package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Plan, disk sizes")
public class PlanGrowTest {

  private static final String IMAGES = "/var/lib/libvirt/images/";

  private static ExtraDisk disk(String name, int size) {
    ExtraDisk d = new ExtraDisk();
    d.setName(name);
    d.setSize(size);
    d.setPool("default");
    return d;
  }

  private static Server server(String id, int rootGiB, ExtraDisk... extras) {
    Server s = new Server();
    s.setId(id);
    s.setName(id);
    s.setCpu(2);
    s.setRam(2048);
    s.setDisk(rootGiB);
    s.setExtraDisks(List.of(extras));
    return s;
  }

  private static DomainState domain(String id, String managedBy, DomainState.Disk... disks) {
    return new DomainState(id, 2, 2048, id, "1", managedBy, List.of(disks), true, false);
  }

  private static DomainState managed(String id, DomainState.Disk... disks) {
    return domain(id, "mnemosyne", disks);
  }

  private static DomainState.Disk root(String vm, long capacityGiB) {
    return new DomainState.Disk("vda", IMAGES + vm + ".qcow2", null, capacityGiB);
  }

  private static Plan plan(DomainState d, Server s) {
    return new Plan(List.of(d), Map.of(s.getId(), s), false);
  }

  @Test
  void theRootDiskGrowsWhenTheInventoryAsksForMore() {
    Server s = server("web-01", 40);
    Plan plan = plan(managed("web-01", root("web-01", 25)), s);
    Plan.Update u = plan.getToUpdate().get("web-01");
    assertThat(u).isNotNull();
    assertThat(u.growChanged()).isTrue();
    assertThat(u.toGrow())
        .singleElement()
        .satisfies(
            g -> {
              assertThat(g.label()).isEqualTo("root disk");
              assertThat(g.fromGiB()).isEqualTo(25);
              assertThat(g.toGiB()).isEqualTo(40);
              assertThat(g.target()).isEqualTo("vda");
              assertThat(g.path()).isEqualTo(IMAGES + "web-01.qcow2");
            });
    assertThat(u.diff()).contains("grow root disk 25G->40G");
  }

  @Test
  void anExtraDiskGrowsWhenTheInventoryAsksForMore() {
    Server s = server("web-01", 25, disk("data", 50));
    DomainState d =
        managed(
            "web-01",
            root("web-01", 25),
            new DomainState.Disk("vdb", IMAGES + "web-01-data.qcow2", "data", 40));
    Plan plan = plan(d, s);
    assertThat(plan.getToUpdate().get("web-01").toGrow())
        .singleElement()
        .satisfies(
            g -> {
              assertThat(g.label()).isEqualTo("disk 'data'");
              assertThat(g.fromGiB()).isEqualTo(40);
              assertThat(g.toGiB()).isEqualTo(50);
            });
  }

  @Test
  void sizesThatAlreadyMatchAreNotAChange() {
    Server s = server("web-01", 25, disk("data", 40));
    DomainState d =
        managed(
            "web-01",
            root("web-01", 25),
            new DomainState.Disk("vdb", IMAGES + "web-01-data.qcow2", "data", 40));
    Plan plan = plan(d, s);
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getShrinks()).isEmpty();
  }

  @Test
  void aDiskBiggerThanTheInventoryIsRecordedAsAShrink_andIsNotWork() {
    Server s = server("web-01", 25, disk("data", 40));
    DomainState d =
        managed(
            "web-01",
            root("web-01", 25),
            new DomainState.Disk("vdb", IMAGES + "web-01-data.qcow2", "data", 50));
    Plan plan = plan(d, s);
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getShrinks().get("web-01"))
        .singleElement()
        .satisfies(
            sh -> {
              assertThat(sh.label()).isEqualTo("disk 'data'");
              assertThat(sh.actualGiB()).isEqualTo(50);
              assertThat(sh.wantedGiB()).isEqualTo(40);
            });
  }

  @Test
  void aShrinkOnOneDiskDoesNotCancelAGrowOnAnother() {
    Server s = server("web-01", 40, disk("data", 10));
    DomainState d =
        managed(
            "web-01",
            root("web-01", 25),
            new DomainState.Disk("vdb", IMAGES + "web-01-data.qcow2", "data", 20));
    Plan plan = plan(d, s);
    assertThat(plan.getToUpdate().get("web-01").toGrow())
        .singleElement()
        .satisfies(g -> assertThat(g.label()).isEqualTo("root disk"));
    assertThat(plan.getShrinks().get("web-01"))
        .singleElement()
        .satisfies(sh -> assertThat(sh.label()).isEqualTo("disk 'data'"));
  }

  @Test
  void aDiskWhoseSizeCouldNotBeReadIsLeftAlone() {
    Server s = server("web-01", 40);
    DomainState d = managed("web-01", new DomainState.Disk("vda", IMAGES + "web-01.qcow2", null));
    Plan plan = plan(d, s);
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getShrinks()).isEmpty();
  }

  @Test
  void aDiskTheInventoryListsButTheDomainDoesNotHaveIsAnAttach_notAGrow() {
    Server s = server("web-01", 25, disk("data", 40));
    Plan plan = plan(managed("web-01", root("web-01", 25)), s);
    Plan.Update u = plan.getToUpdate().get("web-01");
    assertThat(u.disksChanged()).isTrue();
    assertThat(u.growChanged()).isFalse();
  }

  @Test
  void aRenamedVmIsMeasuredOnTheDiskItHas_notOnTheNameItWouldGetToday() {
    Server s = server("web-01", 25, disk("data", 50));
    s.setName("web-01-renamed");
    DomainState d =
        managed(
            "web-01",
            root("web-01", 25),
            new DomainState.Disk("vdb", IMAGES + "web-01-data.qcow2", "data", 40));
    Plan plan = plan(d, s);
    assertThat(plan.getToUpdate().get("web-01").toGrow())
        .singleElement()
        .satisfies(g -> assertThat(g.path()).isEqualTo(IMAGES + "web-01-data.qcow2"));
  }

  @Test
  void anUnmanagedDomainIsNeverGrown() {
    Server s = server("web-01", 40);
    DomainState d = domain("web-01", null, root("web-01", 25));
    Plan plan = new Plan(List.of(d), Map.of("web-01", s), false);
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getShrinks()).isEmpty();
    assertThat(plan.getUnmanaged()).containsExactly("web-01");
  }

  @Test
  void aDiskTheInventoryDoesNotMentionIsNeverGrown() {
    Server s = server("web-01", 25);
    DomainState d =
        managed(
            "web-01",
            root("web-01", 25),
            new DomainState.Disk("vdb", IMAGES + "scratch.qcow2", null, 5));
    Plan plan = plan(d, s);
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getShrinks()).isEmpty();
    assertThat(plan.getNotes().get("web-01"))
        .singleElement()
        .asString()
        .contains("scratch.qcow2")
        .contains("left as is");
  }
}
