package com.mnemosyne.app.model;

import com.mnemosyne.app.output.Report;
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

  public Plan(List<DomainState> actual, Map<String, Server> servers, boolean deleteDisable) {

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
        !deleteDisable
            ? managedD.values().stream()
                .filter(d -> !servers.containsKey(d.serverId()))
                .map(DomainState::name)
                .sorted()
                .toList()
            : List.of();

    this.toAdopt =
        servers.entrySet().stream()
            .filter(e -> unmanagedD.containsKey(e.getValue().getName()))
            .collect(
                Collectors.toMap(e -> e.getKey(), e -> e.getValue(), (a, b) -> a, TreeMap::new));

    this.unmanaged = unmanagedD.values().stream().map(d -> d.name()).sorted().toList();
  }

  public void print(String group, boolean isJoin) {
    Report report = new Report();

    if (isJoin) {
      Map<String, String> adoptByName =
          toAdopt.entrySet().stream()
              .collect(Collectors.toMap(e -> e.getValue().getName(), Map.Entry::getKey));

      for (String n : unmanaged) {
        String id = adoptByName.get(n);
        if (id != null) report.add("adopt", "+", n, "as '" + id + "'");
        else report.add("unmanaged", ">", n, "");
      }
      report.print(group, "no unmanaged domains");
      return;
    }

    toDelete.forEach(n -> report.add("delete", "-", n, ""));
    toUpdate.forEach((id, u) -> report.add("update", "~", id, u.diff()));
    toCreate.keySet().forEach(n -> report.add("create", "+", n, ""));
    report.print(group, "no changes");
  }
}
