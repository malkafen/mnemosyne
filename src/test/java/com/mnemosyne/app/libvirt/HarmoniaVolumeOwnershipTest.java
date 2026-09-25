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

/**
 * Which volumes a new VM calls its own.
 *
 * <p>Only a volume created by this run is recorded in {@code <mnem:disks>} and may be deleted with
 * the VM. One found in the pool is attached as it is and recorded in {@code <mnem:reused-disks>};
 * Mnemosyne cannot tell a leftover of its own from a disk somebody put there, so it deletes neither
 * and says so. A creation that fails takes back only what it created itself.
 */
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

  /** Templates whose domain XML cannot be built: the failure comes after the volumes exist. */
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

  /** The root volume is already in the pool. */
  private void rootVolumeExists() throws LibvirtException {
    when(pool.storageVolLookupByName("qa-a.qcow2")).thenReturn(existing);
    when(existing.getPath()).thenReturn(IMAGES + "qa-a.qcow2");
  }

  /** The root volume is not in the pool, and cloning the base image makes it. */
  private void rootVolumeIsCloned() throws LibvirtException {
    LibvirtException missing = notFound(Error.ErrorNumber.VIR_ERR_NO_STORAGE_VOL);
    when(pool.storageVolLookupByName("qa-a.qcow2")).thenThrow(missing);
    when(pool.storageVolLookupByName(BASE)).thenReturn(base);
    when(pool.storageVolCreateXMLFrom(anyString(), eq(base), eq(0))).thenReturn(cloned);
    // Not asked for when the resize fails: the clone is deleted before its path is read.
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
    // Arrange
    emptyHost();
    rootVolumeExists();
    when(connect.domainDefineXML(anyString())).thenReturn(domain);
    // Act
    create(server(fixtures()));
    // Assert
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
    // Arrange
    emptyHost();
    rootVolumeIsCloned();
    when(connect.domainDefineXML(anyString())).thenReturn(domain);
    // Act
    create(server(fixtures()));
    // Assert
    ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
    verify(connect).domainDefineXML(xml.capture());
    DomainState state = XmlUtil.getShortState(xml.getValue());
    assertThat(state.ownedPaths()).containsExactly(IMAGES + "qa-a.qcow2");
    assertThat(state.reusedPaths()).isEmpty();
    assertThat(report()).doesNotContain("reused");
  }

  @Test
  void aFailedCreation_deletesTheVolumeItCreated() throws Exception {
    // Arrange
    emptyHost();
    rootVolumeIsCloned();
    LibvirtException noDomain = notFound(Error.ErrorNumber.VIR_ERR_NO_DOMAIN);
    when(connect.domainLookupByName("qa-a")).thenThrow(noDomain);
    when(connect.storageVolLookupByPath(IMAGES + "qa-a.qcow2")).thenReturn(cloned);
    // Act
    create(server(withoutInterface()));
    // Assert
    verify(cloned).delete(0);
    verify(connect, never()).domainDefineXML(anyString());
    assertThat(report())
        .contains("· qa-a")
        .contains(IMAGES + "qa-a.qcow2 - created by this attempt, deleted");
  }

  @Test
  void aFailedCreation_neverDeletesAVolumeItFoundInThePool() throws Exception {
    // Arrange
    emptyHost();
    rootVolumeExists();
    // Act
    create(server(withoutInterface()));
    // Assert
    verify(existing, never()).delete(anyInt());
    verify(connect, never()).storageVolLookupByPath(anyString());
    assertThat(report()).contains("· qa-a").doesNotContain("deleted");
  }

  @Test
  void aFailedCreation_keepsItsVolumeWhileADomainOfThatNameIsDefined() throws Exception {
    // The volume may be under that domain; the operator gets its path instead of a broken VM.
    // Arrange
    emptyHost();
    rootVolumeIsCloned();
    when(connect.domainLookupByName("qa-a")).thenReturn(domain);
    // Act
    create(server(withoutInterface()));
    // Assert
    verify(cloned, never()).delete(anyInt());
    assertThat(report())
        .contains(
            IMAGES + "qa-a.qcow2 - created by this attempt, left in the pool: delete it by hand");
  }

  @Test
  void aVolumeThatCannotBeResized_isStillRolledBackByStorageOps_notTwice() throws Exception {
    // The clone that fails its resize is deleted inside StorageOps and never reaches `created`.
    // Arrange
    emptyHost();
    rootVolumeIsCloned();
    when(cloned.resize(anyLong(), anyInt())).thenThrow(mock(LibvirtException.class));
    // Act
    create(server(fixtures()));
    // Assert
    verify(cloned).delete(0);
    verify(connect, never()).domainLookupByName(anyString());
    verify(connect, never()).domainDefineXML(any());
  }

  /** A shut-down managed VM with only its root disk, and whatever {@code <mnem:disks>} records. */
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

  /**
   * The metadata {@code attachDisks} records for a data disk whose volume is already there, or null
   * when it had nothing new to record.
   */
  private String metadataAfterAttachingAnExistingVolume(String recorded) throws Exception {
    String data = IMAGES + "qa-a-data.qcow2";
    when(connect.listAllDomains(0)).thenReturn(new Domain[] {domain});
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(managedDomainXml(recorded));
    lenient().when(domain.getXMLDesc(0)).thenReturn(managedDomainXml(recorded));
    when(domain.isActive()).thenReturn(0);
    when(domain.getAutostart()).thenReturn(false);
    // The root disk is the size the inventory asks for, so the plan has nothing to grow.
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
    // Act
    String meta =
        metadataAfterAttachingAnExistingVolume(
            "<mnem:disk path='/var/lib/libvirt/images/qa-a.qcow2'/>");
    // Assert
    assertThat(meta)
        .contains("<disks><disk path='" + IMAGES + "qa-a.qcow2'/></disks>")
        .contains("<reused-disks><volume path='" + IMAGES + "qa-a-data.qcow2'/></reused-disks>");
  }

  @Test
  void aRetriedAttach_findsTheVolumeThisVmCreatedLastTime_andItStaysOwned() throws Exception {
    // The previous run created and recorded the volume, then failed to attach it.
    // Act
    String meta =
        metadataAfterAttachingAnExistingVolume(
            "<mnem:disk path='/var/lib/libvirt/images/qa-a.qcow2'/>"
                + "<mnem:disk path='/var/lib/libvirt/images/qa-a-data.qcow2'/>");
    // Assert: already on the owned list, so nothing is rewritten and nothing moves to reused
    assertThat(meta).isNull();
  }
}
