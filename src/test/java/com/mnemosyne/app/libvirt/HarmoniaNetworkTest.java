package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.mnemosyne.app.model.ExtraDisk;
import com.mnemosyne.app.model.Plan;
import com.mnemosyne.app.model.Preflight;
import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.model.Templates;
import com.mnemosyne.app.utils.XmlUtil;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.libvirt.Network;
import org.libvirt.StoragePool;
import org.libvirt.StorageVol;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Harmonia, guest network")
public class HarmoniaNetworkTest {

  private static final String NAME = "web-01";
  private static final String IMAGES = "/var/lib/libvirt/images/";

  @Mock Connect connect;
  @Mock Domain domain;
  @Mock GuestOps guestOps;
  @Mock Network network;
  @Mock StoragePool pool;
  @Mock StorageVol created;

  private static String domainXml(String recorded) {
    return domainXml(recorded, AGENT);
  }

  private static String domainXml(String recorded, String channel) {
    return """
        <domain type='kvm'>
          <name>web-01</name>
          <metadata>
            <mnem:mnemosyne xmlns:mnem="https://mnemosyne.dev/schema/v1">
              <mnem:managedBy>mnemosyne</mnem:managedBy>
              <mnem:serverId>web-01</mnem:serverId>
              <mnem:disks><mnem:disk path='/var/lib/libvirt/images/web-01.qcow2'/></mnem:disks>
              <mnem:init state='finished' token='t' created='2026-09-25T10:00:00Z'/>
              %s
            </mnem:mnemosyne>
          </metadata>
          <memory unit='KiB'>2097152</memory>
          <vcpu placement='static'>2</vcpu>
          <devices>
            <disk type='file' device='disk'>
              <source file='/var/lib/libvirt/images/web-01.qcow2'/>
              <target dev='vda' bus='virtio'/>
            </disk>
            <interface type='network'>
              <mac address='52:54:00:46:96:dc'/>
              <source network='lan'/>
              <model type='virtio'/>
            </interface>
            %s
          </devices>
        </domain>
        """
        .formatted(recorded, channel);
  }

  private static final String AGENT =
      "<channel type='unix'><target type='virtio' name='org.qemu.guest_agent.0'/></channel>";

  private static final String RECORDED = "<mnem:network ip='10.0.0.5/24' gateway='10.0.0.1'/>";

  private static Server server(String ip, String network, boolean launch) {
    Templates t = new Templates();
    t.setServerTmpl(HarmoniaNetworkTest.class.getResource("/server-template.xml").getPath());
    t.setVolTmpl(HarmoniaNetworkTest.class.getResource("/volume.xml").getPath());
    t.setNetworkConfigTmpl(HarmoniaNetworkTest.class.getResource("/network-config.yml").getPath());
    Server s = new Server();
    s.setId(NAME);
    s.setName(NAME);
    s.setCpu(2);
    s.setRam(2048);
    s.setDisk(10);
    s.setIp(ip);
    s.setGateway("10.0.0.1");
    s.setNetwork(network);
    s.setLaunch(launch);
    s.setTemplates(t);
    return s;
  }

  private void host(String recorded, Integer first, Integer... next) throws LibvirtException {
    when(connect.listAllDomains(0)).thenReturn(new Domain[] {domain});
    when(connect.domainLookupByName(NAME)).thenReturn(domain);
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(domainXml(recorded));
    when(domain.getXMLDesc(0)).thenReturn(domainXml(recorded));
    when(domain.isActive()).thenReturn(first, next);
    when(domain.getAutostart()).thenReturn(false);
    when(connect.storageVolLookupByPath(anyString())).thenThrow(mock(LibvirtException.class));
  }

  private void liveMoveRefused() throws LibvirtException {
    doReturn(0).when(domain).updateDeviceFlags(anyString(), eq(Domain.DeviceModifyFlags.CONFIG));
    doThrow(mock(LibvirtException.class))
        .when(domain)
        .updateDeviceFlags(anyString(), eq(Domain.DeviceModifyFlags.LIVE));
  }

