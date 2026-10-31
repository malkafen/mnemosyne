package com.mnemosyne.app.model;

import com.mnemosyne.app.output.Report;
import com.mnemosyne.app.utils.XmlUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.Getter;

/**
 * The diff between a group's {@code networks} and {@code firewalls} and what the hypervisor has,
 * built the way {@link Plan} is for VMs: a pure function of the inventory and one snapshot.
 *
 * <p>Ownership works as it does for domains. A network is Mnemosyne's when its metadata says so, a
 * filter when its name starts with {@value FirewallSpec#FILTER_PREFIX}; nothing else is changed or
 * deleted. A declared network whose name an unmanaged one holds is not taken over: it stops the
 * run, because the host's network may be carrying VMs nobody asked Mnemosyne about.
 *
 * <p>A network or filter that leaves the inventory is deleted only once nothing uses it — counting
 * what the host will look like after this run, not before: a VM the same run deletes no longer
 * holds its network, and a VM whose firewall the run changes no longer holds the old filter.
 */
@Getter
public final class NetworkPlan {

  /** A filter on the host. {@code canonical} is null for one that is not Mnemosyne's. */
  public record FilterState(String name, String uuid, List<String> canonical) {}

  /**
   * A managed network whose config, power or autostart differs from the inventory.
   *
   * @param diff what differs in the config; empty when only power or autostart does
   * @param running the running domains attached to it, which a restart would cut off
   */
  public record NetUpdate(
      NetworkSpec spec, NetState actual, List<String> diff, List<String> running) {

    public boolean redefine() {
      return !diff.isEmpty();
    }

    public boolean start() {
      return !actual.active();
    }

    public boolean autostartChanged() {
      return spec.isAutostart() != actual.autostart();
    }

    /**
     * A running network takes a new config only through a restart, and a restart detaches every
     * guest on it. That is done only when no running guest is on it; otherwise the config is
     * written and the restart is left to the operator.
     */
    public boolean restartable() {
      return actual.active() && running.isEmpty();
    }

    public String describe() {
      List<String> parts = new ArrayList<>(diff);
      if (autostartChanged())
        parts.add("autostart " + actual.autostart() + "->" + spec.isAutostart());
      if (start()) parts.add("start");
      return String.join(", ", parts);
    }
  }

  /** A managed filter whose rules differ from the inventory, and who is using it right now. */
  public record FilterUpdate(FirewallSpec spec, String uuid, List<String> running) {}

  private final Map<String, NetworkSpec> netCreate = new TreeMap<>();
  private final Map<String, NetUpdate> netUpdate = new TreeMap<>();

  /** Declared networks whose name an unmanaged network holds, mapped to the reason. */
  private final Map<String, String> netTaken = new TreeMap<>();

  private final List<String> netDelete = new ArrayList<>();

  /** Managed networks absent from the inventory that something still uses, mapped to the users. */
  private final Map<String, List<String>> netKept = new TreeMap<>();

  private final Map<String, FirewallSpec> filterCreate = new TreeMap<>();
  private final Map<String, FilterUpdate> filterUpdate = new TreeMap<>();
  private final List<String> filterDelete = new ArrayList<>();
  private final Map<String, List<String>> filterKept = new TreeMap<>();

  /** Whether the host answered for its filters; false on a host without the nwfilter driver. */
  private final boolean filtersKnown;

  /** Every network on the host, for preflight's overlap check. */
  private final List<NetState> hostNetworks;

