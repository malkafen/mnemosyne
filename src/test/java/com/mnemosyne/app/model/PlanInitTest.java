package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the plan does with a VM's {@code <mnem:init>}: a finished or adopted VM is diffed as always,
 * a pending one is shown and left alone, and one whose state cannot be told is refused.
 */
@DisplayName("Plan, init marker")
public class PlanInitTest {

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

  private static Server server(int cpu) {
    Server s = new Server();
    s.setId("web-01");
    s.setCpu(cpu);
    s.setRam(2048);
    return s;
  }

  private static DomainState domain(InitMarker init) {
    return new DomainState(
        "web-01",
        2,
        2048,
        "web-01",
        "1",
        "mnemosyne",
        List.of(new DomainState.Disk("vda", "/img/web-01.qcow2", null).markOwned()),
        false,
        false,
        List.of(),
        List.of(),
        init);
  }

  private static Plan plan(int cpu, InitMarker init) {
    return new Plan(List.of(domain(init)), Map.of("web-01", server(cpu)), false);
  }

  @Test
  void finishedAndAdopted_areDiffedAsAlways() {
    // Act & Assert
    for (String state : List.of("finished", "adopted")) {
      Plan result = plan(4, new InitMarker(state, null, null, null));
      assertThat(result.getToUpdate()).containsKey("web-01");
      assertThat(result.getPendingInit()).isEmpty();
      assertThat(result.getUnknownInit()).isEmpty();
    }
  }

  @Test
  void pending_isNeverUpdated_evenWithDrift_andIsShown() {
    // Arrange
    InitMarker pending = new InitMarker("pending", "t", "2026-09-25T10:00:00Z", null);
    // Act
    Plan result = plan(4, pending);
    result.print("bm05", false, new Preflight());
    // Assert
    assertThat(result.getToUpdate()).isEmpty();
    assertThat(result.getToCreate()).isEmpty();
    assertThat(result.getPendingInit()).containsOnlyKeys("web-01");
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("pending: 1")
        .contains(
            "! web-01  (init pending since 2026-09-25T10:00:00Z: cloud-init never confirmed it"
                + " finished - left as is)")
        .doesNotContain("update");
  }

  @Test
  void noMarker_isRefused_notGuessed() {
    // Act
    Plan result = plan(2, null);
    // Assert
    assertThat(result.getUnknownInit())
        .containsEntry(
            "web-01", "has no <mnem:init>, so whether it was ever initialized cannot be told");
    assertThat(result.getPendingInit()).isEmpty();
  }

  @Test
  void anUnknownState_isRefusedByName() {
    // Act
    Plan result = plan(2, new InitMarker("done", null, null, null));
    // Assert
    assertThat(result.getUnknownInit())
        .containsEntry("web-01", "has an unknown init state 'done' in <mnem:init>");
  }

  @Test
  void aRefusedVmWithNoDrift_isStillPrintedAsBlocked() {
    // Arrange
    Plan result = plan(2, null);
    Preflight preflight = new Preflight();
    preflight.add(new Preflight.Problem("domain of 'web-01'", "unknown init state"), "web-01");
    // Act
    result.print("bm05", false, preflight);
    // Assert
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("blocked: 1").contains("web-01");
  }

  @Test
  void aVmThatLeftTheInventory_isDeletedWhateverItsMarker() {
    // Act
    Plan result = new Plan(List.of(domain(null)), Map.of(), false);
    // Assert
    assertThat(result.getToDelete()).containsKey("web-01");
    assertThat(result.getUnknownInit()).isEmpty();
  }
}