  @Test
  void aFailedAddressChangeStillStopsAVmThatMustStop() throws Exception {
    host(RECORDED, 1, 1, 0);
    doThrow(new IllegalStateException("qemu-guest-agent did not answer within 180s"))
        .when(guestOps)
        .applyNetplan(anyString(), anyString());
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.0.0.6/24", "lan", false)), false);
    h.reconcile(1);

    InOrder order = inOrder(guestOps, domain);
    order.verify(guestOps).applyNetplan(eq(NAME), anyString());
    order.verify(domain).shutdown();
    assertThat(h.failures()).isEqualTo(1);
    assertThat(metadataWritten()).isEmpty();
  }

  @Test
  void aRunningVmWhoseInterfaceCannotMoveLiveIsNotReaddressedOnTheOldNetwork() throws Exception {
    host(RECORDED, 1);
    liveMoveRefused();
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.1.0.5/24", "dmz", true)), false);
    h.reconcile(1);

    verifyNoInteractions(guestOps);
    verify(domain).updateDeviceFlags(anyString(), eq(Domain.DeviceModifyFlags.CONFIG));
    assertThat(h.failures()).isEqualTo(1);
    assertThat(metadataWritten()).isEmpty();
  }

  @Test
  void aVmBeingStoppedWhoseInterfaceCannotMoveLiveIsReaddressedOnItsNewNetwork() throws Exception {
    host(RECORDED, 1, 1, 0, 1, 0);
    liveMoveRefused();
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.1.0.5/24", "dmz", false)), false);
    h.reconcile(1);

    InOrder order = inOrder(guestOps, domain);
    order.verify(domain).shutdown();
    order.verify(domain).create();
    order.verify(guestOps).applyNetplan(eq(NAME), contains("10.1.0.5/24"));
    assertThat(h.failures()).isZero();
  }

  @Test
  void aLiveMoveThatFailsWithoutAnAddressChangeIsOnlyNoted() throws Exception {
    host(RECORDED, 1);
    liveMoveRefused();
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.0.0.5/24", "dmz", true)), false);
    h.reconcile(1);

    verifyNoInteractions(guestOps);
    assertThat(h.failures()).isZero();
  }

  @Test
  void preflightRefusesANewAddressForAVmWithoutAnAgentChannel() throws Exception {
    host(RECORDED, 1);
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(domainXml(RECORDED, ""));
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.0.0.6/24", "lan", true)), false);

    assertThat(h.preflight().blockers(NAME))
        .anySatisfy(b -> assertThat(b).contains("no qemu-guest-agent channel"));
  }

  @Test
  void aVmWithoutAnAgentChannelCanStillMoveNetwork() throws Exception {
    host(RECORDED, 1);
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(domainXml(RECORDED, ""));
    when(connect.networkLookupByName("dmz")).thenReturn(network);
    when(network.isActive()).thenReturn(1);
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.0.0.5/24", "dmz", true)), false);

    assertThat(h.preflight().ok()).isTrue();
  }

  private Harmonia harmonia() {
    return new Harmonia("hv01", connect, guestOps);
  }

  private List<String> metadataWritten() throws LibvirtException {
    ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
    verify(domain, atLeast(0))
        .setMetadata(anyInt(), meta.capture(), eq("mnem"), eq(XmlUtil.MNEM_NS), anyInt());
    return meta.getAllValues();
  }

