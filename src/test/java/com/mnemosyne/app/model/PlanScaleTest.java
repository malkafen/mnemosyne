package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The plan over a group far larger than any hand-written fixture: every managed domain must be
 * accounted for exactly once, and the same inputs must always produce the same plan.
 *
 * <p>The size is the point. {@link Plan} builds {@code toUpdate} and {@code notes} by mapping a
 * pure function over the managed domains, which is what lets that pipeline be switched to a
 * parallel stream later. Flip it, and this is the test that says whether it still holds: below a
 * few hundred elements a parallel stream usually runs on the calling thread and proves nothing,
 * while at this size it really does fork. Run against a version that records notes on the side
 * instead of returning them, it fails.
 */
@DisplayName("Plan at scale")
public class PlanScaleTest {

  private static final int DOMAINS = 2000;

  /**
   * Even-numbered VMs already have their data disk, odd ones still need it, and every third VM
   * carries a disk the inventory knows nothing about.
   */
  private static Plan build() {
    Map<String, Server> servers = new HashMap<>();
    List<DomainState> actual = new ArrayList<>(DOMAINS);

    for (int i = 0; i < DOMAINS; i++) {
      String id = "vm-" + i;

      ExtraDisk disk = new ExtraDisk();
      disk.setName("data");
      disk.setSize(40);
      disk.setPool("default");

      Server s = new Server();
      s.setId(id);
      s.setCpu(2);
      s.setRam(2048);
      s.setExtraDisks(List.of(disk));
      servers.put(id, s);

      List<DomainState.Disk> disks = new ArrayList<>();
      disks.add(new DomainState.Disk("vda", "/img/" + id + ".qcow2", null));
      if (i % 2 == 0) disks.add(new DomainState.Disk("vdb", "/img/" + id + "-data.qcow2", "data"));
      if (i % 3 == 0)
        disks.add(new DomainState.Disk("vdz", "/img/" + id + "-stranger.qcow2", null));

      actual.add(
          new DomainState(id, 2, 2048, id, "1", "mnemosyne", List.copyOf(disks), true, false));
    }
    return new Plan(actual, servers, false);
  }

  @Test
  void everyDomainIsAccountedForExactlyOnce() {
    // Arrange & Act
    Plan plan = build();
    // Assert
    long expectedAttaches = IntStream.range(0, DOMAINS).filter(i -> i % 2 != 0).count();
    long expectedNotes = IntStream.range(0, DOMAINS).filter(i -> i % 3 == 0).count();

    assertThat(plan.getToUpdate()).hasSize((int) expectedAttaches);
    assertThat(plan.getNotes()).hasSize((int) expectedNotes);
    assertThat(plan.getToCreate()).isEmpty();
    assertThat(plan.getToDelete()).isEmpty();
    assertThat(plan.getUnmanaged()).isEmpty();
  }

  @Test
  void theSameInputsAlwaysProduceTheSamePlan() {
    // Nothing may be dropped, duplicated or reordered between runs.
    // Arrange
    Plan first = build();
    // Act & Assert
    for (int run = 0; run < 20; run++) {
      Plan again = build();
      assertThat(again.getToUpdate().keySet()).isEqualTo(first.getToUpdate().keySet());
      assertThat(again.getNotes()).isEqualTo(first.getNotes());
    }
  }
}
