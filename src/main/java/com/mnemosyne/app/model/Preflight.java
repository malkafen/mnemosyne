package com.mnemosyne.app.model;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/**
 * What a group needs before a VM can be created: the storage pool and base image the new disk is
 * cloned from, the libvirt network the domain XML points at, and the template files rendered on
 * this machine. Collected while the plan is built, so a missing pool stops the run before the first
 * VM instead of halfway through a batch.
 */
@Getter
public final class Preflight {

  /** One unmet prerequisite: what is missing, and why it fails. */
  public record Problem(String resource, String reason) {}

  /**
   * A pool, an image or a template is normally shared by every VM in the group, so a resource is
   * reported once, with the servers that need it: the operator fixes one thing, not one thing per
   * VM, but still sees which VMs are held up by it.
   */
  private final Map<Problem, Set<String>> problems = new LinkedHashMap<>();

  public void add(Problem problem, Collection<String> serverIds) {
    problems.computeIfAbsent(problem, p -> new LinkedHashSet<>()).addAll(serverIds);
  }

  public void add(Problem problem, String serverId) {
    add(problem, List.of(serverId));
  }

  public boolean ok() {
    return problems.isEmpty();
  }

  /**
   * The templates are read from the machine running Mnemosyne, not from the hypervisor, so this
   * costs nothing over the wire. Only readability is checked here; a template that parses but has
   * no {@code <disk>} to fill in is still reported by the builders at creation time.
   */
  public void checkTemplates(Server server) {
    Templates templates = server.getTemplates();
    if (templates == null) {
      add(new Problem("templates", "not configured"), server.getId());
      return;
    }
    for (String path : templates.paths()) {
      File file = new File(path);
      if (!file.isFile() || !file.canRead()) {
        add(new Problem("template '" + path + "'", "not readable on this machine"), server.getId());
      }
    }
  }

  /**
   * Refuses a volume name that is already attached to another domain.
   *
   * <p>This is the one disk condition that blocks a run instead of being reported as a note, and it
   * is the reason extra-disk volume names carry the VM's name. Two domains backed by one qcow2 file
   * corrupt it as soon as both are running, and the damage is done before anything reports an
   * error. Better to stop and make the operator look.
   *
   * <p>Paths are compared in full rather than by file name, so the same volume name in two
   * different pools is not mistaken for a conflict. The comparison costs nothing over the wire: the
   * domains come from the snapshot the plan was built from, and {@code poolPaths} from the pool
   * lookups preflight already does.
   *
   * @param ownDomain the domain this server already has, which its own disks are expected on, or
   *     null for a server that is about to be created
   */
  public void checkVolumeOwnership(
      Server server,
      Collection<DomainState> domains,
      Map<String, String> poolPaths,
      String ownDomain) {

    Map<String, String> planned = plannedPaths(server, poolPaths);
    if (planned.isEmpty()) return;

    for (DomainState domain : domains) {
      if (domain.name() == null || domain.name().equals(ownDomain)) continue;
      for (String path : domain.diskPaths()) {
        planned.forEach(
            (volName, plannedPath) -> {
              if (plannedPath.equals(path)) {
                add(
                    new Problem(
                        "volume '" + volName + "'",
                        "already attached to domain '" + domain.name() + "'"),
                    server.getId());
              }
            });
      }
    }
  }

  /**
   * Refuses two servers of one group that would create the same volume.
   *
   * <p>Extra-disk volume names are built from the VM name and the disk name, so a collision needs
   * two entries that disagree about where one name ends and the other begins — a VM {@code web}
   * with a disk {@code 01-data} against a VM {@code web-01} with a disk {@code data}. Rare, silent,
   * and it ends with two VMs writing to one file, so it is checked rather than assumed away.
   */
  public void checkVolumeCollisions(Collection<Server> servers, Map<String, String> poolPaths) {
    Map<String, List<String>> byPath = new LinkedHashMap<>();
    Map<String, String> volNames = new LinkedHashMap<>();

    for (Server server : servers) {
      plannedPaths(server, poolPaths)
          .forEach(
              (volName, path) -> {
                byPath.computeIfAbsent(path, p -> new ArrayList<>()).add(server.getId());
                volNames.put(path, volName);
              });
    }
    byPath.forEach(
        (path, ids) -> {
          if (ids.size() > 1) {
            add(
                new Problem(
                    "volume '" + volNames.get(path) + "'",
                    "planned by more than one server: " + String.join(", ", ids)),
                ids);
          }
        });
  }

  /**
   * Where each of a server's disks would land, keyed by volume name. The root disk is included: it
   * is the one whose name a second VM is most likely to repeat, and it is cloned over whatever is
   * already there.
   */
  private static Map<String, String> plannedPaths(Server server, Map<String, String> poolPaths) {
    Map<String, String> planned = new LinkedHashMap<>();

    String rootPath = poolPaths.get(server.getPool());
    if (rootPath != null) planned.put(server.getVolName(), join(rootPath, server.getVolName()));

    server
        .getExtraDisks()
        .forEach(
            disk -> {
              String poolPath = poolPaths.get(disk.getPool());
              String volName = disk.volName(server.getName());
              if (poolPath != null) planned.put(volName, join(poolPath, volName));
            });
    return planned;
  }

  private static String join(String dir, String name) {
    return dir.endsWith("/") ? dir + name : dir + "/" + name;
  }

  /** Every distinct pool a server's disks need, root disk included. */
  public static Set<String> poolsOf(Collection<Server> servers) {
    return servers.stream()
        .flatMap(
            s ->
                Stream.concat(
                    Stream.of(s.getPool()), s.getExtraDisks().stream().map(ExtraDisk::getPool)))
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Why this server cannot be created, in the wording the plan prints next to it. Empty for a
   * server whose prerequisites are all in place.
   */
  public List<String> blockers(String serverId) {
    return problems.entrySet().stream()
        .filter(e -> e.getValue().contains(serverId))
        .map(e -> e.getKey().resource() + " " + e.getKey().reason())
        .toList();
  }
}
