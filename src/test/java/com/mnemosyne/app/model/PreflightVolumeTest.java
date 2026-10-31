package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    Preflight preflight = new Preflight();
    Server s = server("web-01", "web-01", disk("data", "default"));
    DomainState other = domain("someone-else", "/var/lib/libvirt/images/web-01-data.qcow2");
    preflight.checkVolumeOwnership(s, List.of(other), POOLS, null);
    assertThat(preflight.blockers("web-01"))
        .containsExactly("volume 'web-01-data.qcow2' already attached to domain 'someone-else'");
  }

  @Test
  void theServersOwnDomainIsNotAConflictWithItself() {
    Preflight preflight = new Preflight();
    Server s = server("web-01", "web-01", disk("data", "default"));
    DomainState own = domain("web-01", "/var/lib/libvirt/images/web-01-data.qcow2");
    preflight.checkVolumeOwnership(s, List.of(own), POOLS, "web-01");
    assertThat(preflight.ok()).isTrue();
  }

  @Test
  void aSameNamedVolumeInAnotherPoolIsNotAConflict() {
    Preflight preflight = new Preflight();
    Server s = server("web-01", "web-01", disk("data", "nvme"));
    DomainState other = domain("someone-else", "/var/lib/libvirt/images/web-01-data.qcow2");
    preflight.checkVolumeOwnership(s, List.of(other), POOLS, null);
    assertThat(preflight.ok()).isTrue();
  }

  @Test
  void theRootVolumeIsCheckedToo() {
    Preflight preflight = new Preflight();
    Server s = server("web-01", "web-01");
    DomainState other = domain("someone-else", "/var/lib/libvirt/images/web-01.qcow2");
    preflight.checkVolumeOwnership(s, List.of(other), POOLS, null);
    assertThat(preflight.blockers("web-01"))
        .containsExactly("volume 'web-01.qcow2' already attached to domain 'someone-else'");
  }

  @Test
  void aPoolWithNoLocalPathIsSkippedRatherThanGuessedAt() {
    Preflight preflight = new Preflight();
    Server s = server("web-01", "web-01", disk("data", "ceph"));
    DomainState other = domain("someone-else", "/var/lib/libvirt/images/web-01-data.qcow2");
    preflight.checkVolumeOwnership(s, List.of(other), POOLS, null);
    assertThat(preflight.ok()).isTrue();
  }

  @Test
  void twoServersPlanningTheSameVolume_blockBoth() {
    Preflight preflight = new Preflight();
    Server a = server("a", "web", disk("01-data", "default"));
    Server b = server("b", "web-01", disk("data", "default"));
    preflight.checkVolumeCollisions(List.of(a, b), POOLS);
    assertThat(preflight.blockers("a"))
        .containsExactly("volume 'web-01-data.qcow2' planned by more than one server: a, b");
    assertThat(preflight.blockers("b")).hasSize(1);
  }

  @Test
  void distinctServersWithDistinctDisksCollideWithNothing() {
    Preflight preflight = new Preflight();
    Server a = server("a", "web-01", disk("data", "default"), disk("logs", "nvme"));
    Server b = server("b", "web-02", disk("data", "default"));
    preflight.checkVolumeCollisions(List.of(a, b), POOLS);
    assertThat(preflight.ok()).isTrue();
  }

  @Test
  void poolsOf_listsEveryPoolTheDisksNeedOnce() {
    var pools =
        Preflight.poolsOf(
            List.of(
                server("a", "web-01", disk("data", "default"), disk("logs", "nvme")),
                server("b", "web-02", disk("data", "nvme"))));
    assertThat(pools).containsExactly("default", "nvme");
  }
}
