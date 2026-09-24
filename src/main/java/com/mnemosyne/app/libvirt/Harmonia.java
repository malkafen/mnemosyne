package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.http.CloudInitServer;
import com.mnemosyne.app.libvirt.DomainOps.DomainSpec;
import com.mnemosyne.app.libvirt.StorageOps.PoolCheck;
import com.mnemosyne.app.libvirt.StorageOps.Provisioned;
import com.mnemosyne.app.libvirt.StorageOps.VolumeSpec;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.ExtraDisk;
import com.mnemosyne.app.model.Plan;
import com.mnemosyne.app.model.Preflight;
import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.output.Report;
import com.mnemosyne.app.utils.TargetDev;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One hypervisor's share of the run: the plan for it, the checks in front of it, and the calls that
 * apply it.
 *
 * <p>Within a phase the VMs may be applied several at a time — see {@link #phase} for what that is
 * allowed to touch. Everything a worker reaches from there is either its own ({@link Server}, its
 * own {@link Report} block) or built to be shared: {@link DomainOps}, {@link StorageOps} and {@link
 * NetworkOps} hold nothing but the connection, libvirt connections are safe to call from several
 * threads at once, and {@link CloudInitServer} keeps its seeds in concurrent maps because the
 * guests were always going to fetch them in parallel.
 */
public class Harmonia implements AutoCloseable {

  private final DomainOps domainOps;
  private final StorageOps storageOps;
  private final NetworkOps networkOps;
  private final Connect connect;
  private final String group;
  private Plan plan;

  /**
   * The domains as they were when the plan was built. Kept so preflight can tell whether a volume
   * name Mnemosyne is about to use is already attached somewhere, without reading the host twice.
   */
  private List<DomainState> actual = List.of();

  /**
   * The ids of the VMs booted only so cloud-init could configure them; shut down in {@link
   * #settle}. Ids rather than servers, and a set rather than a list, because they are written by
   * however many workers are creating VMs at once: the order they went in carries no meaning, and
   * {@link #pendingStops()} reads them back in the plan's order instead.
   */
  private final Set<String> toSettle = ConcurrentHashMap.newKeySet();

  /**
   * How many entries this group gave up on, across every block it printed. A per-VM failure keeps
   * the run going, but it is still a failure, and {@code Mnemosyne} turns the total into the
   * process' exit code so that a run nobody watched is not mistaken for a clean one.
   *
   * <p>Only ever touched by the thread that drives the group, after a phase has finished and its
   * blocks have been folded in, so the count is one thread's arithmetic however many workers did
   * the work.
   */
  private int failures;

  private static final Logger log = LoggerFactory.getLogger(Harmonia.class);

  /** How long a finished phase waits for its workers before it reads their report blocks. */
  private static final long WORKER_STOP_WAIT_S = 30L;

  /** Numbers the phase workers, so a log line says which VM a thread was busy with. */
  private static final AtomicInteger workerCount = new AtomicInteger();

  public Harmonia(String group, String user, String key, String host, int port)
      throws LibvirtException, IOException {
    this(group, Hypervisor.connect(user, key, host, port));
  }

  /** Everything this class does goes through one connection; opening it is the only other step. */
  Harmonia(String group, Connect connect) {
    this.domainOps = new DomainOps(connect);
    this.storageOps = new StorageOps(connect);
    this.networkOps = new NetworkOps(connect);
    this.connect = connect;
    this.group = group;
  }

  @Override
  public void close() throws LibvirtException {
    log.debug("Closing connection for Harmonia...");
    connect.close();
    log.debug("Harmonia connection closed, go to bed");
  }

  public Plan plan(Map<String, Server> servers, boolean deleteDisable) throws LibvirtException {
    return plan(servers, deleteDisable, false);
  }

  public Plan plan(Map<String, Server> servers, boolean deleteDisable, boolean purgeDisks)
      throws LibvirtException {
    this.actual = withDiskCapacities(domainOps.readActualState());
    this.plan = new Plan(this.actual, servers, deleteDisable, purgeDisks);
    return this.plan;
  }

  /**
   * Fills in the size of every disk of every managed domain, before the plan is built.
   *
   * <p>A volume's capacity is the one disk fact the domain XML does not carry, and the plan needs
   * it to tell a disk that matches the inventory from one that has to grow. It is attached to the
   * snapshot here rather than looked up later so that {@link Plan} keeps deciding everything about
   * a domain from one value, the way it does for vCPU, RAM, power and autostart — a second source
   * of "what should change" is exactly what the plan's own comments warn against.
   *
   * <p>The price is one volume lookup per disk of a managed domain, per run. Unmanaged domains are
   * skipped: they are never touched, so their sizes are nobody's business. A lookup that fails
   * leaves the disk at {@link DomainState#CAPACITY_UNKNOWN}, and an unknown size is never acted on.
   */
  private List<DomainState> withDiskCapacities(List<DomainState> domains) {
    List<DomainState> filled = new ArrayList<>(domains.size());
    for (DomainState d : domains) {
      if (!d.managed() || d.disks().isEmpty()) {
        filled.add(d);
        continue;
      }
      List<DomainState.Disk> disks = new ArrayList<>(d.disks().size());
      for (DomainState.Disk disk : d.disks()) {
        OptionalLong capacity =
            disk.path() == null ? OptionalLong.empty() : storageOps.capacityGiB(disk.path());
        disks.add(capacity.isPresent() ? disk.withCapacity(capacity.getAsLong()) : disk);
      }
      filled.add(d.withDisks(disks));
    }
    return List.copyOf(filled);
  }

  /**
   * Checks what the plan needs from the host before anything is applied.
   *
   * <p>Nothing is cloned or defined for an adoption or a delete, so only the creations and the
   * updates that add a disk are checked, and a group with neither costs no calls at all. Pools,
   * images and networks are looked up once per distinct value, not once per VM.
   *
   * <p>A pool is looked up for its own sake even when a base image is checked in it, because the
   * pool's directory is what the volume-ownership check compares paths against. That is one extra
   * read-only lookup per pool, and it buys the one check that stands between a typo and two domains
   * writing into the same disk image.
   */
  public Preflight preflight() {
    Preflight preflight = new Preflight();
    if (this.plan == null) {
      log.debug("[ {} ] nothing to check (no plan)", group);
      return preflight;
    }
    refuseShrinks(preflight);

    List<Server> creating = List.copyOf(this.plan.getToCreate().values());
    List<Plan.Update> attaching =
        this.plan.getToUpdate().values().stream().filter(Plan.Update::disksChanged).toList();

    if (creating.isEmpty() && attaching.isEmpty()) {
      log.debug("[ {} ] nothing to check (no VM to create, no disk to attach)", group);
      return preflight;
    }

    List<Server> servers = new ArrayList<>(creating);
    attaching.forEach(u -> servers.add(u.server()));

    // Grouped by the value first, so a pool, an image or a network shared by twenty VMs is looked
    // up once and reported once, against all the servers that need it.
    Map<String, List<Server>> byPool = new LinkedHashMap<>();
    Map<String, List<Server>> byImage = new LinkedHashMap<>();
    Map<String, List<Server>> byNetwork = new LinkedHashMap<>();

    for (Server s : servers) {
      byPool.computeIfAbsent(s.getPool(), k -> new ArrayList<>()).add(s);
      for (ExtraDisk d : s.getExtraDisks())
        byPool.computeIfAbsent(d.getPool(), k -> new ArrayList<>()).add(s);
    }
    for (Server s : creating) {
      byImage
          .computeIfAbsent(s.getPool() + "\u001f" + s.getVolLookup(), k -> new ArrayList<>())
          .add(s);
      byNetwork.computeIfAbsent(s.getNetwork(), k -> new ArrayList<>()).add(s);
      preflight.checkTemplates(s);
    }

    Map<String, String> poolPaths = new LinkedHashMap<>();
    byPool.forEach(
        (pool, needed) -> {
          PoolCheck check = storageOps.checkPool(pool);
          check.problem().ifPresent(p -> preflight.add(p, ids(needed)));
          if (check.targetPath() != null) poolPaths.put(pool, check.targetPath());
        });

    for (List<Server> needed : byImage.values()) {
      Server first = needed.get(0);
      // A pool that is already reported as a problem cannot be searched for an image in it.
      if (poolPaths.containsKey(first.getPool()) || preflight.blockers(first.getId()).isEmpty()) {
        storageOps
            .checkBaseImage(first.getPool(), first.getVolLookup())
            .ifPresent(p -> preflight.add(p, ids(needed)));
      }
    }
    for (List<Server> needed : byNetwork.values()) {
      networkOps
          .checkNetwork(needed.get(0).getNetwork())
          .ifPresent(p -> preflight.add(p, ids(needed)));
    }

    preflight.checkVolumeCollisions(servers, poolPaths);
    for (Server s : creating) preflight.checkVolumeOwnership(s, this.actual, poolPaths, null);
    for (Plan.Update u : attaching)
      preflight.checkVolumeOwnership(u.server(), this.actual, poolPaths, u.actual().name());

    log.debug("[ {} ] preflight found {} problem(s)", group, preflight.getProblems().size());
    return preflight;
  }

  /**
   * Turns every disk the inventory wants smaller into a preflight problem, which stops the run.
   *
   * <p>It goes through preflight rather than through a mechanism of its own because preflight is
   * already what halts a run, and because the operator then reads one list of reasons instead of
   * two. It is added before preflight's early return: a group with nothing to create and no disk to
   * attach still has to stop if one of its disks is over size.
   *
   * <p>Costs nothing over the wire. The sizes were read when the snapshot was taken, and the
   * comparison was done by the plan.
   */
  private void refuseShrinks(Preflight preflight) {
    this.plan
        .getShrinks()
        .forEach(
            (id, disks) ->
                disks.forEach(
                    d ->
                        preflight.add(
                            new Preflight.Problem(
                                d.label() + " of '" + id + "'",
                                String.format(
                                    "%dG on the host, %dG in the inventory; a disk is never shrunk"
                                        + " - put %dG back in the inventory",
                                    d.actualGiB(), d.wantedGiB(), d.actualGiB())),
                            id)));
  }

  private static List<String> ids(List<Server> servers) {
    return servers.stream().map(Server::getId).toList();
  }

  public void join(int parallel) {
    if (this.plan == null) {
      log.debug("[ {} ] nothing to join (no plan)", group);
      return;
    }
    if (this.plan.getUnmanaged().isEmpty()) {
      log.debug("[ {} ] nothing to join", group);
      return;
    }

    Report report = new Report();
    phase("join", this.plan.getToAdopt().values(), this::joinOne, report, parallel);
    report.print(group);
    failures += report.skipped();
  }

  private void joinOne(Server s, Report report) {
    // An adopted VM's volumes were not created by Mnemosyne: none are recorded as its own.
    if (domainOps.joinDomain(s.getName(), s.buildMnemosyneMetadataXml(List.of())))
      report.add("join", "+", s.getId(), "");
    else {
      log.debug("[ {} ] join failed for '{}'", group, s.getId());
      report.skip(s.getId(), "join failed (run with -v for details)");
    }
  }

  /**
   * Applies the plan to the host: everything to delete, then everything to update, then everything
   * to create, and within each of those three up to {@code parallel} VMs at a time.
   *
   * <p>The order of the phases is the contract and does not bend for concurrency — a name freed by
   * a delete has to be free before a create asks for it, and a disk added by an update has to be
   * there before the guest is told to boot. So each phase is finished, and its results folded into
   * the report, before the next one starts. Within a phase the entries are independent: the plan
   * has already made every one of them about a different domain, and preflight has already refused
   * the case where two of them want the same volume.
   *
   * <p>A failure belongs to the VM it happened on and to nothing else: every entry is attempted,
   * the ones that fail are reported as skipped, and the run carries on. That holds whatever the
   * failure is. libvirt refusing a call is the expected kind; a template that cannot be rendered is
   * the other, and it arrives as an unchecked exception from deep inside the builders. Catching
   * only {@link LibvirtException} let that second kind escape the loop and take the whole run with
   * it — the VMs already created went unreported, their seeds were never served because the
   * cloud-init server was stopped on the way out, and a guest that had booted without one could
   * never be configured again, because the next run finds the domain healthy and starts an existing
   * VM without a seed.
   *
   * <p>The report is printed from a {@code finally} block for the same reason: whatever stops the
   * run, what was already applied to the host is what gets printed.
   */
  public void reconcile(int parallel) {
    if (this.plan == null) {
      log.debug("[ {} ] nothing to reconcile (no plan)", group);
      return;
    }
    Report report = new Report();
    try {
      phase("delete", plan.getToDelete().entrySet(), this::deleteOne, report, parallel);
      phase("update", plan.getToUpdate().values(), this::updateOne, report, parallel);
      phase("create", plan.getToCreate().values(), this::createOne, report, parallel);
    } finally {
      report.print(group);
      failures += report.skipped();
    }
  }

  /**
   * Runs one phase's entries, at most {@code parallel} of them at a time, and folds what each one
   * reported into {@code report} in the order the plan listed them.
   *
   * <p>What a worker is allowed to touch is what makes this safe, and it is worth stating: its own
   * entry, its own block of the report, and the hypervisor. Nothing else is shared but the
   * connection and the three {@code *Ops} that wrap it, none of which keeps state between calls.
   * The two pieces of the run that several entries do write to at once — the cloud-init seeds and
   * the set of VMs owed a shutdown — are concurrent collections, and neither is read until the
   * phase is over.
   *
   * <p>The report is not built as the work lands. Each entry writes into a block of its own and the
   * blocks are folded back in the plan's order afterwards, so the same inventory prints the same
   * report whether it ran one VM at a time or eight, and a disk line never turns up under somebody
   * else's VM.
   *
   * <p>{@code parallel} of 1 keeps the phase on the calling thread, with no pool and no handoff:
   * the sequential run stays exactly what it was.
   */
  private <T> void phase(
      String what, Collection<T> entries, BiConsumer<T, Report> work, Report report, int parallel) {
    if (entries.isEmpty()) return;

    List<T> items = List.copyOf(entries);
    List<Report> blocks = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) blocks.add(new Report());

    int workers = Math.min(Math.max(parallel, 1), items.size());
    log.debug("[ {} ] {}: {} entries, {} at a time", group, what, items.size(), workers);

    if (workers == 1) {
      try {
        for (int i = 0; i < items.size(); i++) work.accept(items.get(i), blocks.get(i));
      } finally {
        blocks.forEach(report::merge);
      }
      return;
    }

    ExecutorService pool = Executors.newFixedThreadPool(workers, Harmonia::worker);
    try {
      List<Future<?>> running = new ArrayList<>(items.size());
      for (int i = 0; i < items.size(); i++) {
        T item = items.get(i);
        Report block = blocks.get(i);
        running.add(pool.submit(() -> work.accept(item, block)));
      }
      await(running);
    } finally {
      // Both in a finally, and in this order: whatever stopped the phase, the entries that did
      // finish are on the host and belong in the report.
      stopWorkers(pool);
      blocks.forEach(report::merge);
    }
  }

  /**
   * Waits for every entry of a phase, including the ones queued behind a failure.
   *
   * <p>Draining all of them rather than giving up at the first is what keeps the contract: a VM
   * whose neighbour blew up is still attempted, still reported, and — because its block is only
   * read once its worker is gone — still reported correctly.
   */
  private static void await(List<Future<?>> running) {
    RuntimeException failure = null;
    for (Future<?> f : running) {
      try {
        f.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while applying the plan", e);
      } catch (ExecutionException e) {
        // Nothing ordinary arrives here: an entry catches its own failure and reports it as
        // skipped. What does is a bug in this class, and it is raised once the phase is over.
        Throwable c = e.getCause();
        if (failure == null)
          failure = (c instanceof RuntimeException r) ? r : new IllegalStateException(c);
      }
    }
    if (failure != null) throw failure;
  }

  /** Stops a phase's workers and waits for them, because their blocks are read straight after. */
  private static void stopWorkers(ExecutorService pool) {
    pool.shutdownNow();
    try {
      if (!pool.awaitTermination(WORKER_STOP_WAIT_S, TimeUnit.SECONDS))
        log.debug(
            "A worker did not stop within {}s; its report block may be short", WORKER_STOP_WAIT_S);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static Thread worker(Runnable r) {
    Thread t = new Thread(r, "mnemosyne-apply-" + workerCount.incrementAndGet());
    // Daemon for the same reason the cloud-init threads are: a worker that outlives the phase it
    // was interrupted in must not be what keeps the process alive.
    t.setDaemon(true);
    return t;
  }

  // Reconcile methods
  private void deleteOne(Map.Entry<String, List<String>> entry, Report report) {
    String name = entry.getKey();
    List<String> diskPaths = entry.getValue();
    try {
      domainOps.destroyDomain(name);
      domainOps.undefineDomain(name);
      storageOps.deleteVolumes(diskPaths, name);
      List<String> kept = plan.getKept().get(name);
      report.add("delete", "-", name, diskPaths.isEmpty() && kept.isEmpty() ? "no disks" : "");
      report.sub(diskPaths);
      report.sub(kept);
    } catch (LibvirtException | RuntimeException e) {
      log.debug("[ {} ] delete failed for '{}'", group, name, e);
      report.skip(name, "delete failed: " + cause(e));
    }
  }

  private void updateOne(Plan.Update u, Report report) {
    Server s = u.server();
    String name = u.actual().name();
    try {
      // Disks first, and always before the power state: a disk written into the persistent
      // config before a shutdown or a start is already there when the guest comes up, so the
      // same run does not have to both add it and restart for it. Growing comes after adding,
      // for the same reason in reverse: a disk that was just created already has its final size,
      // so there is never anything to grow among the ones this run added.
      List<String> diskLines = new ArrayList<>(attachDisks(u));
      diskLines.addAll(growDisks(u));
      if (u.cpuChanged()) domainOps.updateCpu(name, s.getCpu());
      if (u.ramChanged()) domainOps.updateRam(name, s.getRam());
      if (u.autostartChanged()) domainOps.updateAutostart(name, s.getAutostart());
      if (u.powerChanged()) {
        // Every managed VM has been through cloud-init at creation, so a start needs no seed.
        if (s.isLaunch()) domainOps.startDomain(name);
        else domainOps.shutdownDomain(name);
      }
      report.add("update", "~", s.getId(), u.diff() + restartNote(u));
      report.sub(diskLines);
    } catch (LibvirtException | RuntimeException e) {
      log.debug("[ {} ] update failed for '{}'", group, s.getId(), e);
      report.skip(s.getId(), "update failed: " + cause(e));
    }
  }

  /**
   * Adds the disks an existing VM is missing, and returns one line per disk for the report.
   *
   * <p>Target names are recomputed here rather than taken from the plan. The plan reads the stored
   * config; a disk hot-plugged into the running guest since its last boot is only in the live
   * definition, and reusing its name would attach the new disk on top of it.
   *
   * <p>There is no rollback. A volume created here whose attach then fails is left where it is: the
   * next run sees the disk still missing from the domain, finds the volume, reuses it and attaches
   * it. Deleting it to "clean up" is the one step that could destroy data somebody wanted, and
   * retrying costs nothing.
   */
  private List<String> attachDisks(Plan.Update u) throws LibvirtException {
    if (!u.disksChanged()) return List.of();

    Server s = u.server();
    String name = u.actual().name();
    boolean live = u.actual().active();

    Set<String> used = new LinkedHashSet<>(domainOps.usedDiskTargets(name));
    u.actual().disks().stream()
        .map(DomainState.Disk::target)
        .filter(Objects::nonNull)
        .forEach(used::add);

    String prefix =
        TargetDev.prefix(u.actual().disks().isEmpty() ? null : u.actual().disks().get(0).target());

    List<String> lines = new ArrayList<>();
    // The whole record is rewritten, so the volumes detached by hand are carried over: they are
    // still Mnemosyne's, and dropping them would make them vanish from the plan.
    List<String> owned = new ArrayList<>(u.actual().ownedPaths());
    owned.addAll(u.actual().detachedOwned());
    for (Plan.DiskAttach attach : u.toAttach()) {
      ExtraDisk disk = attach.disk();
      List<String> free = TargetDev.allocate(prefix, used, 1);
      if (free.isEmpty()) {
        lines.add(
            String.format(
                "%s: no free target device name on this domain - not attached", disk.getName()));
        continue;
      }
      String target = free.get(0);

      Provisioned volume =
          storageOps.provisionBlankVolume(
              VolumeSpec.blank(
                  disk.volName(s.getName()), disk.getPool(), s.buildExtraVolumeXml(disk)));

      // Recorded before the attach: a failed attach is retried with this same volume next run,
      // and a volume that was never recorded would be left behind when the VM is deleted.
      if (!owned.contains(volume.path())) {
        owned.add(volume.path());
        domainOps.writeMetadata(name, s.buildMnemosyneMetadataXml(owned));
      }

      boolean hotPlugged =
          domainOps.attachDisk(name, s.buildExtraDiskXml(disk, target, volume.path()), live);

      used.add(target);
      lines.add(diskLine(disk, target, volume, live && !hotPlugged));
    }
    return lines;
  }

  /**
   * Grows the disks the inventory wants bigger, and returns one line per disk for the report.
   *
   * <p>Which call does the work depends on the domain's power state, and the hypervisor does all of
   * it either way. A running domain is resized through QEMU, which grows the image and raises a
   * capacity-change event on the virtio device, so the guest has the new size immediately. A
   * shut-down domain has no QEMU to ask, so its volume is resized in the storage pool and the guest
   * finds the new size when it next boots.
   *
   * <p>There is no rollback, and there can be none: a qcow2 that has grown cannot be put back
   * without risking whatever was written in the meantime. It costs nothing, because both calls set
   * an absolute size rather than a delta — a run that died between the resize and its report leaves
   * a disk the next run simply finds already correct.
   *
   * <p>What happens inside the guest is deliberately not attempted. The partition table and the
   * filesystem on it belong to whoever administers that server, and a run that grew them would be
   * guessing at a layout it has never seen.
   */
  private List<String> growDisks(Plan.Update u) throws LibvirtException {
    if (!u.growChanged()) return List.of();

    String name = u.actual().name();
    boolean live = u.actual().active();

    List<String> lines = new ArrayList<>();
    for (Plan.DiskGrow g : u.toGrow()) {
      if (live && g.target() != null) {
        domainOps.blockResize(name, g.target(), g.toGiB());
      } else if (!live && g.path() != null) {
        storageOps.growVolume(g.path(), g.toGiB());
      } else {
        // A running disk with no target device name, or a stopped one with no file behind it.
        // Neither can be addressed, and neither is worth guessing about.
        lines.add(String.format("%s: no way to address this disk - not resized", g.label()));
        continue;
      }
      lines.add(
          String.format(
              "%s grown %dG->%dG%s",
              g.label(),
              g.fromGiB(),
              g.toGiB(),
              live ? "" : " (the guest sees it at its next boot)"));
    }
    if (!lines.isEmpty())
      lines.add("the guest's partition and filesystem are untouched - extend them yourself");
    return lines;
  }

  /**
   * One report line per disk. Both notes it can carry matter to the operator: {@code reused} says
   * the disk is not blank, and {@code afterRestart} says the guest cannot see it until the domain
   * is stopped and started again.
   */
  private static String diskLine(
      ExtraDisk disk, String target, Provisioned volume, boolean afterRestart) {
    StringBuilder sb = new StringBuilder();
    // The size is already on the update line; what this adds is the target the disk really got,
    // which may differ from the plan's guess, plus whatever the operator has to know about it.
    sb.append(String.format("%s %s in pool '%s'", target, disk.getName(), disk.getPool()));
    if (volume.reused()) sb.append(" (reused existing volume)");
    // "power cycle", not "restart": a device that only made it into the persistent config appears
    // when the domain is stopped and started again. A reboot from inside the guest keeps the same
    // QEMU process, and therefore the same devices it was launched with, so it changes nothing.
    if (afterRestart) sb.append(" (applies after power cycle)");
    return sb.toString();
  }

  /**
   * vCPU and RAM are written to the persistent config only. The note is dropped when the domain is
   * shut down in the same pass, because the new values are then already in effect on its next boot.
   */
  private static String restartNote(Plan.Update u) {
    boolean staysUp = u.actual().active() && u.server().isLaunch();
    return (u.cpuChanged() || u.ramChanged()) && staysUp ? ", applies after restart" : "";
  }

  private void createOne(Server s, Report report) {
    try {
      VolumeSpec volSpec =
          new VolumeSpec(
              s.getVolName(), s.getPool(), s.buildVolumeXml(), s.getVolLookup(), s.getDisk());
      s.setVolPath(storageOps.provisionVolume(volSpec));
      // Every disk must exist before the domain XML is built: the XML points at their paths.
      List<String> diskLines = createExtraVolumes(s);
      // A new VM always boots once so cloud-init can configure it; launch:false is honoured
      // afterwards, in settle().
      DomainSpec domainSpec =
          new DomainSpec(s.getName(), s.buildServerXml(), true, s.getAutostart());
      CloudInitServer.register(s.buildSeed());
      domainOps.setupDomain(domainSpec);
      if (!s.isLaunch()) toSettle.add(s.getId());
      report.add("create", "+", s.getId(), s.isLaunch() ? "" : "off after init");
      report.sub(diskLines);
    } catch (LibvirtException | RuntimeException e) {
      log.debug("[ {} ] create failed for '{}'", group, s.getId(), e);
      // Whatever went wrong, this VM is not coming up in this run, and a seed left registered
      // for it would hold waitForCloudInit for the full timeout and then report a VM that does
      // not exist. Unregistering a name that was never registered costs nothing.
      CloudInitServer.unregister(s.getName());
      report.skip(s.getId(), "create failed: " + cause(e));
    }
  }

  /**
   * Creates the blank volumes of a new VM and hands their paths to the server, so {@code
   * buildServerXml} can point the domain's disks at them.
   *
   * <p>A volume already in the pool under the expected name is reused and reported, never replaced.
   * For the root disk that is a retried creation; for a data disk it can be the previous life of a
   * VM with the same name, which is exactly why Mnemosyne does not delete it to get a blank one.
   */
  private List<String> createExtraVolumes(Server s) throws LibvirtException {
    if (s.getExtraDisks().isEmpty()) return List.of();

    Map<String, String> paths = new LinkedHashMap<>();
    List<String> lines = new ArrayList<>();
    for (ExtraDisk disk : s.getExtraDisks()) {
      Provisioned volume =
          storageOps.provisionBlankVolume(
              VolumeSpec.blank(
                  disk.volName(s.getName()), disk.getPool(), s.buildExtraVolumeXml(disk)));
      paths.put(disk.getName(), volume.path());
      lines.add(
          String.format(
              "%s %dG in pool '%s'%s",
              disk.getName(),
              disk.getSize(),
              disk.getPool(),
              volume.reused() ? " (reused existing volume)" : ""));
    }
    s.setExtraVolPaths(paths);
    return lines;
  }

  /**
   * Shuts down the VMs that were booted only to let cloud-init configure them. Runs after the
   * phone_home wait, so the host still matches the inventory when the run ends — a cloud-init
   * timeout does not keep a launch:false VM running.
   */
  public void settle(int parallel) {
    Report report = new Report();
    // Worth applying side by side more than anything else here: a guest that ignores the shutdown
    // request is waited on for a full minute before it is destroyed, and done one after another
    // that minute is paid once per VM.
    phase("stop", pendingStops(), this::settleOne, report, parallel);
    report.print(group);
    failures += report.skipped();
  }

  private void settleOne(Server s, Report report) {
    // Read before the shutdown, because the answer is about the boot that is ending here.
    boolean initialized = CloudInitServer.initialized(s.getName());
    try {
      domainOps.shutdownDomain(s.getName());
      // The VM is stopped either way - it must not stay up against the inventory - but the
      // word for it is not the same. A guest that never phoned home was booted and nothing
      // more, and "initialized" would be a lie in the one case where it matters.
      report.add("stop", "-", s.getId(), initialized ? "initialized" : "cloud-init did not finish");
    } catch (LibvirtException | RuntimeException e) {
      log.debug("[ {} ] shutdown failed for '{}'", group, s.getId(), e);
      report.skip(s.getId(), "shutdown failed: " + cause(e));
    }
  }

  /** The VMs {@link #createOne} booted for cloud-init and owes a shutdown, in the plan's order. */
  private List<Server> pendingStops() {
    if (this.plan == null || toSettle.isEmpty()) return List.of();
    return plan.getToCreate().values().stream().filter(s -> toSettle.contains(s.getId())).toList();
  }

  public boolean hasPendingStop() {
    return !toSettle.isEmpty();
  }

  /** The entries this group reported as skipped, over the whole run. */
  public int failures() {
    return failures;
  }

  private static String cause(Throwable e) {
    String msg = e.getMessage();
    return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg.trim();
  }
}
