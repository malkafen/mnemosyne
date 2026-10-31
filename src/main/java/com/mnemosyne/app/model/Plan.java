package com.mnemosyne.app.model;

import com.mnemosyne.app.output.Report;
import com.mnemosyne.app.utils.TargetDev;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

@Getter
public final class Plan {

  public record DiskAttach(ExtraDisk disk, String target) {}

  public record DiskGrow(String label, String target, String path, long fromGiB, long toGiB) {}

  public record DiskShrink(String label, long actualGiB, long wantedGiB) {}

  public record Update(
      Server server,
      DomainState actual,
      List<DiskAttach> toAttach,
      List<DiskGrow> toGrow,
      List<DiskShrink> shrinks,
      List<String> notes) {

    public Update(Server server, DomainState actual) {
      this(server, actual, List.of(), List.of(), List.of(), List.of());
    }

    public boolean cpuChanged() {
      return server.getCpu() != actual.cpu();
    }

    public boolean ramChanged() {
      return server.getRam() != actual.ram();
    }

    public boolean powerChanged() {
      return server.isLaunch() != actual.active();
    }

    public boolean autostartChanged() {
      return server.getAutostart() != null && server.getAutostart() != actual.autostart();
    }

    public boolean disksChanged() {
      return !toAttach.isEmpty();
    }

    public boolean growChanged() {
      return !toGrow.isEmpty();
    }

    boolean actionable() {
      return cpuChanged()
          || ramChanged()
          || autostartChanged()
          || powerChanged()
          || disksChanged()
          || growChanged();
    }

    public String diff() {
      StringBuilder sb = new StringBuilder();
      if (cpuChanged()) sb.append(String.format(", cpu %d->%d", actual.cpu(), server.getCpu()));
      if (ramChanged()) sb.append(String.format(", ram %d->%d", actual.ram(), server.getRam()));
      if (autostartChanged())
        sb.append(String.format(", autostart %b->%b", actual.autostart(), server.getAutostart()));
      if (powerChanged())
        sb.append(
            String.format(", power %s->%s", power(actual.active()), power(server.isLaunch())));
      for (DiskAttach a : toAttach)
        sb.append(
            String.format(
                ", attach '%s' %dG as %s", a.disk().getName(), a.disk().getSize(), a.target()));
      for (DiskGrow g : toGrow)
        sb.append(String.format(", grow %s %dG->%dG", g.label(), g.fromGiB(), g.toGiB()));
      return sb.length() == 0 ? "" : sb.substring(2);
    }

    private static String power(boolean on) {
      return on ? "on" : "off";
    }
  }

  private final Map<String, Server> toAdopt;

  private final Map<String, Update> toUpdate;

  private final Map<String, Server> toCreate;

  private final Map<String, List<String>> toDelete;

  private final Map<String, List<String>> kept;

  private final List<String> unmanaged;

  private final Map<String, List<String>> notes;

  private final Map<String, DomainState> pendingInit;

  private final Map<String, String> unknownInit;

  private final Map<String, List<DiskShrink>> shrinks;

  public Plan(List<DomainState> actual, Map<String, Server> servers, boolean deleteDisable) {
    this(actual, servers, deleteDisable, false);
  }

