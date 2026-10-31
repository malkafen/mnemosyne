package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.http.CloudInitServer;
import com.mnemosyne.app.libvirt.DomainOps.DomainSpec;
import com.mnemosyne.app.libvirt.StorageOps.PoolCheck;
import com.mnemosyne.app.libvirt.StorageOps.Provisioned;
import com.mnemosyne.app.libvirt.StorageOps.VolumeSpec;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.ExtraDisk;
import com.mnemosyne.app.model.InitMarker;
import com.mnemosyne.app.model.Plan;
import com.mnemosyne.app.model.Preflight;
import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.output.Report;
import com.mnemosyne.app.utils.TargetDev;
import java.io.IOException;
import java.time.Instant;
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

public class Harmonia implements AutoCloseable {

  private final DomainOps domainOps;
  private final StorageOps storageOps;
  private final NetworkOps networkOps;
  private final Connect connect;
  private final String group;
  private Plan plan;

  private List<DomainState> actual = List.of();

  private final Set<String> toSettle = ConcurrentHashMap.newKeySet();

  private int failures;

  private static final Logger log = LoggerFactory.getLogger(Harmonia.class);

  private static final long WORKER_STOP_WAIT_S = 30L;

  private static final AtomicInteger workerCount = new AtomicInteger();

  public Harmonia(
      String group, String user, String key, String host, int port, boolean skipHostKeyCheck)
      throws LibvirtException, IOException {
    this(group, Hypervisor.connect(user, key, host, port, skipHostKeyCheck));
  }

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

