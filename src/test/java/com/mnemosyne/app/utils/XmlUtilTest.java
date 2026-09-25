package com.mnemosyne.app.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mnemosyne.app.exception.XmlParseException;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.InitMarker;
import java.util.List;
import org.junit.jupiter.api.Test;

public class XmlUtilTest {

  private static final String MIXED_DEVICES =
      """
      <domain type="kvm">
        <name>mixed-vm</name>
        <memory unit="KiB">2097152</memory>
        <vcpu placement="static">2</vcpu>
        <devices>
          <disk type="file" device="disk">
            <source file="/var/lib/libvirt/images/system.qcow2"/>
          </disk>
          <disk type="file" device="cdrom">
            <source file="/var/lib/libvirt/images/seed.iso"/>
          </disk>
          <disk type="network" device="disk">
            <source protocol="rbd" name="pool/vol"/>
          </disk>
          <disk type="file" device="disk">
            <source file="/var/lib/libvirt/images/data.qcow2"/>
          </disk>
        </devices>
      </domain>
      """;

  @Test
  void diskPaths_skipsCdromAndSourcelessDisks_keepsOrder() {
    // Act
    List<String> paths = XmlUtil.diskPaths(MIXED_DEVICES);
    // Assert
    assertThat(paths)
        .containsExactly(
            "/var/lib/libvirt/images/system.qcow2", "/var/lib/libvirt/images/data.qcow2");
  }

  private static final String DISKS_WITH_SERIALS =
      """
      <domain type="kvm">
        <name>web-01</name>
        <memory unit="KiB">2097152</memory>
        <vcpu placement="static">2</vcpu>
        <devices>
          <disk type="file" device="disk">
            <source file="/var/lib/libvirt/images/web-01.qcow2"/>
            <target dev="vda" bus="virtio"/>
          </disk>
          <disk type="file" device="disk">
            <source file="/var/lib/libvirt/images/web-01-data.qcow2"/>
            <target dev="vdb" bus="virtio"/>
            <serial>data</serial>
          </disk>
          <disk type="file" device="cdrom">
            <source file="/var/lib/libvirt/images/seed.iso"/>
            <target dev="hdc" bus="ide"/>
          </disk>
        </devices>
        <serial type="pty">
          <target port="0"/>
        </serial>
      </domain>
      """;

  @Test
  void getShortState_readsTargetAndSerialOfEveryFileBackedDisk() {
    // Act
    DomainState state = XmlUtil.getShortState(DISKS_WITH_SERIALS);
    // Assert
    assertThat(state.disks())
        .containsExactly(
            new DomainState.Disk("vda", "/var/lib/libvirt/images/web-01.qcow2", null),
            new DomainState.Disk("vdb", "/var/lib/libvirt/images/web-01-data.qcow2", "data"));
  }

  @Test
  void disks_serialIsReadFromTheDiskNotFromTheConsoleDevice() {
    // A document-wide lookup for <serial> would find <serial type="pty">, the console, and
    // attribute its (empty) text to the root disk.
    // Act
    DomainState state = XmlUtil.getShortState(DISKS_WITH_SERIALS);
    // Assert
    assertThat(state.disks().get(0).serial()).isNull();
  }

  @Test
  void diskVolName_isTheFileNameThatTheInventoryCanPredict() {
    // This is what the plan matches an extra disk on.
    // Act & Assert
    assertThat(XmlUtil.getShortState(DISKS_WITH_SERIALS).disks().get(1).volName())
        .isEqualTo("web-01-data.qcow2");
  }

  @Test
  void usedDiskTargets_countsTheCdromToo() {
    // The cdrom is not a disk Mnemosyne owns, but it owns a target name all the same, and giving
    // that name to a data disk would collide.
    // Act & Assert
    assertThat(XmlUtil.usedDiskTargets(DISKS_WITH_SERIALS)).containsExactly("vda", "vdb", "hdc");
  }

