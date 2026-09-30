package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Hypervisor libvirt URI")
class HypervisorUriTest {

  @Test
  void hostKeysAreVerifiedByDefault() {
    assertThat(Hypervisor.uri("virtops", "/home/virtops/.ssh/id_ed25519", "192.0.2.10", 22, false))
        .isEqualTo(
            "qemu+ssh://virtops@192.0.2.10:22/system"
                + "?keyfile=/home/virtops/.ssh/id_ed25519&no_tty=1")
        .doesNotContain("no_verify");
  }

  @Test
  void skippingTheHostKeyCheckIsExplicit() {
    assertThat(Hypervisor.uri("virtops", "/k", "192.0.2.10", 2222, true))
        .isEqualTo("qemu+ssh://virtops@192.0.2.10:2222/system?keyfile=/k&no_verify=1&no_tty=1");
  }

  @Test
  void theKeyPathCannotAddParameters() {
    String uri = Hypervisor.uri("virtops", "/k&command=/tmp/pwn.sh#x ?y", "hv01", 22, false);
    assertThat(uri)
        .isEqualTo(
            "qemu+ssh://virtops@hv01:22/system"
                + "?keyfile=/k%26command%3D/tmp/pwn.sh%23x%20%3Fy&no_tty=1")
        .doesNotContain("&command=");
  }

  @Test
  void nonAsciiInTheKeyPathIsEncodedAsUtf8() {
    assertThat(Hypervisor.uri("virtops", "/ключ", "hv01", 22, false))
        .contains("keyfile=/%D0%BA%D0%BB%D1%8E%D1%87&");
  }
}
