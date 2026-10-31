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
    assertThat(TargetDev.prefix(null)).isEqualTo("vd");
    assertThat(TargetDev.prefix("  ")).isEqualTo("vd");
    assertThat(TargetDev.prefix("z")).isEqualTo("vd");
    assertThat(TargetDev.prefix("zza")).isEqualTo("zz");
  }

  @Test
  void allocate_takesTheNextFreeNamesInOrder() {
    assertThat(TargetDev.allocate("vd", Set.of("vda"), 3)).containsExactly("vdb", "vdc", "vdd");
  }

  @Test
  void allocate_neverHandsOutANameSomethingElseAlreadyUses() {
    List<String> free = TargetDev.allocate("vd", Set.of("vda", "vdb", "vdd"), 3);
    assertThat(free).containsExactly("vdc", "vde", "vdf");
  }

  @Test
  void allocate_lettersExhausted_returnsFewerRatherThanReusingOne() {
    Set<String> used =
        "abcdefghijklmnopqrstuvwxyz"
            .chars()
            .mapToObj(c -> "vd" + (char) c)
            .collect(java.util.stream.Collectors.toSet());
    List<String> free = TargetDev.allocate("vd", used, 2);
    assertThat(free).isEmpty();
  }
}
