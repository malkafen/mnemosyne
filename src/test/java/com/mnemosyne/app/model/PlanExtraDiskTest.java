package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the plan reads the disks of a managed domain.
 *
 * <p>The rule under test throughout: disks are only ever added. Nothing in this class may produce a
 * detach, a resize or a volume deletion, whatever the inventory says.
 */
@DisplayName("Plan, extra disks")
public class PlanExtraDiskTest {

  private static final String IMAGES = "/var/lib/libvirt/images/";

  private static ExtraDisk disk(String name, int size) {
    ExtraDisk d = new ExtraDisk();
    d.setName(name);
    d.setSize(size);
    d.setPool("default");
    return d;
  }

  private static Server server(ExtraDisk... extras) {
    Server s = new Server();
    s.setId("web-01");
    s.setCpu(2);
    s.setRam(2048);
    s.setExtraDisks(List.of(extras));
    return s;
  }

  private static DomainState domain(boolean active, DomainState.Disk... disks) {
    return new DomainState(
        "web-01", 2, 2048, "web-01", "1", "mnemosyne", List.of(disks), active, false);
  }

  private static DomainState.Disk root() {
    return new DomainState.Disk("vda", IMAGES + "web-01.qcow2", null);
  }

  private static DomainState.Disk attached(String target, String name) {
    return new DomainState.Disk(target, IMAGES + "web-01-" + name + ".qcow2", name);
  }

  private static Plan plan(Server s, DomainState d) {
    return new Plan(List.of(d), Map.of("web-01", s), false);
  }

  @Test
  void extraDiskMissing_landsInToUpdateAsAnAttach() {
    // Act
    Plan result = plan(server(disk("data", 40)), domain(true, root()));
    // Assert
    Plan.Update update = result.getToUpdate().get("web-01");
    assertThat(update).isNotNull();
    assertThat(update.toAttach()).hasSize(1);
    assertThat(update.toAttach().get(0).target()).isEqualTo("vdb");
    assertThat(update.diff()).isEqualTo("attach 'data' 40G as vdb");
  }

  @Test
  void extraDiskAlreadyAttached_isNotPlannedAgain() {
    // Matching is on the volume file name, which the inventory can predict from the VM's name.
    // Act
    Plan result = plan(server(disk("data", 40)), domain(true, root(), attached("vdb", "data")));
    // Assert
    assertThat(result.getToUpdate()).isEmpty();
    assertThat(result.getNotes()).isEmpty();
  }

  @Test
  void someDisksAttached_onlyTheMissingOnesArePlanned_afterTheUsedLetters() {
    // Act
    Plan result =
        plan(
            server(disk("data", 40), disk("logs", 50), disk("cache", 10)),
            domain(true, root(), attached("vdb", "data")));
    // Assert
    Plan.Update update = result.getToUpdate().get("web-01");
    assertThat(update.toAttach())
        .extracting(a -> a.disk().getName())
        .containsExactly("logs", "cache");
    assertThat(update.toAttach()).extracting(Plan.DiskAttach::target).containsExactly("vdc", "vdd");
  }

  @Test
  void aDiskTheInventoryDoesNotMention_isReportedAndNeverTouched() {
    // Somebody attached it by hand, or the entry was removed from the inventory. Either way it
    // is not ours to detach, and its volume is not ours to delete.
    // Act
    Plan result =
        plan(
            server(),
            domain(true, root(), new DomainState.Disk("vdb", IMAGES + "scratch.qcow2", null)));
    // Assert
    assertThat(result.getToUpdate()).isEmpty();
    assertThat(result.getToDelete()).isEmpty();
    assertThat(result.getNotes().get("web-01"))
        .containsExactly("disk 'vdb' (scratch.qcow2) is not in the inventory - left as is");
  }

  @Test
  void removingAnExtraDiskFromTheInventory_deletesNothing() {
    // The entry is gone from the YAML but the disk is still on the domain. This is the case that
    // must never turn into a deletion: the volume holds the only copy of whatever is on it.
    // Arrange: the inventory now lists only 'data', the domain also has 'logs'
    Server s = server(disk("data", 40));
    DomainState d = domain(true, root(), attached("vdb", "data"), attached("vdc", "logs"));
    // Act
    Plan result = plan(s, d);
    // Assert
    assertThat(result.getToDelete()).isEmpty();
    assertThat(result.getToUpdate()).isEmpty();
    assertThat(result.getNotes().get("web-01"))
        .containsExactly("disk 'vdc' (web-01-logs.qcow2) is not in the inventory - left as is");
  }

