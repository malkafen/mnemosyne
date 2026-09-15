package com.mnemosyne.app.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mnemosyne.app.exception.XmlParseException;
import com.mnemosyne.app.model.DomainState;
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
}
