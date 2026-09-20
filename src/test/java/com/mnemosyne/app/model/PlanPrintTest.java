package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the operator actually reads. Notes only protect anybody if they reach the screen, so the
 * printed plan is asserted rather than only the model behind it.
 */
@DisplayName("Plan.print(), disks")
public class PlanPrintTest {

  private static final String IMAGES = "/var/lib/libvirt/images/";

  private static ExtraDisk disk(String name, int size, String pool) {
    ExtraDisk d = new ExtraDisk();
    d.setName(name);
    d.setSize(size);
    d.setPool(pool);
    return d;
  }

  private static Server server(String id, ExtraDisk... extras) {
    Server s = new Server();
    s.setId(id);
    s.setCpu(2);
    s.setRam(2048);
    s.setExtraDisks(List.of(extras));
    return s;
  }

  private static DomainState managed(String id, DomainState.Disk... disks) {
    return new DomainState(id, 2, 2048, id, "1", "mnemosyne", List.of(disks), true, false);
  }

  private static String print(Plan plan) {
    return print(plan, new Preflight());
  }

  private static String print(Plan plan, Preflight preflight) {
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
      plan.print("hv01", false, preflight);
    } finally {
      System.setOut(original);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  @Test
  void aNewVmListsTheDisksItWillGet() {
    // Arrange
    Server s = server("web-01", disk("data", 40, "default"), disk("logs", 50, "nvme"));
    Plan plan = new Plan(List.of(), Map.of("web-01", s), false);
    // Act
    String out = print(plan);
    // Assert
    assertThat(out)
        .contains("create: 1")
        .contains("+ web-01")
        .contains("web-01-data.qcow2 40G in pool 'default'")
        .contains("web-01-logs.qcow2 50G in pool 'nvme'");
  }

  @Test
  void anAttachIsPrintedOnTheUpdateLineWithTheTargetItWillGet() {
    // Arrange
    Plan plan =
        new Plan(
            List.of(managed("web-01", new DomainState.Disk("vda", IMAGES + "web-01.qcow2", null))),
            Map.of("web-01", server("web-01", disk("data", 40, "default"))),
            false);
    // Act
    String out = print(plan);
    // Assert
    assertThat(out).contains("update: 1").contains("~ web-01  (attach 'data' 40G as vdb)");
  }

  @Test
  void aDiskSmallerThanTheInventoryIsPrintedAsAChange_notAsANote() {
    // The inventory says 50 and the disk is 40, so the run will grow it. It is a change, and it
    // has to be on the update line before the confirmation window rather than under it.
    // Arrange
    Plan plan =
        new Plan(
            List.of(
                managed(
                    "web-01",
                    new DomainState.Disk("vda", IMAGES + "web-01.qcow2", null, 30),
                    new DomainState.Disk("vdb", IMAGES + "web-01-data.qcow2", "data", 40))),
            Map.of("web-01", server("web-01", disk("data", 50, "default"))),
            false);
    // Act
    String out = print(plan);
    // Assert
    assertThat(out)
        .contains("update: 1")
        .doesNotContain("note: 1")
        .contains("~ web-01  (grow disk 'data' 40G->50G)");
  }

  @Test
  void aDiskBiggerThanTheInventoryBlocksTheVm_andIsNeverShrunk() {
    // The host has 50G, the inventory asks for 40G. Shrinking would throw away whatever lives past
    // the 40G mark, so the run stops and the operator fixes the inventory instead.
    // Arrange
    Plan plan =
        new Plan(
            List.of(
                managed(
                    "web-01",
                    new DomainState.Disk("vda", IMAGES + "web-01.qcow2", null, 30),
                    new DomainState.Disk("vdb", IMAGES + "web-01-data.qcow2", "data", 50))),
            Map.of("web-01", server("web-01", disk("data", 40, "default"))),
            false);
    // The shrink is a finding of the plan; preflight is what turns it into a refusal.
    Preflight preflight = new Preflight();
    preflight.add(new Preflight.Problem("disk 'data' of 'web-01'", "never shrunk"), "web-01");
    // Act
    String out = print(plan, preflight);
    // Assert
    assertThat(plan.getShrinks()).containsKey("web-01");
    assertThat(plan.getToUpdate()).doesNotContainKey("web-01");
    assertThat(out).contains("blocked: 1").contains("! web-01").contains("never shrunk");
  }

  @Test
  void aDiskTheInventoryDoesNotMentionIsPrintedButNeverAsADeletion() {
    // Arrange
    Plan plan =
        new Plan(
            List.of(
                managed(
                    "web-01",
                    new DomainState.Disk("vda", IMAGES + "web-01.qcow2", null),
                    new DomainState.Disk("vdb", IMAGES + "scratch.qcow2", null))),
            Map.of("web-01", server("web-01")),
            false);
    // Act
    String out = print(plan);
    // Assert
    assertThat(out)
        .contains("disk 'vdb' (scratch.qcow2) is not in the inventory - left as is")
        .doesNotContain("delete");
  }

  @Test
  void aDeletedVmNamesEveryVolumeThatGoesWithIt() {
    // The contract is that a VM leaving the inventory takes its data disks along, so the plan has
    // to name them all before the confirmation window, not after.
    // Arrange
    Plan plan =
        new Plan(
            List.of(
                managed(
                    "old-db",
                    new DomainState.Disk("vda", IMAGES + "old-db.qcow2", null),
                    new DomainState.Disk("vdb", IMAGES + "old-db-data.qcow2", "data"))),
            Map.of(),
            false);
    // Act
    String out = print(plan);
    // Assert
    assertThat(out)
        .contains("delete: 1")
        .contains(IMAGES + "old-db.qcow2")
        .contains(IMAGES + "old-db-data.qcow2");
  }

  @Test
  void anAttachAGrowAndANoteOnTheSameVmAppearTogether() {
    // One VM with all three disk outcomes at once: a disk to add, a disk to grow, and a disk
    // nobody claims. They must not crowd each other out of the entry.
    // Arrange
    Plan plan =
        new Plan(
            List.of(
                managed(
                    "web-01",
                    new DomainState.Disk("vda", IMAGES + "web-01.qcow2", null, 30),
                    new DomainState.Disk("vdb", IMAGES + "web-01-data.qcow2", "data", 30),
                    new DomainState.Disk("vdc", IMAGES + "scratch.qcow2", null, 10))),
            Map.of(
                "web-01", server("web-01", disk("data", 40, "default"), disk("logs", 50, "nvme"))),
            false);
    // Act
    String out = print(plan);
    // Assert
    assertThat(out)
        .contains("update: 1")
        .doesNotContain("note: 1")
        .contains("attach 'logs' 50G as vdd")
        .contains("grow disk 'data' 30G->40G")
        .contains("disk 'vdc' (scratch.qcow2) is not in the inventory - left as is");
  }

  @Test
  void nothingToDoAndNothingToSay_isStillNoChanges() {
    // Arrange
    Plan plan =
        new Plan(
            List.of(
                managed(
                    "web-01",
                    new DomainState.Disk("vda", IMAGES + "web-01.qcow2", null),
                    new DomainState.Disk("vdb", IMAGES + "web-01-data.qcow2", "data"))),
            Map.of("web-01", server("web-01", disk("data", 40, "default"))),
            false);
    // Act
    String out = print(plan);
    // Assert
    assertThat(out).contains("no changes");
  }
}
