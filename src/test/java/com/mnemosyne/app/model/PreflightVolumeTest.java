package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The checks that stand between a mistyped name and two domains writing into one disk image.
 *
 * <p>Sharing a qcow2 between two running domains corrupts it, and the corruption happens before
 * anything reports an error, so this is the one disk condition that blocks a run outright.
 */
@DisplayName("Preflight, volume names")
public class PreflightVolumeTest {

  private static final Map<String, String> POOLS =
      Map.of("default", "/var/lib/libvirt/images", "nvme", "/mnt/nvme/images");

  private static ExtraDisk disk(String name, String pool) {
    ExtraDisk d = new ExtraDisk();
    d.setName(name);
    d.setSize(10);
    d.setPool(pool);
    return d;
  }

  private static Server server(String id, String name, ExtraDisk... extras) {
    Server s = new Server();
    s.setId(id);
    s.setName(name);
    s.setPool("default");
    s.setExtraDisks(List.of(extras));
    return s;
  }

  private static DomainState domain(String name, String... paths) {
    return new DomainState(
        name,
        2,
        2048,
        name,
        "1",
        "mnemosyne",
        List.of(paths).stream().map(p -> new DomainState.Disk("vda", p, null)).toList(),
        true,
        false);
  }

  @Test
  void plannedVolumeAttachedToAnotherDomain_blocksTheServer() {
    // Arrange
    Preflight preflight = new Preflight();
    Server s = server("web-01", "web-01", disk("data", "default"));
    DomainState other = domain("someone-else", "/var/lib/libvirt/images/web-01-data.qcow2");
    // Act
    preflight.checkVolumeOwnership(s, List.of(other), POOLS, null);
    // Assert
    assertThat(preflight.blockers("web-01"))
        .containsExactly("volume 'web-01-data.qcow2' already attached to domain 'someone-else'");
  }

  @Test
  void theServersOwnDomainIsNotAConflictWithItself() {
    // Its extra disk being attached where it belongs is the normal, already-converged case.
    // Arrange
    Preflight preflight = new Preflight();
    Server s = server("web-01", "web-01", disk("data", "default"));
    DomainState own = domain("web-01", "/var/lib/libvirt/images/web-01-data.qcow2");
    // Act
    preflight.checkVolumeOwnership(s, List.of(own), POOLS, "web-01");
    // Assert
    assertThat(preflight.ok()).isTrue();
  }

  @Test
  void aSameNamedVolumeInAnotherPoolIsNotAConflict() {
    // Paths are compared in full for this reason: two pools may each hold a 'web-01-data.qcow2'
    // and they are different files.
    // Arrange
    Preflight preflight = new Preflight();
    Server s = server("web-01", "web-01", disk("data", "nvme"));
    DomainState other = domain("someone-else", "/var/lib/libvirt/images/web-01-data.qcow2");
    // Act
    preflight.checkVolumeOwnership(s, List.of(other), POOLS, null);
    // Assert
    assertThat(preflight.ok()).isTrue();
  }

  @Test
  void theRootVolumeIsCheckedToo() {
    // It is the name a second inventory entry is most likely to repeat, and it is cloned over
    // whatever is already there.
    // Arrange
    Preflight preflight = new Preflight();
    Server s = server("web-01", "web-01");
    DomainState other = domain("someone-else", "/var/lib/libvirt/images/web-01.qcow2");
    // Act
    preflight.checkVolumeOwnership(s, List.of(other), POOLS, null);
    // Assert
    assertThat(preflight.blockers("web-01"))
        .containsExactly("volume 'web-01.qcow2' already attached to domain 'someone-else'");
  }

  @Test
  void aPoolWithNoLocalPathIsSkippedRatherThanGuessedAt() {
    // An rbd or iscsi pool has no directory, so nothing can be said about path collisions in it.
    // Arrange
    Preflight preflight = new Preflight();
    Server s = server("web-01", "web-01", disk("data", "ceph"));
    DomainState other = domain("someone-else", "/var/lib/libvirt/images/web-01-data.qcow2");
    // Act
    preflight.checkVolumeOwnership(s, List.of(other), POOLS, null);
    // Assert
    assertThat(preflight.ok()).isTrue();
  }

  @Test
  void twoServersPlanningTheSameVolume_blockBoth() {
    // 'web' + disk '01-data' and 'web-01' + disk 'data' both come out as web-01-data.qcow2.
    // Arrange
    Preflight preflight = new Preflight();
    Server a = server("a", "web", disk("01-data", "default"));
    Server b = server("b", "web-01", disk("data", "default"));
    // Act
    preflight.checkVolumeCollisions(List.of(a, b), POOLS);
    // Assert
    assertThat(preflight.blockers("a"))
        .containsExactly("volume 'web-01-data.qcow2' planned by more than one server: a, b");
    assertThat(preflight.blockers("b")).hasSize(1);
  }

  @Test
  void distinctServersWithDistinctDisksCollideWithNothing() {
    // Arrange
    Preflight preflight = new Preflight();
    Server a = server("a", "web-01", disk("data", "default"), disk("logs", "nvme"));
    Server b = server("b", "web-02", disk("data", "default"));
    // Act
    preflight.checkVolumeCollisions(List.of(a, b), POOLS);
    // Assert
    assertThat(preflight.ok()).isTrue();
  }

  @Test
  void poolsOf_listsEveryPoolTheDisksNeedOnce() {
    // Act
    var pools =
        Preflight.poolsOf(
            List.of(
                server("a", "web-01", disk("data", "default"), disk("logs", "nvme")),
                server("b", "web-02", disk("data", "nvme"))));
    // Assert
    assertThat(pools).containsExactly("default", "nvme");
  }
}