  @Test
  void renamingTheVm_doesNotHandItASecondEmptySetOfDisks() {
    // The reason disks are matched on their serial. A VM renamed through `name:` keeps its id and
    // its domain, so nothing is recreated — but the volume name the inventory would predict has
    // changed. Matching on the file name alone would report every data disk as missing and attach
    // brand-new empty ones next to the originals, which still hold the data.
    // Arrange: id 'web-01', renamed to 'web-01.prod.lan'; the disk is still web-01-data.qcow2
    Server s = server(disk("data", 40));
    s.setName("web-01.prod.lan");
    DomainState d =
        new DomainState(
            "web-01.prod.lan",
            2,
            2048,
            "web-01",
            "1",
            "mnemosyne",
            List.of(root(), new DomainState.Disk("vdb", IMAGES + "web-01-data.qcow2", "data")),
            true,
            false);
    // Act
    Plan result = new Plan(List.of(d), Map.of("web-01", s), false);
    // Assert
    assertThat(result.getToUpdate()).isEmpty();
    assertThat(result.getNotes()).isEmpty();
  }

  @Test
  void aDiskWithNoSerialIsStillMatchedByItsFileName() {
    // The fallback, for a disk attached before serials were written or by hand.
    // Arrange
    DomainState d =
        new DomainState(
            "web-01",
            2,
            2048,
            "web-01",
            "1",
            "mnemosyne",
            List.of(root(), new DomainState.Disk("vdb", IMAGES + "web-01-data.qcow2", null)),
            true,
            false);
    // Act
    Plan result = new Plan(List.of(d), Map.of("web-01", server(disk("data", 40))), false);
    // Assert
    assertThat(result.getToUpdate()).isEmpty();
    assertThat(result.getNotes()).isEmpty();
  }

  @Test
  void aDomainWithOnlyNotesIsNotAnUpdate() {
    // Nothing will be applied to it, so it must not be counted as a change.
    // Act
    Plan result =
        plan(server(), domain(true, root(), new DomainState.Disk("vdb", IMAGES + "x.qcow2", null)));
    // Assert
    assertThat(result.getToUpdate()).isEmpty();
    assertThat(result.getNotes()).containsOnlyKeys("web-01");
  }

  @Test
  void theFirstDiskIsNeverReportedAsUnknown_soAdoptedVmsAreNotNoisy() {
    // A domain taken over with --join has a root volume named however its creator named it.
    // Act
    Plan result =
        plan(server(), domain(true, new DomainState.Disk("vda", IMAGES + "anything-at-all", null)));
    // Assert
    assertThat(result.getNotes()).isEmpty();
  }

  @Test
  void noFreeTargetName_reportsTheDiskInsteadOfReusingALetter() {
    // Arrange: every vd* name on the domain is taken
    DomainState.Disk[] disks = new DomainState.Disk[26];
    for (int i = 0; i < 26; i++)
      disks[i] = new DomainState.Disk("vd" + (char) ('a' + i), IMAGES + "d" + i + ".qcow2", null);
    // Act
    Plan result = plan(server(disk("data", 40)), domain(true, disks));
    // Assert
    assertThat(result.getToUpdate()).isEmpty();
    assertThat(result.getNotes().get("web-01"))
        .contains("disk 'data': no free target device name on this domain - not attached");
  }

  @Test
  void deletingAVmTakesEveryOneOfItsVolumesWithIt_andThePlanListsThem() {
    // The agreed contract: a VM that leaves the inventory takes its data disks with it. What
    // matters is that the plan names every volume first, so nothing disappears unannounced.
    // Arrange
    DomainState d =
        new DomainState(
            "old-db",
            2,
            2048,
            "old-db",
            "1",
            "mnemosyne",
            List.of(
                new DomainState.Disk("vda", IMAGES + "old-db.qcow2", null).markOwned(),
                new DomainState.Disk("vdb", IMAGES + "old-db-data.qcow2", "data").markOwned()),
            false,
            false);
    // Act
    Plan result = new Plan(List.of(d), Map.of(), false);
    // Assert
    assertThat(result.getToDelete().get("old-db"))
        .containsExactly(IMAGES + "old-db.qcow2", IMAGES + "old-db-data.qcow2");
  }

