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
    List<String> paths = XmlUtil.diskPaths(MIXED_DEVICES);
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
    DomainState state = XmlUtil.getShortState(DISKS_WITH_SERIALS);
    assertThat(state.disks())
        .containsExactly(
            new DomainState.Disk("vda", "/var/lib/libvirt/images/web-01.qcow2", null),
            new DomainState.Disk("vdb", "/var/lib/libvirt/images/web-01-data.qcow2", "data"));
  }

  @Test
  void disks_serialIsReadFromTheDiskNotFromTheConsoleDevice() {
    DomainState state = XmlUtil.getShortState(DISKS_WITH_SERIALS);
    assertThat(state.disks().get(0).serial()).isNull();
  }

  @Test
  void diskVolName_isTheFileNameThatTheInventoryCanPredict() {
    assertThat(XmlUtil.getShortState(DISKS_WITH_SERIALS).disks().get(1).volName())
        .isEqualTo("web-01-data.qcow2");
  }

  @Test
  void usedDiskTargets_countsTheCdromToo() {
    assertThat(XmlUtil.usedDiskTargets(DISKS_WITH_SERIALS)).containsExactly("vda", "vdb", "hdc");
  }

  @Test
  void poolTargetPath_readsADirectoryBackedPool() {
    String pool =
        """
        <pool type="dir">
          <name>default</name>
          <target><path>/var/lib/libvirt/images</path></target>
        </pool>
        """;
    assertThat(XmlUtil.poolTargetPath(pool)).isEqualTo("/var/lib/libvirt/images");
  }

  @Test
  void poolTargetPath_poolWithoutADirectory_isNull() {
    assertThat(XmlUtil.poolTargetPath("<pool type=\"rbd\"><name>ceph</name></pool>")).isNull();
  }

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
    String patched = XmlUtil.withMemory(DOMAIN_2_GIB, 4096);
    assertThat(patched).contains("<memory unit=\"KiB\">4194304</memory>");
  }

  @Test
  void withMemory_patchesCurrentMemoryToo() {
    String patched = XmlUtil.withMemory(DOMAIN_2_GIB, 4096);
    assertThat(patched).contains("<currentMemory unit=\"KiB\">4194304</currentMemory>");
  }

  @Test
  void withMemory_roundTripsThroughGetShortState() {
    DomainState state = XmlUtil.getShortState(XmlUtil.withMemory(DOMAIN_2_GIB, 4096));
    assertThat(state.ram()).isEqualTo(4096);
    assertThat(state.cpu()).isEqualTo(2);
  }

  @Test
  void withMemory_currentMemoryAbsent_stillPatchesMemory() {
    String noBalloon = DOMAIN_2_GIB.replaceAll("(?m)^.*currentMemory.*\\R", "");
    String patched = XmlUtil.withMemory(noBalloon, 8192);
    assertThat(XmlUtil.getShortState(patched).ram()).isEqualTo(8192);
    assertThat(patched).doesNotContain("currentMemory");
  }

  @Test
  void withMemory_memoryAbsent_throws() {
    String noMemory = DOMAIN_2_GIB.replaceAll("(?m)^.*<memory.*\\R", "");
    assertThatThrownBy(() -> XmlUtil.withMemory(noMemory, 4096))
        .isInstanceOf(XmlParseException.class)
        .hasMessageContaining("<memory>");
  }

  @Test
  void withMemory_staleMibUnit_isRewrittenToKib() {
    String stale = DOMAIN_2_GIB.replace("unit=\"KiB\">2097152", "unit=\"MiB\">2048");
    String patched = XmlUtil.withMemory(stale, 4096);
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
