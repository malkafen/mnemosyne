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

  /**
   * One extra disk the inventory asks for that the domain does not have yet.
   *
   * <p>{@code target} is what the plan predicts the disk will be called in the guest. It is
   * computed from the persistent config, so the reconciler recomputes it against the live domain
   * before attaching — a disk hot-plugged since the last boot occupies a name the stored config
   * does not know about.
   */
  public record DiskAttach(ExtraDisk disk, String target) {}

  /**
   * One disk the inventory wants bigger than it is on the host.
   *
   * <p>Carries both addresses the reconciler might need, because which one it uses depends on the
   * domain's power state: {@code target} for a running domain, whose disk QEMU resizes in place,
   * and {@code path} for a shut-down one, whose volume is resized through the storage pool.
   */
  public record DiskGrow(String label, String target, String path, long fromGiB, long toGiB) {}

  /**
   * One disk the inventory wants smaller than it is on the host.
   *
   * <p>Never acted on, and not a note either: shrinking a disk throws away whatever lives past the
   * new end, and no amount of care makes that recoverable. It stops the run instead, so the
   * operator fixes the inventory rather than discovering later that the two disagree. Carried here
   * rather than reported on the spot so that {@link Plan} stays a pure function.
   */
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

    /** An unset {@code autostart} is not desired state, so it never counts as drift. */
    public boolean autostartChanged() {
      return server.getAutostart() != null && server.getAutostart() != actual.autostart();
    }

    /** Whether there is a disk to add. A disk is never removed. */
    public boolean disksChanged() {
      return !toAttach.isEmpty();
    }

    /** Whether a disk the domain already has must get bigger. */
    public boolean growChanged() {
      return !toGrow.isEmpty();
    }

    /**
     * Whether this entry is worth applying, as opposed to only worth mentioning.
     *
     * <p>A shrink is deliberately absent: it is the one piece of drift that is never applied, and
     * counting it here would put the server on the list of things to do.
     */
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

  /** Managed domains absent from config, keyed by VM name, mapped to the volumes to delete. */
  private final Map<String, List<String>> toDelete;

  /**
   * Volumes of the domains in {@link #toDelete} that stay in their pool, keyed by VM name, as the
   * lines the plan prints for them.
   *
   * <p>A volume goes with the VM only when its metadata records it as created by Mnemosyne — or the
   * run says {@code --purge-disks} — and no other domain uses it. A recorded volume that was
   * detached by hand is listed too, and never deleted.
   */
  private final Map<String, List<String>> kept;

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

  /**
   * Managed domains of the inventory whose initialization never finished ({@code <mnem:init
   * state='pending'>}), keyed by server id.
   *
   * <p>Their XML may match the inventory to the last byte and they are still not what it describes:
   * cloud-init never confirmed it had configured them. They are shown and left alone — nothing is
   * updated on a VM that was never set up — and they make the run incomplete. What to do with one
   * is the operator's call until Mnemosyne can replace it.
   */
  private final Map<String, DomainState> pendingInit;

  /**
   * Managed domains of the inventory with no {@code <mnem:init>} or a state Mnemosyne does not
   * know, keyed by server id, mapped to the reason. Turned into preflight problems by the caller:
   * Mnemosyne does not guess whether such a VM was ever initialized.
   */
  private final Map<String, String> unknownInit;

  /**
   * Disks the inventory wants smaller than they are, keyed by server id.
   *
   * <p>Separate from {@link #notes} because it is not a note: the caller turns these into preflight
   * problems, which stop the run. Separate from {@link #toUpdate} because there is nothing to apply
   * — a server whose only drift is a shrink has no work to do and must not appear as if it had.
   */
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

    // Every managed domain the inventory knows, diffed once. Both maps below are derived from
    // this list rather than filled in as it is built: update() stays a pure function, so nothing
    // here depends on the pipeline running sequentially, in order, or exactly once per element.
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

    // A pending VM is not diffed at all: nothing on it is updated, so there is nothing to show.
    List<Update> matched =
        inInventory.stream()
            .filter(d -> !pendingInit.containsKey(d.serverId()))
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
    List<DiskGrow> toGrow = new ArrayList<>();
    List<DiskShrink> shrinks = new ArrayList<>();
    sizeDrift(s, d, toGrow, shrinks);
    notes.addAll(unknownDiskNotes(s, d));
    return new Update(
        s, d, toAttach, List.copyOf(toGrow), List.copyOf(shrinks), List.copyOf(notes));
  }

  /**
   * Compares the size of every disk the domain already has with the size the inventory asks for.
   *
   * <p>Only disks that are attached are looked at. One the inventory lists and the domain does not
   * have is an attach, and it is created at the right size to begin with, so there is nothing here
   * to compare it against.
   *
   * <p>The root disk is matched by position and the extra disks by serial, each the way the rest of
   * the code already identifies them, so a renamed VM is measured against the disks it actually
   * has.
   */
  private static void sizeDrift(
      Server s, DomainState d, List<DiskGrow> grow, List<DiskShrink> shrink) {

    d.rootDisk().ifPresent(root -> compareSize("root disk", root, s.getDisk(), grow, shrink));
    for (ExtraDisk e : s.getExtraDisks())
      d.diskFor(e, s.getName())
          .ifPresent(
              disk -> compareSize("disk '" + e.getName() + "'", disk, e.getSize(), grow, shrink));
  }

  /**
   * One disk's size against one inventory figure.
   *
   * <p>A disk whose capacity could not be read is left alone entirely. Treating an unknown size as
   * zero would make every such disk look undersized, and the run would "grow" disks whose real size
   * nobody knows.
   */
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

  /** Another domain the volume is attached to, which deleting it would pull the disk from under. */
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
   * <p>An entry preflight refused is printed as {@code blocked} wherever it would otherwise have
   * appeared — a creation, an update or a bare note — because the run will not carry it out, and a
   * plan that still showed it as {@code update} would be describing something that is not going to
   * happen.
   */
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
    toCreate.forEach(
        (id, s) -> {
          if (blocked(report, preflight, id)) return;
          report.add("create", "+", id, s.isLaunch() ? "" : "off after init");
          report.sub(createDiskLines(s));
        });

    // Servers with something to report but nothing to do. They are not a change, so they are
    // counted separately: an operator reading "update: 0, note: 1" knows nothing will be touched.
    notes.forEach(
        (id, lines) -> {
          if (toUpdate.containsKey(id)) return;
          if (blocked(report, preflight, id)) return;
          report.add("note", "i", id, "");
          report.sub(lines);
        });

    // A server whose only finding is a disk that must not shrink has neither work nor a note, and
    // would otherwise vanish from the plan that is about to stop because of it.
    // The same goes for a domain whose init state cannot be told and that has no drift either.
    Stream.concat(shrinks.keySet().stream(), unknownInit.keySet().stream())
        .distinct()
        .forEach(
            id -> {
              if (toUpdate.containsKey(id) || notes.containsKey(id)) return;
              blocked(report, preflight, id);
            });

    report.print(group, "no changes");
  }

  /** Prints an entry as blocked when preflight refused it, and reports whether it did. */
  private static boolean blocked(Report report, Preflight preflight, String id) {
    List<String> blockers = preflight.blockers(id);
    if (blockers.isEmpty()) return false;
    report.add("blocked", "!", id, String.join("; ", blockers));
    return true;
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