  public Plan(
      List<DomainState> actual,
      Map<String, Server> servers,
      boolean deleteDisable,
      boolean purgeDisks) {

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

    List<DomainState> inInventory =
        managedD.values().stream().filter(d -> servers.containsKey(d.serverId())).toList();

    this.pendingInit =
        inInventory.stream()
            .filter(d -> d.init() != null && d.init().isPending())
            .collect(Collectors.toMap(DomainState::serverId, d -> d, (a, b) -> a, TreeMap::new));

    this.unknownInit =
        inInventory.stream()
            .filter(d -> d.init() == null || !d.init().isKnown())
            .collect(
                Collectors.toMap(
                    DomainState::serverId, Plan::unknownInitReason, (a, b) -> a, TreeMap::new));

    List<Update> matched =
        inInventory.stream()
            .filter(d -> !pendingInit.containsKey(d.serverId()))
            .map(d -> update(servers.get(d.serverId()), d))
            .toList();

    this.notes =
        matched.stream()
            .filter(u -> !u.notes().isEmpty())
            .collect(
                Collectors.toMap(
                    u -> u.server().getId(), Update::notes, (a, b) -> a, TreeMap::new));

    this.shrinks =
        matched.stream()
            .filter(u -> !u.shrinks().isEmpty())
            .collect(
                Collectors.toMap(
                    u -> u.server().getId(), Update::shrinks, (a, b) -> a, TreeMap::new));

    this.toUpdate =
        matched.stream()
            .filter(Update::actionable)
            .collect(
                Collectors.toMap(u -> u.actual().serverId(), u -> u, (a, b) -> a, TreeMap::new));

    List<DomainState> gone =
        deleteDisable
            ? List.of()
            : managedD.values().stream().filter(d -> !servers.containsKey(d.serverId())).toList();
    this.toDelete =
        gone.stream()
            .collect(
                Collectors.toMap(
                    d -> d.name(),
                    d ->
                        d.disks().stream()
                            .filter(disk -> deletable(d, disk, actual, purgeDisks))
                            .map(DomainState.Disk::path)
                            .toList(),
                    (a, b) -> a,
                    TreeMap::new));
    this.kept =
        gone.stream()
            .collect(
                Collectors.toMap(
                    d -> d.name(),
                    d ->
                        Stream.concat(
                                d.disks().stream()
                                    .filter(disk -> !deletable(d, disk, actual, purgeDisks))
                                    .map(disk -> keptLine(d, disk, actual)),
                                Stream.concat(
                                    d.detachedOwned().stream()
                                        .map(
                                            p ->
                                                p
                                                    + " - created by mnemosyne but no longer"
                                                    + " attached, left as is"),
                                    d.detachedReused().stream()
                                        .map(
                                            p ->
                                                p
                                                    + " - reused by mnemosyne, no longer"
                                                    + " attached, left as is")))
                            .toList(),
                    (a, b) -> a,
                    TreeMap::new));

    this.toAdopt =
        servers.entrySet().stream()
            .filter(e -> unmanagedD.containsKey(e.getValue().getName()))
            .collect(
                Collectors.toMap(e -> e.getKey(), e -> e.getValue(), (a, b) -> a, TreeMap::new));

    this.unmanaged = unmanagedD.values().stream().map(d -> d.name()).sorted().toList();
  }

  private static Update update(Server s, DomainState d) {
    List<String> notes = new ArrayList<>();
    List<DiskAttach> toAttach = diskAttachments(s, d, notes);
    List<DiskGrow> toGrow = new ArrayList<>();
    List<DiskShrink> shrinks = new ArrayList<>();
    sizeDrift(s, d, toGrow, shrinks);
    notes.addAll(unknownDiskNotes(s, d));
    return new Update(
        s, d, toAttach, List.copyOf(toGrow), List.copyOf(shrinks), List.copyOf(notes));
  }

  private static void sizeDrift(
      Server s, DomainState d, List<DiskGrow> grow, List<DiskShrink> shrink) {

    d.rootDisk().ifPresent(root -> compareSize("root disk", root, s.getDisk(), grow, shrink));
    for (ExtraDisk e : s.getExtraDisks())
      d.diskFor(e, s.getName())
          .ifPresent(
              disk -> compareSize("disk '" + e.getName() + "'", disk, e.getSize(), grow, shrink));
  }

  private static void compareSize(
      String label,
      DomainState.Disk disk,
      long wantedGiB,
      List<DiskGrow> grow,
      List<DiskShrink> shrink) {

    if (!disk.capacityKnown()) return;
    long actual = disk.capacityGiB();
    if (actual < wantedGiB)
      grow.add(new DiskGrow(label, disk.target(), disk.path(), actual, wantedGiB));
    else if (actual > wantedGiB) shrink.add(new DiskShrink(label, actual, wantedGiB));
  }

  private static List<DiskAttach> diskAttachments(Server s, DomainState d, List<String> notes) {
    if (s.getExtraDisks().isEmpty()) return List.of();

    List<ExtraDisk> missing =
        s.getExtraDisks().stream().filter(e -> d.diskFor(e, s.getName()).isEmpty()).toList();
    if (missing.isEmpty()) return List.of();

    Set<String> used =
        d.disks().stream()
            .map(DomainState.Disk::target)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    String prefix = TargetDev.prefix(d.disks().isEmpty() ? null : d.disks().get(0).target());
    List<String> targets = TargetDev.allocate(prefix, used, missing.size());

    List<DiskAttach> attach = new ArrayList<>(targets.size());
    for (int i = 0; i < missing.size(); i++) {
      if (i < targets.size()) attach.add(new DiskAttach(missing.get(i), targets.get(i)));
      else
        notes.add(
            String.format(
                "disk '%s': no free target device name on this domain - not attached",
                missing.get(i).getName()));
    }
    return List.copyOf(attach);
  }

