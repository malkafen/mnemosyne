package com.mnemosyne.app.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.Getter;

@Getter
public final class Plan {

  public record Update(Server server, DomainState actual) {
    public boolean cpuChanged() {
      return server.getCpu() != actual.cpu();
    }

    public boolean ramChanged() {
      return server.getRam() != actual.ram();
    }

    public String diff() {
      StringBuilder sb = new StringBuilder();
      if (cpuChanged()) sb.append(String.format(" cpu %d->%d", actual.cpu(), server.getCpu()));
      // RAM excluded from the plan output until libvirt-java ships setMemoryFlags:
      // if (ramChanged()) sb.append(String.format(" ram %d->%d", actual.ram(), server.getRam()));
      return sb.toString().trim();
    }
  }

  private final Map<String, Server> toAdopt;
  private final Map<String, Update> toUpdate;
  private final Map<String, Server> toCreate;
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
            .map(d -> new Update(servers.get(d.serverId()), d))
            .filter(u -> u.cpuChanged() /* || u.ramChanged() */)
            .collect(
                Collectors.toMap(u -> u.actual().serverId(), u -> u, (a, b) -> a, TreeMap::new));

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
    toDelete.forEach(n -> System.out.println("  - " + n));
    toUpdate.forEach((id, u) -> System.out.printf("  ~ %s  (%s)%n", id, u.diff()));
    toCreate.keySet().forEach(n -> System.out.println("  + " + n));
    System.out.println();
  }
}
