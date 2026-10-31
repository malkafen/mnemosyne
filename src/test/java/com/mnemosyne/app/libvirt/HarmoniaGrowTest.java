package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mnemosyne.app.model.ExtraDisk;
import com.mnemosyne.app.model.Plan;
import com.mnemosyne.app.model.Server;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.libvirt.StorageVol;
import org.libvirt.StorageVolInfo;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Harmonia, growing disks")
public class HarmoniaGrowTest {

  private static final long GIB = 1024L * 1024 * 1024;
  private static final String NAME = "web-01";
  private static final String ROOT = "/var/lib/libvirt/images/web-01.qcow2";
  private static final String DATA = "/var/lib/libvirt/images/web-01-data.qcow2";

  @Mock Connect connect;
  @Mock Domain domain;
  @Mock StorageVol rootVol;
  @Mock StorageVol dataVol;

  private static String domainXml(boolean withData) {
    return """
        <domain type='kvm'>
          <name>web-01</name>
          <metadata>
            <mnem:mnemosyne xmlns:mnem="https://mnemosyne.dev/schema/v1">
              <mnem:managedBy>mnemosyne</mnem:managedBy>
              <mnem:serverId>web-01</mnem:serverId>
              <mnem:specVersion>1</mnem:specVersion>
              <mnem:init state='finished' token='t' created='2026-09-25T10:00:00Z'/>
            </mnem:mnemosyne>
          </metadata>
          <memory unit='KiB'>2097152</memory>
          <vcpu placement='static'>2</vcpu>
          <devices>
            <disk type='file' device='disk'>
              <source file='/var/lib/libvirt/images/web-01.qcow2'/>
              <target dev='vda' bus='virtio'/>
            </disk>
        """
        + (withData
            ? """
                <disk type='file' device='disk'>
                  <source file='/var/lib/libvirt/images/web-01-data.qcow2'/>
                  <target dev='vdb' bus='virtio'/>
                  <serial>data</serial>
                </disk>
            """
            : "")
        + """
          </devices>
        </domain>
        """;
  }

  private static Server server(int rootGiB, boolean launch, ExtraDisk... extras) {
    Server s = new Server();
    s.setId(NAME);
    s.setName(NAME);
    s.setCpu(2);
    s.setRam(2048);
    s.setDisk(rootGiB);
    s.setLaunch(launch);
    s.setExtraDisks(List.of(extras));
    return s;
  }

  private static ExtraDisk extra(String name, int size) {
    ExtraDisk d = new ExtraDisk();
    d.setName(name);
    d.setSize(size);
    d.setPool("default");
    return d;
  }

  private static StorageVolInfo info(long capacityGiB) {
    StorageVolInfo i = mock(StorageVolInfo.class);
    i.capacity = capacityGiB * GIB;
    return i;
  }

  private void host(boolean running, boolean withData) throws LibvirtException {
    when(connect.listAllDomains(0)).thenReturn(new Domain[] {domain});
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(domainXml(withData));
    when(domain.isActive()).thenReturn(running ? 1 : 0);
    when(domain.getAutostart()).thenReturn(false);
  }

  @Test
  void aRunningDomainIsResizedThroughQemu_soTheGuestSeesItAtOnce() throws LibvirtException {
    host(true, false);
    when(connect.storageVolLookupByPath(ROOT)).thenReturn(rootVol);
    when(rootVol.getInfo()).thenReturn(info(25));
    when(connect.domainLookupByName(NAME)).thenReturn(domain);

    Harmonia harmonia = new Harmonia("hv01", connect);
    Plan plan = harmonia.plan(Map.of(NAME, server(40, true)), false);
    harmonia.reconcile(1);
    assertThat(plan.getToUpdate().get(NAME).diff()).contains("grow root disk 25G->40G");
    verify(domain).blockResize("vda", 40 * GIB, 1);
    verify(rootVol, never()).resize(anyLong(), anyInt());
  }