  /**
   * @param filters the host's filters, or null when they could not be read
   * @param vmPlan the VM plan of the same group, or null; used to tell what each network and filter
   *     is used by once this run is over
   */
  public NetworkPlan(
      Map<String, NetworkSpec> networks,
      Map<String, FirewallSpec> firewalls,
      List<NetState> actualNetworks,
      List<FilterState> filters,
      List<DomainState> domains,
      Plan vmPlan,
      Map<String, Server> servers,
      boolean deleteDisable) {

    this.filtersKnown = filters != null;
    this.hostNetworks = List.copyOf(actualNetworks);

    Map<String, Set<String>> netUsers = new HashMap<>();
    Map<String, Set<String>> filterUsers = new HashMap<>();
    Map<String, Set<String>> netRunning = new HashMap<>();
    Map<String, Set<String>> filterRunning = new HashMap<>();
    usage(domains, vmPlan, servers, netUsers, filterUsers, netRunning, filterRunning);

    Map<String, NetState> byName = new HashMap<>();
    actualNetworks.forEach(n -> byName.put(n.name(), n));

    networks.forEach(
        (name, spec) -> {
          NetState actual = byName.get(name);
          if (actual == null) netCreate.put(name, spec);
          else if (!actual.managed())
            netTaken.put(
                name,
                "name taken by a network mnemosyne does not manage - rename it here, or drop it"
                    + " from `networks` and reference it from servers by name");
          else {
            NetUpdate u =
                new NetUpdate(
                    spec, actual, actual.shape().diff(spec.shape()), sorted(netRunning.get(name)));
            if (u.redefine() || u.start() || u.autostartChanged()) netUpdate.put(name, u);
          }
        });

    if (!deleteDisable) {
      for (NetState n : actualNetworks) {
        if (!n.managed() || networks.containsKey(n.name())) continue;
        List<String> users = sorted(netUsers.get(n.name()));
        if (users.isEmpty()) netDelete.add(n.name());
        else netKept.put(n.name(), users);
      }
      java.util.Collections.sort(netDelete);
    }

    if (filters == null) {
      // Nothing can be compared; preflight reports the missing driver if anything needs it.
      firewalls.forEach((name, spec) -> filterCreate.put(name, spec));
      return;
    }
    Map<String, FilterState> filterByName = new HashMap<>();
    filters.forEach(f -> filterByName.put(f.name(), f));

    firewalls.forEach(
        (name, spec) -> {
          FilterState actual = filterByName.get(spec.filterName());
          if (actual == null) filterCreate.put(name, spec);
          else if (!Objects.equals(actual.canonical(), XmlUtil.canonicalFilter(spec.toXml(null))))
            filterUpdate.put(
                name,
                new FilterUpdate(
                    spec, actual.uuid(), sorted(filterRunning.get(spec.filterName()))));
        });

    if (!deleteDisable) {
      Set<String> wanted = new LinkedHashSet<>();
      firewalls.values().forEach(f -> wanted.add(f.filterName()));
      for (FilterState f : filters) {
        if (!f.name().startsWith(FirewallSpec.FILTER_PREFIX) || wanted.contains(f.name())) continue;
        List<String> users = sorted(filterUsers.get(f.name()));
        if (users.isEmpty()) filterDelete.add(f.name());
        else filterKept.put(f.name(), users);
      }
      java.util.Collections.sort(filterDelete);
    }
  }

  /**
   * Which domains will be on which network and behind which filter once this run is over, and which
   * of them are running now. Domains the run deletes count for nothing; a VM whose firewall the run
   * changes counts with its new one; a VM the run creates counts with what it is created with.
   */
  private static void usage(
      List<DomainState> domains,
      Plan vmPlan,
      Map<String, Server> servers,
      Map<String, Set<String>> netUsers,
      Map<String, Set<String>> filterUsers,
      Map<String, Set<String>> netRunning,
      Map<String, Set<String>> filterRunning) {

    Set<String> leaving = vmPlan == null ? Set.of() : vmPlan.getToDelete().keySet();
    Map<String, String> refiltered = new HashMap<>();
    if (vmPlan != null)
      vmPlan
          .getToUpdate()
          .values()
          .forEach(
              u -> {
                if (u.firewallChanged())
                  refiltered.put(u.actual().name(), u.server().desiredFilter());
              });

    for (DomainState d : domains) {
      if (d.name() == null || leaving.contains(d.name())) continue;
      for (int i = 0; i < d.nics().size(); i++) {
        DomainState.Nic nic = d.nics().get(i);
        if (nic.network() != null) {
          netUsers.computeIfAbsent(nic.network(), k -> new TreeSet<>()).add(d.name());
          if (d.active())
            netRunning.computeIfAbsent(nic.network(), k -> new TreeSet<>()).add(d.name());
        }
        String filter = nic.filter();
        if (i == 0 && refiltered.containsKey(d.name())) filter = refiltered.get(d.name());
        if (filter != null) {
          filterUsers.computeIfAbsent(filter, k -> new TreeSet<>()).add(d.name());
          if (d.active() && Objects.equals(filter, nic.filter()))
            filterRunning.computeIfAbsent(filter, k -> new TreeSet<>()).add(d.name());
        }
      }
    }
    if (vmPlan != null) {
      vmPlan
          .getToCreate()
          .values()
          .forEach(
              s -> {
                netUsers.computeIfAbsent(s.getNetwork(), k -> new TreeSet<>()).add(s.getName());
                if (s.desiredFilter() != null)
                  filterUsers
                      .computeIfAbsent(s.desiredFilter(), k -> new TreeSet<>())
                      .add(s.getName());
              });
    }
  }

