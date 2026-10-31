package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.model.Templates;
import com.mnemosyne.app.utils.XmlUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.Error;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;
import org.libvirt.StorageVol;
import org.libvirt.StorageVolInfo;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Harmonia, volume ownership at creation")
public class HarmoniaVolumeOwnershipTest {

  private static final String IMAGES = "/var/lib/libvirt/images/";
  private static final String BASE = "debian-13-genericcloud.qcow2";

  @Mock Connect connect;
  @Mock Domain domain;
  @Mock StoragePool pool;
  @Mock StorageVol existing;
  @Mock StorageVol base;
  @Mock StorageVol cloned;

  @TempDir Path tmp;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private PrintStream stdout;

  @BeforeEach
  void captureStdout() {
    stdout = System.out;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void restoreStdout() {
    System.setOut(stdout);
  }

  private String report() {
    return out.toString(StandardCharsets.UTF_8);
  }

  private static Templates fixtures() {
    Templates t = new Templates();
    t.setServerTmpl(
        HarmoniaVolumeOwnershipTest.class.getResource("/server-template.xml").getPath());
    t.setVolTmpl(HarmoniaVolumeOwnershipTest.class.getResource("/volume.xml").getPath());
    t.setMetaDataTmpl(HarmoniaVolumeOwnershipTest.class.getResource("/meta-data.yml").getPath());
    t.setUserDataTmpl(HarmoniaVolumeOwnershipTest.class.getResource("/user-data.yml").getPath());
    t.setNetworkConfigTmpl(
        HarmoniaVolumeOwnershipTest.class.getResource("/network-config.yml").getPath());
    return t;
  }

  private Templates withoutInterface() throws IOException {
    String xml = Files.readString(Path.of(fixtures().getServerTmpl()), StandardCharsets.UTF_8);
    String stripped =
        xml.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)<interface.*?</interface>", "");
    Path copy = tmp.resolve("server-no-interface.xml");
    Files.writeString(copy, stripped, StandardCharsets.UTF_8);
    Templates t = fixtures();
    t.setServerTmpl(copy.toString());
    return t;
  }

  private static Server server(Templates templates) {
    Server s = new Server();
    s.setId("qa-a");
    s.setCpu(2);
    s.setRam(1024);
    s.setDisk(10);
    s.setPool("default");
    s.setNetwork("host-bridge");
    s.setIp("192.168.17.40/24");
    s.setGateway("192.168.17.1");
    s.setVolLookup(BASE);
    s.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    s.setTemplates(templates);
    return s;
  }

  private static LibvirtException notFound(Error.ErrorNumber code) {
    LibvirtException e = mock(LibvirtException.class);
    Error error = mock(Error.class);
    lenient().when(error.getCode()).thenReturn(code);
    lenient().when(e.getError()).thenReturn(error);
    return e;
  }

  private void emptyHost() throws LibvirtException {
    when(connect.listAllDomains(0)).thenReturn(new Domain[0]);
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
  }

  private void rootVolumeExists() throws LibvirtException {
    when(pool.storageVolLookupByName("qa-a.qcow2")).thenReturn(existing);
    when(existing.getPath()).thenReturn(IMAGES + "qa-a.qcow2");
  }

  private void rootVolumeIsCloned() throws LibvirtException {
    LibvirtException missing = notFound(Error.ErrorNumber.VIR_ERR_NO_STORAGE_VOL);
    when(pool.storageVolLookupByName("qa-a.qcow2")).thenThrow(missing);
    when(pool.storageVolLookupByName(BASE)).thenReturn(base);
    when(pool.storageVolCreateXMLFrom(anyString(), eq(base), eq(0))).thenReturn(cloned);
    lenient().when(cloned.getPath()).thenReturn(IMAGES + "qa-a.qcow2");
  }

  private void create(Server s) throws LibvirtException {
    Harmonia harmonia = new Harmonia("bm05", connect);
    harmonia.plan(Map.of(s.getId(), s), false);
    harmonia.reconcile(1);
  }

  @Test
  void aRootVolumeFoundInThePool_isAttachedButRecordedAsReused_andTheReportSaysSo()
      throws Exception {
    emptyHost();
    rootVolumeExists();
    when(connect.domainDefineXML(anyString())).thenReturn(domain);
    create(server(fixtures()));
    ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
    verify(connect).domainDefineXML(xml.capture());
    DomainState state = XmlUtil.getShortState(xml.getValue());
    assertThat(state.diskPaths()).containsExactly(IMAGES + "qa-a.qcow2");
    assertThat(state.ownedPaths()).isEmpty();
    assertThat(state.reusedPaths()).containsExactly(IMAGES + "qa-a.qcow2");

    assertThat(report())
        .contains("+ qa-a")
        .contains(
            "root "
                + IMAGES
                + "qa-a.qcow2 (reused existing volume - not created by mnemosyne, kept when the VM"
                + " is deleted)");
    verify(existing, never()).delete(anyInt());
  }

  @Test
  void aRootVolumeCreatedHere_isOwned() throws Exception {
    emptyHost();
    rootVolumeIsCloned();
    when(connect.domainDefineXML(anyString())).thenReturn(domain);
    create(server(fixtures()));
    ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
    verify(connect).domainDefineXML(xml.capture());
    DomainState state = XmlUtil.getShortState(xml.getValue());
    assertThat(state.ownedPaths()).containsExactly(IMAGES + "qa-a.qcow2");
    assertThat(state.reusedPaths()).isEmpty();
    assertThat(report()).doesNotContain("reused");
  }