  @Test
  void aShutDownDomainIsResizedThroughItsVolume() throws LibvirtException {
    host(false, false);
    when(connect.storageVolLookupByPath(ROOT)).thenReturn(rootVol);
    when(rootVol.getInfo()).thenReturn(info(25));

    Harmonia harmonia = new Harmonia("hv01", connect);
    harmonia.plan(Map.of(NAME, server(40, false)), false);
    harmonia.reconcile(1);
    verify(rootVol).resize(40 * GIB, 0);
    verify(domain, never()).blockResize(anyString(), anyLong(), anyInt());
  }

  @Test
  void anExtraDiskIsGrownByItsOwnTarget_notTheRootDisks() throws LibvirtException {
    host(true, true);
    when(connect.storageVolLookupByPath(ROOT)).thenReturn(rootVol);
    when(connect.storageVolLookupByPath(DATA)).thenReturn(dataVol);
    when(rootVol.getInfo()).thenReturn(info(25));
    when(dataVol.getInfo()).thenReturn(info(10));
    when(connect.domainLookupByName(NAME)).thenReturn(domain);

    Harmonia harmonia = new Harmonia("hv01", connect);
    harmonia.plan(Map.of(NAME, server(25, true, extra("data", 20))), false);
    harmonia.reconcile(1);
    verify(domain).blockResize("vdb", 20 * GIB, 1);
    verify(domain, never()).blockResize(eq("vda"), anyLong(), anyInt());
  }

  @Test
  void sizesThatAlreadyMatchCostNoResizeAtAll() throws LibvirtException {
    host(true, false);
    when(connect.storageVolLookupByPath(ROOT)).thenReturn(rootVol);
    when(rootVol.getInfo()).thenReturn(info(25));

    Harmonia harmonia = new Harmonia("hv01", connect);
    Plan plan = harmonia.plan(Map.of(NAME, server(25, true)), false);
    harmonia.reconcile(1);
    assertThat(plan.getToUpdate()).isEmpty();
    verify(domain, never()).blockResize(anyString(), anyLong(), anyInt());
    verify(rootVol, never()).resize(anyLong(), anyInt());
  }

  @Test
  void aDiskBiggerThanTheInventoryBlocksTheRun_andIsNeverShrunk() throws LibvirtException {
    host(true, false);
    when(connect.storageVolLookupByPath(ROOT)).thenReturn(rootVol);
    when(rootVol.getInfo()).thenReturn(info(50));

    Harmonia harmonia = new Harmonia("hv01", connect);
    harmonia.plan(Map.of(NAME, server(40, true)), false);
    var preflight = harmonia.preflight();
    assertThat(preflight.ok()).isFalse();
    assertThat(preflight.blockers(NAME))
        .singleElement()
        .asString()
        .contains("50G on the host, 40G in the inventory")
        .contains("never shrunk");
  }

  @Test
  void aVolumeWhoseSizeCannotBeReadIsNeverResized() throws LibvirtException {
    host(true, false);
    when(connect.storageVolLookupByPath(ROOT)).thenThrow(mock(LibvirtException.class));

    Harmonia harmonia = new Harmonia("hv01", connect);
    Plan plan = harmonia.plan(Map.of(NAME, server(40, true)), false);
    harmonia.reconcile(1);
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getShrinks()).isEmpty();
    verify(domain, never()).blockResize(anyString(), anyLong(), anyInt());
  }

  @Test
  void anUnmanagedDomainIsNotEvenMeasured() throws LibvirtException {
    when(connect.listAllDomains(0)).thenReturn(new Domain[] {domain});
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE))
        .thenReturn(
            domainXml(false)
                .replace("mnemosyne</mnem:managedBy>", "someone-else</mnem:managedBy>"));
    when(domain.isActive()).thenReturn(1);
    when(domain.getAutostart()).thenReturn(false);

    Harmonia harmonia = new Harmonia("hv01", connect);
    harmonia.plan(Map.of(NAME, server(40, true)), false);
    verify(connect, never()).storageVolLookupByPath(anyString());
  }
}