  public Preflight preflight() {
    Preflight preflight = new Preflight();
    if (this.plan == null) {
      log.debug("[ {} ] nothing to check (no plan)", group);
      return preflight;
    }
    refuseShrinks(preflight);
    refuseUnknownInit(preflight);

    List<Server> creating = List.copyOf(this.plan.getToCreate().values());
    List<Plan.Update> attaching =
        this.plan.getToUpdate().values().stream().filter(Plan.Update::disksChanged).toList();

    if (creating.isEmpty() && attaching.isEmpty()) {
      log.debug("[ {} ] nothing to check (no VM to create, no disk to attach)", group);
      return preflight;
    }

    List<Server> servers = new ArrayList<>(creating);
    attaching.forEach(u -> servers.add(u.server()));

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

  private void refuseUnknownInit(Preflight preflight) {
    this.plan
        .getUnknownInit()
        .forEach(
            (id, reason) ->
                preflight.add(
                    new Preflight.Problem(
                        "domain '" + id + "'",
                        reason + " - fix it with 'virsh metadata' or remove the domain by hand"),
                    id));
  }

  public List<String> pendingInit() {
    return this.plan == null ? List.of() : List.copyOf(this.plan.getPendingInit().keySet());
  }

  public List<String> unjoined() {
    return this.plan == null ? List.of() : List.copyOf(this.plan.getToAdopt().keySet());
  }

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
    String metadata =
        s.buildMnemosyneMetadataXml(List.of(), List.of(), InitMarker.adopted(Instant.now()));
    if (domainOps.joinDomain(s.getName(), metadata)) report.add("join", "+", s.getId(), "");
    else {
      log.debug("[ {} ] join failed for '{}'", group, s.getId());
      report.skip(s.getId(), "join failed (run with -v for details)");
    }
  }

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
      stopWorkers(pool);
      blocks.forEach(report::merge);
    }
  }

  private static void await(List<Future<?>> running) {
    RuntimeException failure = null;
    for (Future<?> f : running) {
      try {
        f.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while applying the plan", e);
      } catch (ExecutionException e) {
        Throwable c = e.getCause();
        if (failure == null)
          failure = (c instanceof RuntimeException r) ? r : new IllegalStateException(c);
      }
    }
    if (failure != null) throw failure;
  }

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
    t.setDaemon(true);
    return t;
  }

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
      List<String> diskLines = new ArrayList<>(attachDisks(u));
      diskLines.addAll(growDisks(u));
      if (u.cpuChanged()) domainOps.updateCpu(name, s.getCpu());
      if (u.ramChanged()) domainOps.updateRam(name, s.getRam());
      if (u.autostartChanged()) domainOps.updateAutostart(name, s.getAutostart());
      if (u.powerChanged()) {
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
    List<String> owned = new ArrayList<>(u.actual().ownedPaths());
    owned.addAll(u.actual().detachedOwned());
    List<String> reused = new ArrayList<>(u.actual().reusedPaths());
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

      if (!owned.contains(volume.path()) && !reused.contains(volume.path())) {
        (volume.reused() ? reused : owned).add(volume.path());
        domainOps.writeMetadata(
            name, s.buildMnemosyneMetadataXml(owned, reused, u.actual().init()));
      }

      boolean hotPlugged =
          domainOps.attachDisk(name, s.buildExtraDiskXml(disk, target, volume.path()), live);

      used.add(target);
      lines.add(diskLine(disk, target, volume, live && !hotPlugged));
    }
    return lines;
  }

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

  private static String diskLine(
      ExtraDisk disk, String target, Provisioned volume, boolean afterRestart) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%s %s in pool '%s'", target, disk.getName(), disk.getPool()));
    if (volume.reused()) sb.append(" (").append(REUSED_NOTE).append(")");
    if (afterRestart) sb.append(" (applies after power cycle)");
    return sb.toString();
  }

  private static String restartNote(Plan.Update u) {
    boolean staysUp = u.actual().active() && u.server().isLaunch();
    return (u.cpuChanged() || u.ramChanged()) && staysUp ? ", applies after restart" : "";
  }

  private static final String REUSED_NOTE =
      "reused existing volume - not created by mnemosyne, kept when the VM is deleted";

  private void createOne(Server s, Report report) {
    List<String> created = new ArrayList<>();
    try {
      VolumeSpec volSpec =
          new VolumeSpec(
              s.getVolName(), s.getPool(), s.buildVolumeXml(), s.getVolLookup(), s.getDisk());
      s.setInit(InitMarker.pending(Instant.now()));
      Provisioned root = storageOps.provisionVolume(volSpec);
      s.setVolPath(root.path());
      List<String> diskLines = new ArrayList<>();
      if (root.reused()) {
        s.getReusedVolPaths().add(root.path());
        diskLines.add(String.format("root %s (%s)", root.path(), REUSED_NOTE));
      } else {
        created.add(root.path());
      }
      diskLines.addAll(createExtraVolumes(s, created));
      DomainSpec domainSpec =
          new DomainSpec(s.getName(), s.buildServerXml(), true, s.getAutostart());
      CloudInitServer.register(s.buildSeed(), () -> confirmInit(s));
      domainOps.setupDomain(domainSpec);
      if (!s.isLaunch()) toSettle.add(s.getId());
      report.add("create", "+", s.getId(), s.isLaunch() ? "" : "off after init");
      report.sub(diskLines);
    } catch (LibvirtException | RuntimeException e) {
      log.debug("[ {} ] create failed for '{}'", group, s.getId(), e);
      CloudInitServer.unregister(s.getName());
      report.skip(s.getId(), "create failed: " + cause(e));
      report.sub(rollback(s.getName(), created));
    }
  }

  private void confirmInit(Server s) throws LibvirtException {
    synchronized (s) {
      InitMarker finished = s.getInit().finish(Instant.now());
      domainOps.writeMetadata(
          s.getName(),
          s.buildMnemosyneMetadataXml(
              s.ownedPaths(), List.copyOf(s.getReusedVolPaths()), finished));
      s.setInit(finished);
    }
  }

  private List<String> rollback(String name, List<String> created) {
    if (created.isEmpty()) return List.of();
    try {
      if (!domainOps.isDefined(name)) {
        storageOps.deleteVolumes(created, name);
        return created.stream().map(p -> p + " - created by this attempt, deleted").toList();
      }
      log.debug("[ {} ] domain '{}' is still defined, its volumes are kept", group, name);
    } catch (LibvirtException | RuntimeException e) {
      log.debug("[ {} ] rollback of the volumes of '{}' failed", group, name, e);
    }
    return created.stream()
        .map(p -> p + " - created by this attempt, left in the pool: delete it by hand")
        .toList();
  }

  private List<String> createExtraVolumes(Server s, List<String> created) throws LibvirtException {
    if (s.getExtraDisks().isEmpty()) return List.of();

    Map<String, String> paths = new LinkedHashMap<>();
    List<String> lines = new ArrayList<>();
    for (ExtraDisk disk : s.getExtraDisks()) {
      Provisioned volume =
          storageOps.provisionBlankVolume(
              VolumeSpec.blank(
                  disk.volName(s.getName()), disk.getPool(), s.buildExtraVolumeXml(disk)));
      if (volume.reused()) s.getReusedVolPaths().add(volume.path());
      else created.add(volume.path());
      paths.put(disk.getName(), volume.path());
      lines.add(
          String.format(
              "%s %dG in pool '%s'%s",
              disk.getName(),
              disk.getSize(),
              disk.getPool(),
              volume.reused() ? " (" + REUSED_NOTE + ")" : ""));
    }
    s.setExtraVolPaths(paths);
    return lines;
  }

  public void settle(int parallel) {
    Report report = new Report();
    phase("stop", pendingStops(), this::settleOne, report, parallel);
    report.print(group);
    failures += report.skipped();
  }

  private void settleOne(Server s, Report report) {
    boolean initialized = CloudInitServer.initialized(s.getName());
    try {
      domainOps.shutdownDomain(s.getName());
      report.add("stop", "-", s.getId(), initialized ? "initialized" : "cloud-init did not finish");
    } catch (LibvirtException | RuntimeException e) {
      log.debug("[ {} ] shutdown failed for '{}'", group, s.getId(), e);
      report.skip(s.getId(), "shutdown failed: " + cause(e));
    }
  }

  private List<Server> pendingStops() {
    if (this.plan == null || toSettle.isEmpty()) return List.of();
    return plan.getToCreate().values().stream().filter(s -> toSettle.contains(s.getId())).toList();
  }

  public boolean hasPendingStop() {
    return !toSettle.isEmpty();
  }

  public int failures() {
    return failures;
  }

  private static String cause(Throwable e) {
    String msg = e.getMessage();
    return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg.trim();
  }
}