  @Test
  void poolTargetPath_readsADirectoryBackedPool() {
    // Arrange
    String pool =
        """
        <pool type="dir">
          <name>default</name>
          <target><path>/var/lib/libvirt/images</path></target>
        </pool>
        """;
    // Act & Assert
    assertThat(XmlUtil.poolTargetPath(pool)).isEqualTo("/var/lib/libvirt/images");
  }

  @Test
  void poolTargetPath_poolWithoutADirectory_isNull() {
    // Act & Assert
    assertThat(XmlUtil.poolTargetPath("<pool type=\"rbd\"><name>ceph</name></pool>")).isNull();
  }

  /** How libvirt actually renders a persistent config: KiB, with the balloon tag present. */
  private static final String DOMAIN_2_GIB =
      """
      <domain type="kvm">
        <name>web-01</name>
        <memory unit="KiB">2097152</memory>
        <currentMemory unit="KiB">2097152</currentMemory>
        <vcpu placement="static">2</vcpu>
        <devices/>
      </domain>
      """;

  @Test
  void withMemory_writesKibValueAndRewritesUnit() {
    // Act
    String patched = XmlUtil.withMemory(DOMAIN_2_GIB, 4096);
    // Assert
    assertThat(patched).contains("<memory unit=\"KiB\">4194304</memory>");
  }

  @Test
  void withMemory_patchesCurrentMemoryToo() {
    // The boot size would apply but the balloon would hold the domain at the old size.
    // Act
    String patched = XmlUtil.withMemory(DOMAIN_2_GIB, 4096);
    // Assert
    assertThat(patched).contains("<currentMemory unit=\"KiB\">4194304</currentMemory>");
  }

  @Test
  void withMemory_roundTripsThroughGetShortState() {
    // Guards the unit convention end to end: what we write must read back unchanged.
    // Act
    DomainState state = XmlUtil.getShortState(XmlUtil.withMemory(DOMAIN_2_GIB, 4096));
    // Assert
    assertThat(state.ram()).isEqualTo(4096);
    assertThat(state.cpu()).isEqualTo(2);
  }

  @Test
  void withMemory_currentMemoryAbsent_stillPatchesMemory() {
    // Arrange
    String noBalloon = DOMAIN_2_GIB.replaceAll("(?m)^.*currentMemory.*\\R", "");
    // Act
    String patched = XmlUtil.withMemory(noBalloon, 8192);
    // Assert
    assertThat(XmlUtil.getShortState(patched).ram()).isEqualTo(8192);
    assertThat(patched).doesNotContain("currentMemory");
  }

  @Test
  void withMemory_memoryAbsent_throws() {
    // Arrange
    String noMemory = DOMAIN_2_GIB.replaceAll("(?m)^.*<memory.*\\R", "");
    // Act & Assert
    assertThatThrownBy(() -> XmlUtil.withMemory(noMemory, 4096))
        .isInstanceOf(XmlParseException.class)
        .hasMessageContaining("<memory>");
  }

  @Test
  void withMemory_staleMibUnit_isRewrittenToKib() {
    // A hand-edited domain left on MiB must not survive the patch as MiB.
    // Arrange
    String stale = DOMAIN_2_GIB.replace("unit=\"KiB\">2097152", "unit=\"MiB\">2048");
    // Act
    String patched = XmlUtil.withMemory(stale, 4096);
    // Assert
    assertThat(patched).doesNotContain("MiB");
    assertThat(XmlUtil.getShortState(patched).ram()).isEqualTo(4096);
  }

  @Test
  void getShortState_marksTheDisksListedInMnemDisksAsOwned() {
    String xml =
        """
        <domain type='kvm'>
          <name>web-01</name>
          <metadata>
            <mnem:mnemosyne xmlns:mnem="https://mnemosyne.dev/schema/v1">
              <mnem:managedBy>mnemosyne</mnem:managedBy>
              <mnem:serverId>web-01</mnem:serverId>
              <mnem:disks>
                <mnem:disk path='/img/web-01.qcow2'/>
                <mnem:disk path='/img/web-01-cache.qcow2'/>
              </mnem:disks>
            </mnem:mnemosyne>
          </metadata>
          <memory unit='KiB'>2097152</memory>
          <vcpu>2</vcpu>
          <devices>
            <disk type='file' device='disk'>
              <source file='/img/web-01.qcow2'/><target dev='vda'/>
            </disk>
            <disk type='file' device='disk'>
              <source file='/img/handmade.qcow2'/><target dev='vdb'/>
            </disk>
          </devices>
        </domain>
        """;

    DomainState s = XmlUtil.getShortState(xml);

    assertThat(s.ownedPaths()).containsExactly("/img/web-01.qcow2");
    assertThat(s.detachedOwned()).containsExactly("/img/web-01-cache.qcow2");
    assertThat(s.disks()).hasSize(2);
  }