  @Test
  void aRunningVmGetsItsNewAddressInPlace_andTheRecordFollows() throws Exception {
    host(RECORDED, 1);
    Harmonia h = harmonia();
    Plan plan = h.plan(Map.of(NAME, server("10.0.0.6/24", "lan", true)), false);
    h.reconcile(1);

    assertThat(plan.getToUpdate().get(NAME).diff()).isEqualTo("ip 10.0.0.5/24->10.0.0.6/24");
    verify(guestOps).applyNetplan(eq(NAME), contains("10.0.0.6/24"));
    verify(domain, never()).create();
    verify(domain, never()).shutdown();
    assertThat(metadataWritten())
        .singleElement()
        .asString()
        .contains("<network ip='10.0.0.6/24' gateway='10.0.0.1'/>")
        .contains("<disks><disk path='" + IMAGES + "web-01.qcow2'/></disks>")
        .contains("<init state='finished' token='t' created='2026-09-25T10:00:00Z'/>");
    assertThat(h.failures()).isZero();
  }

  @Test
  void aVmBeingStoppedGetsItsAddressBeforeTheShutdown() throws Exception {
    host(RECORDED, 1, 1, 1, 0);
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.0.0.6/24", "lan", false)), false);
    h.reconcile(1);

    InOrder order = inOrder(guestOps, domain);
    order.verify(guestOps).applyNetplan(eq(NAME), anyString());
    order.verify(domain).shutdown();
    verify(domain, never()).create();
    assertThat(h.failures()).isZero();
  }

  @Test
  void aStoppedVmIsStartedForTheChange_andStoppedAgain() throws Exception {
    host(RECORDED, 0, 1, 0);
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.0.0.6/24", "lan", false)), false);
    h.reconcile(1);

    InOrder order = inOrder(guestOps, domain);
    order.verify(domain).create();
    order.verify(guestOps).applyNetplan(eq(NAME), anyString());
    order.verify(domain).shutdown();
    assertThat(metadataWritten()).singleElement().asString().contains("ip='10.0.0.6/24'");
    assertThat(h.failures()).isZero();
  }

  @Test
  void aStoppedVmThatMustRunIsStartedFirst_andLeftRunning() throws Exception {
    host(RECORDED, 0, 1);
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.0.0.6/24", "lan", true)), false);
    h.reconcile(1);

    InOrder order = inOrder(guestOps, domain);
    order.verify(domain).create();
    order.verify(guestOps).applyNetplan(eq(NAME), anyString());
    verify(domain, never()).shutdown();
  }

  @Test
  void aGuestThatCannotApplyIt_isSkipped_andTheOldRecordStays() throws Exception {
    host(RECORDED, 1);
    doThrow(new IllegalStateException("qemu-guest-agent did not answer within 180s"))
        .when(guestOps)
        .applyNetplan(anyString(), anyString());
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.0.0.6/24", "lan", true)), false);
    h.reconcile(1);

    assertThat(h.failures()).isEqualTo(1);
    assertThat(metadataWritten()).isEmpty();
  }

  @Test
  void aStoppedVmIsStoppedAgainEvenWhenTheGuestFails() throws Exception {
    host(RECORDED, 0, 1, 0);
    doThrow(new IllegalStateException("netplan in the guest failed (exit 1): bad"))
        .when(guestOps)
        .applyNetplan(anyString(), anyString());
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.0.0.6/24", "lan", false)), false);
    h.reconcile(1);

    verify(domain).create();
    verify(domain).shutdown();
    assertThat(h.failures()).isEqualTo(1);
    assertThat(metadataWritten()).isEmpty();
  }