  private static String unknownInitReason(DomainState d) {
    if (d.init() == null)
      return "has no <mnem:init>, so whether it was ever initialized cannot be told";
    return "has an unknown init state '" + d.init().state() + "' in <mnem:init>";
  }

  private static boolean deletable(
      DomainState d, DomainState.Disk disk, List<DomainState> actual, boolean purgeDisks) {
    return (disk.owned() || purgeDisks) && sharedWith(d, disk, actual).isEmpty();
  }

  private static Optional<String> sharedWith(
      DomainState d, DomainState.Disk disk, List<DomainState> actual) {
    return actual.stream()
        .filter(o -> !d.name().equals(o.name()) && o.diskPaths().contains(disk.path()))
        .map(DomainState::name)
        .findFirst();
  }

  private static String keptLine(DomainState d, DomainState.Disk disk, List<DomainState> actual) {
    return sharedWith(d, disk, actual)
        .map(o -> String.format("%s - also attached to '%s', left as is", disk.path(), o))
        .orElse(
            disk.path()
                + (d.reused(disk.path())
                    ? " - found in the pool and reused by mnemosyne, not created by it"
                    : " - not created by mnemosyne")
                + ", left as is (--purge-disks deletes it)");
  }

  private static List<String> unknownDiskNotes(Server s, DomainState d) {
    if (d.disks().size() <= 1) return List.of();

    Set<DomainState.Disk> known =
        s.getExtraDisks().stream()
            .map(e -> d.diskFor(e, s.getName()))
            .flatMap(Optional::stream)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    return d.disks().stream()
        .skip(1)
        .filter(disk -> disk.volName() != null && !known.contains(disk))
        .map(
            disk ->
                String.format(
                    "disk '%s' (%s) is not in the inventory - left as is",
                    disk.target() == null ? "?" : disk.target(), disk.volName()))
        .toList();
  }

  public void print(String group, boolean isJoin, Preflight preflight) {
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

    toDelete.forEach(
        (n, disks) -> {
          report.add("delete", "-", n, disks.isEmpty() && kept.get(n).isEmpty() ? "no disks" : "");
          report.sub(disks);
          report.sub(kept.get(n));
        });
    toUpdate.forEach(
        (id, u) -> {
          if (blocked(report, preflight, id)) return;
          report.add("update", "~", id, u.diff());
          report.sub(notes.getOrDefault(id, List.of()));
        });
    pendingInit.forEach(
        (id, d) ->
            report.add(
                "pending",
                "!",
                id,
                "init pending"
                    + (d.init().created() == null ? "" : " since " + d.init().created())
                    + ": cloud-init never confirmed it finished - left as is"));
    toAdopt.forEach(
        (id, s) ->
            report.add(
                "unjoined",
                "!",
                id,
                "name '"
                    + s.getName()
                    + "' is taken by an unmanaged domain - adopt it with --join"));
    toCreate.forEach(
        (id, s) -> {
          if (blocked(report, preflight, id)) return;
          report.add("create", "+", id, s.isLaunch() ? "" : "off after init");
          report.sub(createDiskLines(s));
        });

    notes.forEach(
        (id, lines) -> {
          if (toUpdate.containsKey(id)) return;
          if (blocked(report, preflight, id)) return;
          report.add("note", "i", id, "");
          report.sub(lines);
        });

    Stream.concat(shrinks.keySet().stream(), unknownInit.keySet().stream())
        .distinct()
        .forEach(
            id -> {
              if (toUpdate.containsKey(id) || notes.containsKey(id)) return;
              blocked(report, preflight, id);
            });

    report.print(group, "no changes");
  }

  private static boolean blocked(Report report, Preflight preflight, String id) {
    List<String> blockers = preflight.blockers(id);
    if (blockers.isEmpty()) return false;
    report.add("blocked", "!", id, String.join("; ", blockers));
    return true;
  }

  private static List<String> createDiskLines(Server s) {
    Map<String, String> lines = new LinkedHashMap<>();
    s.getExtraDisks()
        .forEach(
            d ->
                lines.put(
                    d.getName(),
                    String.format(
                        "%s %dG in pool '%s'", d.volName(s.getName()), d.getSize(), d.getPool())));
    return List.copyOf(lines.values());
  }
}