  @Test
  void getShortState_readsMnemReusedDisks_andNeverTakesAReusedVolumeForOwned() {
    // A path on both lists can only come from a hand edit; keeping the volume is the mistake that
    // can be undone, so reused wins.
    String xml =
        """
        <domain type='kvm'>
          <name>web-01</name>
          <metadata>
            <mnem:mnemosyne xmlns:mnem="https://mnemosyne.dev/schema/v1">
              <mnem:managedBy>mnemosyne</mnem:managedBy>
              <mnem:serverId>web-01</mnem:serverId>
              <mnem:disks>
                <mnem:disk path='/img/web-01.qcow2'/>
                <mnem:disk path='/img/web-01-data.qcow2'/>
              </mnem:disks>
              <mnem:reused-disks>
                <mnem:volume path='/img/web-01-data.qcow2'/>
                <mnem:volume path='/img/web-01-old.qcow2'/>
              </mnem:reused-disks>
            </mnem:mnemosyne>
          </metadata>
          <memory unit='KiB'>2097152</memory>
          <vcpu>2</vcpu>
          <devices>
            <disk type='file' device='disk'>
              <source file='/img/web-01.qcow2'/><target dev='vda'/>
            </disk>
            <disk type='file' device='disk'>
              <source file='/img/web-01-data.qcow2'/><target dev='vdb'/>
            </disk>
          </devices>
        </domain>
        """;

    DomainState s = XmlUtil.getShortState(xml);

    assertThat(s.ownedPaths()).containsExactly("/img/web-01.qcow2");
    assertThat(s.reusedPaths()).containsExactly("/img/web-01-data.qcow2", "/img/web-01-old.qcow2");
    assertThat(s.detachedReused()).containsExactly("/img/web-01-old.qcow2");
    assertThat(s.detachedOwned()).isEmpty();
  }

  @Test
  void getShortState_readsMnemInit_asItIs() {
    String xml =
        """
        <domain type='kvm'>
          <name>web-01</name>
          <metadata>
            <mnem:mnemosyne xmlns:mnem="https://mnemosyne.dev/schema/v1">
              <mnem:managedBy>mnemosyne</mnem:managedBy>
              <mnem:serverId>web-01</mnem:serverId>
              <mnem:init state='finished' token='ab12' created='2026-09-25T10:00:03Z'
                         finished='2026-09-25T10:04:41Z'/>
            </mnem:mnemosyne>
          </metadata>
          <memory unit='KiB'>2097152</memory>
          <vcpu>2</vcpu>
        </domain>
        """;

    DomainState s = XmlUtil.getShortState(xml);

    assertThat(s.init())
        .isEqualTo(
            new InitMarker("finished", "ab12", "2026-09-25T10:00:03Z", "2026-09-25T10:04:41Z"));
  }

  @Test
  void getShortState_noMnemInit_isNull_notAssumed() {
    String xml =
        """
        <domain type='kvm'>
          <name>web-01</name>
          <metadata>
            <mnem:mnemosyne xmlns:mnem="https://mnemosyne.dev/schema/v1">
              <mnem:managedBy>mnemosyne</mnem:managedBy>
              <mnem:serverId>web-01</mnem:serverId>
            </mnem:mnemosyne>
          </metadata>
          <memory unit='KiB'>2097152</memory>
          <vcpu>2</vcpu>
        </domain>
        """;

    assertThat(XmlUtil.getShortState(xml).init()).isNull();
  }
}
