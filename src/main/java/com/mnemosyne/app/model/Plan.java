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
import lombok.Getter;

@Getter
public final class Plan {

  /**
   * One extra disk the inventory asks for that the domain does not have yet.
   *
   * <p>{@code target} is what the plan predicts the disk will be called in the guest. It is
   * computed from the persistent config, so the reconciler recomputes it against the live domain
   * before attaching — a disk hot-plugged since the last boot occupies a name the stored config
   * does not know about.
   */
  public record DiskAttach(ExtraDisk disk, String target) {}

  public record Update(
      Server server, DomainState actual, List<DiskAttach> toAttach, List<String> notes) {

    public Update(Server server, DomainState actual) {
      this(server, actual, List.of(), List.of());
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

    /** An unset {@code autostart} is not desired state, so it never counts as drift. */
    public boolean autostartChanged() {
      return server.getAutostart() != null && server.getAutostart() != actual.autostart();
    }

    /** Whether there is a disk to add. Disks are only ever added; none is removed or resized. */
    public boolean disksChanged() {
      return !toAttach.isEmpty();
    }

    /** Whether this entry is worth applying, as opposed to only worth mentioning. */
    boolean actionable() {
      return cpuChanged() || ramChanged() || autostartChanged() || powerChanged() || disksChanged();
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
      return sb.length() == 0 ? "" : sb.substring(2);
    }

    private static String power(boolean on) {
      return on ? "on" : "off";
    }
  }

  /**
   * Servers from config whose name matches an existing unmanaged domain, keyed by server id;
   * adopting takes over that domain.
   */
  private final Map<String, Server> toAdopt;

  /**
   * Managed domains whose live vCPU count, RAM, power, autostart or disk set differs from config.
   */
  private final Map<String, Update> toUpdate;

  /**
   * Servers from config with no matching domain (managed or adoptable), keyed by server id; to be
   * created.
   */
  private final Map<String, Server> toCreate;

  /** Managed domains absent from config, keyed by VM name, mapped to their disks to delete. */
  private final Map<String, List<String>> toDelete;

  /** Names of all unmanaged domains, including adoptable ones. */
  private final List<String> unmanaged;

  /**
   * Things seen on the host that Mnemosyne will not act on, keyed by server id.
   *
   * <p>This is where every disk decision that is not "add" ends up. A disk the inventory does not
   * mention is never detached and its volume is never deleted — a data disk holds the only copy of
   * whatever is on it, and a wrong guess cannot be undone — so it is reported instead. Silence here
   * would be the dangerous option: the operator would first learn about such a disk from the list
   * of volumes a {@code delete} is about to remove.
   *
   * <p>Derived from the {@link Update} entries rather than collected alongside them, so the two
   * cannot end up telling different stories about the same server.
   */
  private final Map<String, List<String>> notes;

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

    // Every managed domain the inventory knows, diffed once. Both maps below are derived from
    // this list rather than filled in as it is built: update() stays a pure function, so nothing
    // here depends on the pipeline running sequentially, in order, or exactly once per element.
    List<Update> matched =
        managedD.values().stream()
            .filter(d -> servers.containsKey(d.serverId()))
            .map(d -> update(servers.get(d.serverId()), d))
            .toList();

    // Notes are kept for every matched domain, including the ones with no work to do, which is
    // why they are collected before `actionable` filters anything out.
    this.notes =
        matched.stream()
            .filter(u -> !u.notes().isEmpty())
            .collect(
                Collectors.toMap(
                    u -> u.server().getId(), Update::notes, (a, b) -> a, TreeMap::new));

    this.toUpdate =
        matched.stream()
            .filter(Update::actionable)
            .collect(
                Collectors.toMap(u -> u.actual().serverId(), u -> u, (a, b) -> a, TreeMap::new));

    this.toDelete =
        !deleteDisable
            ? managedD.values().stream()
                .filter(d -> !servers.containsKey(d.serverId()))
                .collect(
                    Collectors.toMap(d -> d.name(), d -> d.diskPaths(), (a, b) -> a, TreeMap::new))
            : Map.of();

    this.toAdopt =
        servers.entrySet().stream()
            .filter(e -> unmanagedD.containsKey(e.getValue().getName()))
            .collect(
                Collectors.toMap(e -> e.getKey(), e -> e.getValue(), (a, b) -> a, TreeMap::new));

    this.unmanaged = unmanagedD.values().stream().map(d -> d.name()).sorted().toList();
  }

