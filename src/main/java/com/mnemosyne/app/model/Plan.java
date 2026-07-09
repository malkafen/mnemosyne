package com.mnemosyne.app.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.Getter;

@Getter
public final class Plan {

  private final Map<String, Server> toCreate;
  private final Map<String, String> toUpdate;
  private final Map<String, Server> toAdopt;
  private final List<String> toDelete;
  private final List<String> unmanaged;

  public Plan(List<DomainState> actual, Map<String, Server> servers) {

    HashMap<String, DomainState> managedD = new HashMap<>();
    HashMap<String, DomainState> unmanagedD = new HashMap<>();

    for (DomainState d : actual) {
      if ("mnemosyne".equals(d.managedBy())) managedD.put(d.serverId(), d);
      else unmanagedD.put(d.name(), d);
    }

    this.toCreate =
        servers.entrySet().stream()
            .filter(e -> !managedD.containsKey(e.getKey()))
            .filter(e -> !unmanagedD.containsKey(e.getValue().getName()))
            .collect(
                Collectors.toMap(e -> e.getKey(), e -> e.getValue(), (a, b) -> a, TreeMap::new));

    this.toUpdate =
        managedD.values().stream()
            .filter(d -> servers.containsKey(d.serverId()))
            .filter(d -> specDrifted(servers.get(d.serverId()), d))
            .collect(
                Collectors.toMap(
                    d -> d.serverId(),
                    d -> diff(servers.get(d.serverId()), d),
                    (a, b) -> a,
                    TreeMap::new));

    this.toDelete =
        managedD.values().stream()
            .filter(d -> !servers.containsKey(d.serverId()))
            .map(d -> d.name())
            .sorted()
            .toList();

    this.toAdopt =
        unmanagedD.values().stream()
            .filter(d -> servers.containsKey(d.serverId()))
            .collect(
                Collectors.toMap(
                    d -> d.serverId(), d -> servers.get(d.serverId()), (a, b) -> a, TreeMap::new));

    this.unmanaged = unmanagedD.values().stream().map(d -> d.name()).sorted().toList();
  }

  public void print(String group, boolean isJoin) {
    System.out.println();
    if (isJoin) {
      if (unmanaged.isEmpty()) {
        System.out.printf("[ %s ] no unmanaged domains%n%n", group);
        return;
      }
      System.out.printf("[ %s ]  unmanaged (can be adopted): %d%n%n", group, unmanaged.size());
      unmanaged.forEach(n -> System.out.println("  > " + n));
      return;
    }

    boolean noChanges = toCreate.isEmpty() && toUpdate.isEmpty() && toDelete.isEmpty();

    if (noChanges) {
      System.out.printf("[ %s ]  no changes%n", group);
      return;
    }

    System.out.printf(
        "[ %s ]  create: %d, update: %d, delete: %d%n",
        group, toCreate.size(), toUpdate.size(), toDelete.size());
    toCreate.keySet().forEach(n -> System.out.println("  + " + n));
    toUpdate.forEach((id, diff) -> System.out.println("  ~ " + id + "  " + diff));
    toDelete.forEach(n -> System.out.println("  - " + n));
    System.out.println();
  }

  private boolean specDrifted(Server s, DomainState d) {
    // RAM is temporarily excluded from drift detection: libvirt-java 0.5.4
    // return !s.getSpecHash().equals(Server.specHash(d.cpu(), d.ram()));
    return s.getCpu() != d.cpu();
  }

  private String diff(Server s, DomainState d) {
    StringBuilder sb = new StringBuilder();
    if (s.getCpu() != d.cpu()) sb.append(String.format(" cpu %d->%d", d.cpu(), s.getCpu()));
    // RAM excluded from the plan output until libvirt-java ships setMemoryFlags:
    // if (s.getRam() != d.ram()) sb.append(String.format(" ram %d->%d", d.ram(), s.getRam()));
    return sb.toString().trim();
  }
}