  @Test
  void anUnrecordedAddressIsNeverPushedIntoTheGuest() throws Exception {
    host("", 1);
    Harmonia h = harmonia();
    Plan plan = h.plan(Map.of(NAME, server("10.0.0.6/24", "lan", true)), false);
    h.reconcile(1);

    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getNotes().get(NAME)).isNotEmpty();
    verifyNoInteractions(guestOps);
  }

  @Test
  void aNewLibvirtNetworkIsWrittenToTheConfig_andLive() throws Exception {
    host(RECORDED, 1);
    Harmonia h = harmonia();
    Plan plan = h.plan(Map.of(NAME, server("10.0.0.5/24", "dmz", true)), false);
    h.reconcile(1);

    assertThat(plan.getToUpdate().get(NAME).diff()).isEqualTo("network lan->dmz");
    verify(domain)
        .updateDeviceFlags(contains("network=\"dmz\""), eq(Domain.DeviceModifyFlags.CONFIG));
    verify(domain)
        .updateDeviceFlags(contains("network=\"dmz\""), eq(Domain.DeviceModifyFlags.LIVE));
    verifyNoInteractions(guestOps);
    assertThat(metadataWritten()).isEmpty();
    assertThat(h.failures()).isZero();
  }

  @Test
  void theNetworkIsMovedBeforeTheGuestIsReaddressed() throws Exception {
    host(RECORDED, 1);
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.1.0.5/24", "dmz", true)), false);
    h.reconcile(1);

    InOrder order = inOrder(domain, guestOps);
    order.verify(domain).updateDeviceFlags(anyString(), eq(Domain.DeviceModifyFlags.CONFIG));
    order.verify(guestOps).applyNetplan(eq(NAME), contains("10.1.0.5/24"));
  }

  @Test
  void aDiskAttachedInTheSameRunStaysInTheRecord() throws Exception {
    host(RECORDED, 1);
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    LibvirtException missing = notFound();
    when(pool.storageVolLookupByName("web-01-data.qcow2")).thenThrow(missing);
    when(pool.storageVolCreateXML(anyString(), eq(0))).thenReturn(created);
    when(created.getPath()).thenReturn(IMAGES + "web-01-data.qcow2");
    ExtraDisk disk = new ExtraDisk();
    disk.setName("data");
    disk.setSize(20);
    disk.setPool("default");
    Server s = server("10.0.0.6/24", "lan", true);
    s.setExtraDisks(List.of(disk));

    Harmonia h = harmonia();
    h.plan(Map.of(NAME, s), false);
    h.reconcile(1);

    List<String> written = metadataWritten();
    assertThat(written).hasSize(2);
    assertThat(written.get(0))
        .contains("<disk path='" + IMAGES + "web-01-data.qcow2'/>")
        .contains("ip='10.0.0.5/24'");
    assertThat(written.get(1))
        .contains("<disk path='" + IMAGES + "web-01-data.qcow2'/>")
        .contains("ip='10.0.0.6/24'");
    assertThat(h.failures()).isZero();
  }

  @Test
  void preflightRefusesAMoveToANetworkTheHostDoesNotHave() throws Exception {
    host(RECORDED, 1);
    when(connect.networkLookupByName("dmz")).thenThrow(mock(LibvirtException.class));
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.0.0.5/24", "dmz", true)), false);
    Preflight preflight = h.preflight();

    assertThat(preflight.blockers(NAME))
        .singleElement()
        .asString()
        .contains("network 'dmz'")
        .contains("not found");
  }

  @Test
  void preflightAcceptsARunningNetwork() throws Exception {
    host(RECORDED, 1);
    when(connect.networkLookupByName("dmz")).thenReturn(network);
    when(network.isActive()).thenReturn(1);
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, server("10.0.0.5/24", "dmz", true)), false);

    assertThat(h.preflight().ok()).isTrue();
  }

  @Test
  void preflightRefusesANewAddressWithoutItsTemplates() throws Exception {
    host(RECORDED, 1);
    Server s = server("10.0.0.6/24", "lan", true);
    s.getTemplates().setNetworkConfigTmpl("/nonexistent/network-config.yml");
    Harmonia h = harmonia();
    h.plan(Map.of(NAME, s), false);

    assertThat(h.preflight().blockers(NAME))
        .anySatisfy(b -> assertThat(b).contains("/nonexistent/network-config.yml"));
  }

  private static LibvirtException notFound() {
    org.libvirt.Error error = mock(org.libvirt.Error.class);
    when(error.getCode()).thenReturn(org.libvirt.Error.ErrorNumber.VIR_ERR_NO_STORAGE_VOL);
    LibvirtException e = mock(LibvirtException.class);
    when(e.getError()).thenReturn(error);
    return e;
  }
}
