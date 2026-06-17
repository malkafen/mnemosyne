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
  private final List<String> toUpdate;
  private final List<String> toDelete;
  private final List<String> unmanaged;

  public Plan(List<DomainState> actual, List<Server> desired) {

    HashMap<String, Server> serverById = new HashMap<>();
    HashMap<String, DomainState> managedD = new HashMap<>();
    HashMap<String, DomainState> unmanagedD = new HashMap<>();

    for (Server s : desired) serverById.put(s.getId(), s);

    for (DomainState d : actual) {
      if ("mnemosyne".equals(d.managedBy())) managedD.put(d.serverId(), d);
      else unmanagedD.put(d.name(), d);
    }

    this.toCreate =
        serverById.values().stream()
            .filter(n -> !managedD.containsKey(n.getId())) // check by id
            .filter(n -> !unmanagedD.containsKey(n.getId())) // check by name
            .collect(Collectors.toMap(n -> n.getId(), n -> n, (a, b) -> a, TreeMap::new));

    this.toUpdate =
        managedD.values().stream()
            .filter(d -> serverById.containsKey(d.serverId()))
            .filter(d -> !serverById.get(d.serverId()).getSpecHash().equals(d.specHash()))
            .map(d -> d.name())
            .sorted()
            .toList();

    this.toDelete =
        managedD.values().stream()
            .filter(d -> !serverById.containsKey(d.serverId()))
            .map(d -> d.name())
            .sorted()
            .toList();

    this.unmanaged = unmanagedD.values().stream().map(d -> d.name()).sorted().toList();
  }

  public void print(String group, boolean printJoin) {

    if (printJoin) {
      if (unmanaged.isEmpty()) return;
      System.out.printf("[ %s ]  unmanaged (can be adopted): %d%n", group, unmanaged.size());
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
    toUpdate.forEach(n -> System.out.println("  ~ " + n));
    toDelete.forEach(n -> System.out.println("  - " + n));
  }
}
