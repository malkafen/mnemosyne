package com.mnemosyne.app.utils;


import static org.assertj.core.api.Assertions.assertThat;

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
}