  @Test
  void aFailedCreation_deletesTheVolumeItCreated() throws Exception {
    emptyHost();
    rootVolumeIsCloned();
    LibvirtException noDomain = notFound(Error.ErrorNumber.VIR_ERR_NO_DOMAIN);
    when(connect.domainLookupByName("qa-a")).thenThrow(noDomain);
    when(connect.storageVolLookupByPath(IMAGES + "qa-a.qcow2")).thenReturn(cloned);
    create(server(withoutInterface()));
    verify(cloned).delete(0);
    verify(connect, never()).domainDefineXML(anyString());
    assertThat(report())
        .contains("· qa-a")
        .contains(IMAGES + "qa-a.qcow2 - created by this attempt, deleted");
  }

  @Test
  void aFailedCreation_neverDeletesAVolumeItFoundInThePool() throws Exception {
    emptyHost();
    rootVolumeExists();
    create(server(withoutInterface()));
    verify(existing, never()).delete(anyInt());
    verify(connect, never()).storageVolLookupByPath(anyString());
    assertThat(report()).contains("· qa-a").doesNotContain("deleted");
  }

  @Test
  void aFailedCreation_keepsItsVolumeWhileADomainOfThatNameIsDefined() throws Exception {
    emptyHost();
    rootVolumeIsCloned();
    when(connect.domainLookupByName("qa-a")).thenReturn(domain);
    create(server(withoutInterface()));
    verify(cloned, never()).delete(anyInt());
    assertThat(report())
        .contains(
            IMAGES + "qa-a.qcow2 - created by this attempt, left in the pool: delete it by hand");
  }

  @Test
  void aVolumeThatCannotBeResized_isStillRolledBackByStorageOps_notTwice() throws Exception {
    emptyHost();
    rootVolumeIsCloned();
    when(cloned.resize(anyLong(), anyInt())).thenThrow(mock(LibvirtException.class));
    create(server(fixtures()));
    verify(cloned).delete(0);
    verify(connect, never()).domainLookupByName(anyString());
    verify(connect, never()).domainDefineXML(any());
  }

  private static String managedDomainXml(String recorded) {
    return """
        <domain type='kvm'>
          <name>qa-a</name>
          <metadata>
            <mnem:mnemosyne xmlns:mnem="https://mnemosyne.dev/schema/v1">
              <mnem:managedBy>mnemosyne</mnem:managedBy>
              <mnem:serverId>qa-a</mnem:serverId>
              <mnem:specVersion>1</mnem:specVersion>
              <mnem:disks>%s</mnem:disks>
              <mnem:init state='finished' token='t' created='2026-09-25T10:00:00Z'/>
            </mnem:mnemosyne>
          </metadata>
          <memory unit='KiB'>1048576</memory>
          <vcpu placement='static'>2</vcpu>
          <devices>
            <disk type='file' device='disk'>
              <source file='/var/lib/libvirt/images/qa-a.qcow2'/>
              <target dev='vda' bus='virtio'/>
            </disk>
          </devices>
        </domain>
        """
        .formatted(recorded);
  }

  private String metadataAfterAttachingAnExistingVolume(String recorded) throws Exception {
    String data = IMAGES + "qa-a-data.qcow2";
    when(connect.listAllDomains(0)).thenReturn(new Domain[] {domain});
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(managedDomainXml(recorded));
    lenient().when(domain.getXMLDesc(0)).thenReturn(managedDomainXml(recorded));
    when(domain.isActive()).thenReturn(0);
    when(domain.getAutostart()).thenReturn(false);
    StorageVolInfo info = mock(StorageVolInfo.class);
    info.capacity = 10L * 1024 * 1024 * 1024;
    when(connect.storageVolLookupByPath(IMAGES + "qa-a.qcow2")).thenReturn(base);
    when(base.getInfo()).thenReturn(info);
    when(connect.domainLookupByName("qa-a")).thenReturn(domain);
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.storageVolLookupByName("qa-a-data.qcow2")).thenReturn(existing);
    when(existing.getPath()).thenReturn(data);

    com.mnemosyne.app.model.ExtraDisk disk = new com.mnemosyne.app.model.ExtraDisk();
    disk.setName("data");
    disk.setSize(20);
    disk.setPool("default");
    Server s = server(fixtures());
    s.setLaunch(false);
    s.setExtraDisks(java.util.List.of(disk));

    create(s);

    ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
    verify(domain, atMost(1))
        .setMetadata(anyInt(), meta.capture(), eq("mnem"), eq(XmlUtil.MNEM_NS), anyInt());
    verify(existing, never()).delete(anyInt());
    return meta.getAllValues().isEmpty() ? null : meta.getValue();
  }

  @Test
  void aDataDiskAddedLater_whoseVolumeIsAlreadyThere_isRecordedAsReused() throws Exception {
    String meta =
        metadataAfterAttachingAnExistingVolume(
            "<mnem:disk path='/var/lib/libvirt/images/qa-a.qcow2'/>");
    assertThat(meta)
        .contains("<disks><disk path='" + IMAGES + "qa-a.qcow2'/></disks>")
        .contains("<reused-disks><volume path='" + IMAGES + "qa-a-data.qcow2'/></reused-disks>");
  }

  @Test
  void aRetriedAttach_findsTheVolumeThisVmCreatedLastTime_andItStaysOwned() throws Exception {
    String meta =
        metadataAfterAttachingAnExistingVolume(
            "<mnem:disk path='/var/lib/libvirt/images/qa-a.qcow2'/>"
                + "<mnem:disk path='/var/lib/libvirt/images/qa-a-data.qcow2'/>");
    assertThat(meta).isNull();
  }
}