  private static List<String> sorted(Set<String> names) {
    return names == null ? List.of() : List.copyOf(new TreeSet<>(names));
  }

  /** Whether there is anything to apply. */
  public boolean hasChanges() {
    return !netCreate.isEmpty()
        || !netUpdate.isEmpty()
        || !netDelete.isEmpty()
        || !filterCreate.isEmpty()
        || !filterUpdate.isEmpty()
        || !filterDelete.isEmpty();
  }

  /** The preflight id of a network, distinct from every server id. */
  public static String netId(String name) {
    return "network:" + name;
  }

  /** The preflight id of a firewall. */
  public static String fwId(String name) {
    return "firewall:" + name;
  }

  /**
   * Prints the networks and firewalls of one group, in the order they are applied: filters and
   * networks to create or change come before the VMs, the deletions after them.
   */
  public void print(String group, Preflight preflight) {
    Report report = new Report();
    netTaken.forEach((name, why) -> blockedOr(report, preflight, netId(name), "network " + name));
    netCreate.forEach(
        (name, spec) -> {
          if (blockedOr(report, preflight, netId(name), "network " + name)) return;
          report.add("create", "+", "network " + name, spec.describe());
        });
    netUpdate.forEach(
        (name, u) -> {
          if (blockedOr(report, preflight, netId(name), "network " + name)) return;
          report.add("update", "~", "network " + name, u.describe());
          if (u.redefine() && u.actual().active() && !u.restartable())
            report.sub(
                List.of(
                    "running guests on it: "
                        + String.join(", ", u.running())
                        + " - the new config waits for a restart of the network, which"
                        + " cuts them off: virsh net-destroy "
                        + name
                        + " && virsh net-start "
                        + name));
        });
    filterCreate.forEach(
        (name, spec) -> {
          if (blockedOr(report, preflight, fwId(name), "firewall " + name)) return;
          report.add("create", "+", "firewall " + name, spec.describe());
          report.sub(spec.getRules().stream().map(FirewallRule::describe).toList());
        });
    filterUpdate.forEach(
        (name, u) -> {
          if (blockedOr(report, preflight, fwId(name), "firewall " + name)) return;
          report.add(
              "update",
              "~",
              "firewall " + name,
              u.spec().describe()
                  + (u.running().isEmpty()
                      ? ""
                      : ", applies live to " + String.join(", ", u.running())));
          report.sub(u.spec().getRules().stream().map(FirewallRule::describe).toList());
        });
    netDelete.forEach(name -> report.add("delete", "-", "network " + name, ""));
    netKept.forEach(
        (name, users) ->
            report.add(
                "note",
                "i",
                "network " + name,
                "not in the inventory, still used by " + String.join(", ", users) + " - kept"));
    filterDelete.forEach(
        name ->
            report.add(
                "delete",
                "-",
                "firewall " + name.substring(FirewallSpec.FILTER_PREFIX.length()),
                ""));
    filterKept.forEach(
        (name, users) ->
            report.add(
                "note",
                "i",
                "firewall " + name.substring(FirewallSpec.FILTER_PREFIX.length()),
                "not in the inventory, still used by " + String.join(", ", users) + " - kept"));
    report.print(group + " · networks");
  }

  private static boolean blockedOr(Report report, Preflight preflight, String id, String label) {
    List<String> blockers = preflight.blockers(id);
    if (blockers.isEmpty()) return false;
    report.add("blocked", "!", label, String.join("; ", blockers));
    return true;
  }
}
