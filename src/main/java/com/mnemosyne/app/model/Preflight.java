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

@Getter
public final class Preflight {

  public record Problem(String resource, String reason) {}

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

  public static Set<String> poolsOf(Collection<Server> servers) {
    return servers.stream()
        .flatMap(
            s ->
                Stream.concat(
                    Stream.of(s.getPool()), s.getExtraDisks().stream().map(ExtraDisk::getPool)))
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  public List<String> blockers(String serverId) {
    return problems.entrySet().stream()
        .filter(e -> e.getValue().contains(serverId))
        .map(e -> e.getKey().resource() + " " + e.getKey().reason())
        .toList();
  }
}