  /**
   * Compares one managed domain's disks with the inventory.
   *
   * <p>Costs nothing beyond the state snapshot the plan is built from. A disk is "already there"
   * when the domain has it — not when a volume of that name exists in the pool, which is a
   * different question and belongs to preflight.
   *
   * <p>Pure, and it has to stay that way: the constructor maps it over every managed domain, and a
   * plan that recorded anything on the side would start losing it the day that stream runs in
   * parallel. Everything this finds travels back in the returned {@link Update}.
   */
  private static Update update(Server s, DomainState d) {
    List<String> notes = new ArrayList<>();
    List<DiskAttach> toAttach = diskAttachments(s, d, notes);
    notes.addAll(unknownDiskNotes(s, d));
    return new Update(s, d, toAttach, List.copyOf(notes));
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

  /**
   * Disks attached to the domain that the inventory does not describe.
   *
   * <p>The first file-backed disk is skipped unconditionally: it is the boot disk, and on a domain
   * adopted with {@code --join} its volume can be named anything at all. Everything after it that
   * the inventory does not claim is somebody else's — reported, never touched.
   */
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

  /**
   * The preflight result is printed with the plan rather than after it: a VM whose pool, image,
   * network or template is missing is listed as {@code blocked} instead of {@code create}, so the
   * plan shows what would actually happen and why it would not.
   *
   * <p>{@code audit} carries the disk facts that cannot be read from the domain XML, so it arrives
   * the same way: collected by the reconciler, merged into the entry it belongs to at print time.
   */
  public void print(String group, boolean isJoin, Preflight preflight, DiskAudit audit) {
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

    Map<String, List<String>> allNotes = mergedNotes(audit);

    toDelete.forEach(
        (n, disks) -> {
          report.add("delete", "-", n, disks.isEmpty() ? "no disks" : "");
          report.sub(disks);
        });
    toUpdate.forEach(
        (id, u) -> {
          report.add("update", "~", id, u.diff());
          report.sub(allNotes.getOrDefault(id, List.of()));
        });
    toCreate.forEach(
        (id, s) -> {
          List<String> blockers = preflight.blockers(id);
          if (blockers.isEmpty()) {
            report.add("create", "+", id, s.isLaunch() ? "" : "off after init");
            report.sub(createDiskLines(s));
          } else report.add("blocked", "!", id, String.join("; ", blockers));
        });

    // Servers with something to report but nothing to do. They are not a change, so they are
    // counted separately: an operator reading "update: 0, note: 1" knows nothing will be touched.
    allNotes.forEach(
        (id, lines) -> {
          if (toUpdate.containsKey(id)) return;
          report.add("note", "i", id, "");
          report.sub(lines);
        });

    report.print(group, "no changes");
  }

  /** Notes found while diffing, plus the ones the reconciler had to ask the host about. */
  private Map<String, List<String>> mergedNotes(DiskAudit audit) {
    Map<String, List<String>> merged = new TreeMap<>(notes);
    if (audit == null) return merged;
    audit
        .getNotes()
        .forEach(
            (id, lines) -> {
              List<String> all = new ArrayList<>(merged.getOrDefault(id, List.of()));
              all.addAll(lines);
              merged.put(id, List.copyOf(all));
            });
    return merged;
  }

  /** The disks a new VM is getting, listed under its entry the way deleted volumes are. */
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