  @Test
  void deletingAVmLeavesVolumesItDidNotCreate_andOnesAnotherDomainUses() {
    // Arrange
    DomainState d =
        new DomainState(
            "old-db",
            2,
            2048,
            "old-db",
            "1",
            "mnemosyne",
            List.of(
                new DomainState.Disk("vda", IMAGES + "old-db.qcow2", null).markOwned(),
                new DomainState.Disk("vdb", IMAGES + "handmade.qcow2", null),
                new DomainState.Disk("vdc", IMAGES + "shared.raw", null).markOwned()),
            false,
            false);
    DomainState other =
        new DomainState(
            "legacy",
            1,
            1024,
            null,
            null,
            null,
            List.of(new DomainState.Disk("vda", IMAGES + "shared.raw", null)),
            false,
            false);
    // Act
    Plan result = new Plan(List.of(d, other), Map.of(), false);
    // Assert
    assertThat(result.getToDelete().get("old-db")).containsExactly(IMAGES + "old-db.qcow2");
    assertThat(result.getKept().get("old-db"))
        .containsExactly(
            IMAGES
                + "handmade.qcow2"
                + " - not created by mnemosyne, left as is (--purge-disks deletes it)",
            IMAGES + "shared.raw - also attached to 'legacy', left as is");
  }

  @Test
  void purgeDisks_takesEveryVolume_butStillNotOneAnotherDomainUses() {
    // Arrange: an adopted VM, nothing recorded as created by Mnemosyne
    DomainState d =
        new DomainState(
            "legacy-01",
            1,
            1024,
            "legacy-01",
            "1",
            "mnemosyne",
            List.of(
                new DomainState.Disk("vda", IMAGES + "legacy-01-disk0.img", null),
                new DomainState.Disk("vdb", IMAGES + "shared.raw", null)),
            false,
            false);
    DomainState other =
        new DomainState(
            "legacy-02",
            1,
            1024,
            null,
            null,
            null,
            List.of(new DomainState.Disk("vda", IMAGES + "shared.raw", null)),
            false,
            false);
    // Act
    Plan result = new Plan(List.of(d, other), Map.of(), false, true);
    // Assert
    assertThat(result.getToDelete().get("legacy-01"))
        .containsExactly(IMAGES + "legacy-01-disk0.img");
    assertThat(result.getKept().get("legacy-01"))
        .containsExactly(IMAGES + "shared.raw - also attached to 'legacy-02', left as is");
  }

  @Test
  void noDeleteFlag_keepsDataDisksToo() {
    // --no-delete is the escape hatch for exactly this fear.
    // Arrange
    DomainState d =
        new DomainState(
            "old-db",
            2,
            2048,
            "old-db",
            "1",
            "mnemosyne",
            List.of(new DomainState.Disk("vdb", IMAGES + "old-db-data.qcow2", "data")),
            false,
            false);
    // Act
    Plan result = new Plan(List.of(d), Map.of(), true);
    // Assert
    assertThat(result.getToDelete()).isEmpty();
  }

  @Test
  void diffCombinesADiskAttachWithTheOtherDrift() {
    // Arrange
    Server s = server(disk("data", 40));
    s.setRam(4096);
    // Act
    Plan result = plan(s, domain(true, root()));
    // Assert
    assertThat(result.getToUpdate().get("web-01").diff())
        .isEqualTo("ram 2048->4096, attach 'data' 40G as vdb");
  }

  @Test
  void anUnmanagedDomainIsNeverDiffedForDisks() {
    // Arrange
    DomainState unmanaged =
        new DomainState(
            "web-01",
            2,
            2048,
            null,
            null,
            null,
            List.of(root(), attached("vdb", "x")),
            true,
            false);
    // Act
    Plan result = new Plan(List.of(unmanaged), Map.of("web-01", server(disk("data", 40))), false);
    // Assert
    assertThat(result.getToUpdate()).isEmpty();
    assertThat(result.getNotes()).isEmpty();
    assertThat(result.getToAdopt()).containsOnlyKeys("web-01");
  }
}
