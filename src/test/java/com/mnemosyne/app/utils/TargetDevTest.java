package com.mnemosyne.app.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TargetDev")
public class TargetDevTest {

  @Test
  void prefix_readsTheBusFromAnExistingTargetName() {
    assertThat(TargetDev.prefix("vda")).isEqualTo("vd");
    assertThat(TargetDev.prefix("sda")).isEqualTo("sd");
    assertThat(TargetDev.prefix("hdb")).isEqualTo("hd");
    assertThat(TargetDev.prefix("xvda")).isEqualTo("xvd");
  }

  @Test
  void prefix_unknownOrMissingName_fallsBackToVirtio() {
    // A template may leave <target dev> out entirely; virtio is what the shipped one uses.
    assertThat(TargetDev.prefix(null)).isEqualTo("vd");
    assertThat(TargetDev.prefix("  ")).isEqualTo("vd");
    assertThat(TargetDev.prefix("z")).isEqualTo("vd");
    assertThat(TargetDev.prefix("zza")).isEqualTo("zz");
  }

  @Test
  void allocate_takesTheNextFreeNamesInOrder() {
    // Act & Assert
    assertThat(TargetDev.allocate("vd", Set.of("vda"), 3)).containsExactly("vdb", "vdc", "vdd");
  }

  @Test
  void allocate_neverHandsOutANameSomethingElseAlreadyUses() {
    // The whole point of computing targets instead of letting libvirt do it: a disk the operator
    // attached by hand must not be overwritten by one of ours.
    // Act
    List<String> free = TargetDev.allocate("vd", Set.of("vda", "vdb", "vdd"), 3);
    // Assert
    assertThat(free).containsExactly("vdc", "vde", "vdf");
  }

  @Test
  void allocate_lettersExhausted_returnsFewerRatherThanReusingOne() {
    // Arrange: every vd* name is taken
    Set<String> used =
        "abcdefghijklmnopqrstuvwxyz"
            .chars()
            .mapToObj(c -> "vd" + (char) c)
            .collect(java.util.stream.Collectors.toSet());
    // Act
    List<String> free = TargetDev.allocate("vd", used, 2);
    // Assert
    assertThat(free).isEmpty();
  }
}
