package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.exception.VolumeCleanupException;
import com.mnemosyne.app.http.CloudInitServer;
import com.mnemosyne.app.libvirt.DomainOps.DomainSpec;
import com.mnemosyne.app.libvirt.StorageOps.PoolCheck;
import com.mnemosyne.app.libvirt.StorageOps.Provisioned;
import com.mnemosyne.app.libvirt.StorageOps.VolumeSpec;
import com.mnemosyne.app.model.DiskAudit;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.ExtraDisk;
import com.mnemosyne.app.model.Plan;
import com.mnemosyne.app.model.Preflight;
import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.output.Report;
import com.mnemosyne.app.utils.TargetDev;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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

  /**
   * The domains as they were when the plan was built. Kept so preflight can tell whether a volume
   * name Mnemosyne is about to use is already attached somewhere, without reading the host twice.
   */
  private List<DomainState> actual = List.of();

  /** VMs booted only so cloud-init could configure them; shut down in {@link #settle()}. */
  private final List<Server> toSettle = new ArrayList<>();

  private static final Logger log = LoggerFactory.getLogger(Harmonia.class);

  public Harmonia(String group, String user, String key, String host, int port)
      throws LibvirtException, IOException {
    Connect c = Hypervisor.connect(user, key, host, port);
    this.domainOps = new DomainOps(c);
    this.storageOps = new StorageOps(c);
    this.networkOps = new NetworkOps(c);
    this.connect = c;
    this.group = group;
  }

  @Override
  public void close() throws LibvirtException {
    log.debug("Closing connection for Harmonia...");
    connect.close();
    log.debug("Harmonia connection closed, go to bed");
  }

  public Plan plan(Map<String, Server> servers, boolean deleteDisable) throws LibvirtException {
    this.actual = domainOps.readActualState();
    this.plan = new Plan(this.actual, servers, deleteDisable);
    return this.plan;
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
   * Reads the size of the extra disks a managed VM already has, so the plan can say when one no
   * longer matches the inventory.
   *
   * <p>A volume's capacity is not in the domain XML, so this is one lookup per disk that is already
   * in place — spent only on servers that actually declare extra disks. Nothing here is ever acted
   * on: a disk is never grown, and never, under any circumstances, shrunk.
   */
  public DiskAudit diskAudit(Map<String, Server> servers) {
    DiskAudit audit = new DiskAudit();
    for (DomainState d : this.actual) {
      Server s = d.managed() ? servers.get(d.serverId()) : null;
      if (s == null || s.getExtraDisks().isEmpty()) continue;

      for (ExtraDisk disk : s.getExtraDisks()) {
        // Matched the same way the plan matches it, serial first, so a renamed VM is audited on
        // the disks it actually has rather than on the names it would get today.
        Optional<DomainState.Disk> attached = d.diskFor(disk, s.getName());
        // Not attached yet; the plan reports it as an attach, and there is nothing to measure.
        if (attached.isEmpty() || attached.get().path() == null) continue;

        OptionalLong actualGiB = storageOps.capacityGiB(attached.get().path());
        if (actualGiB.isEmpty() || actualGiB.getAsLong() == disk.getSize()) continue;

        audit.add(
            s.getId(),
            String.format(
                "disk '%s' is %dG on the host, %dG in the inventory - left as is",
                disk.getName(), actualGiB.getAsLong(), disk.getSize()));
      }
    }
    return audit;
  }

  private static List<String> ids(List<Server> servers) {
    return servers.stream().map(Server::getId).toList();
  }

  public void join() {
    if (this.plan == null) {
      log.debug("[ {} ] nothing to join (no plan)", group);
      return;
    }
    if (this.plan.getUnmanaged().isEmpty()) {
      log.debug("[ {} ] nothing to join", group);
      return;
    }

    Report report = new Report();
    for (Server s : this.plan.getToAdopt().values()) {
      if (domainOps.joinDomain(s.getName(), s.buildMnemosyneMetadataXml()))
        report.add("join", "+", s.getId(), "");
      else {
        log.debug("[ {} ] join failed for '{}'", group, s.getId());
        report.skip(s.getId(), "join failed (run with -v for details)");
      }
    }
    report.print(group);
  }

  public void reconcile() {
    if (this.plan == null) {
      log.debug("[ {} ] nothing to reconcile (no plan)", group);
      return;
    }
    Report report = new Report();
    delete(report);
    update(report);
    create(report);
    report.print(group);
  }

  // Reconcile methods
  private void delete(Report report) {
    for (String name : plan.getToDelete().keySet()) {
      List<String> diskPaths = plan.getToDelete().get(name);
      try {
        domainOps.destroyDomain(name);
        domainOps.undefineDomain(name);
        storageOps.deleteVolumes(diskPaths, name);
        report.add("delete", "-", name, diskPaths.isEmpty() ? "no disks" : "");
        report.sub(diskPaths);
      } catch (LibvirtException | VolumeCleanupException e) {
        log.debug("[ {} ] delete failed for '{}'", group, name, e);
        report.skip(name, "delete failed: " + cause(e));
      }
    }
  }

  private void update(Report report) {
    for (Plan.Update u : plan.getToUpdate().values()) {
      Server s = u.server();
      String name = u.actual().name();
      try {
        // Disks first, and always before the power state: a disk written into the persistent
        // config before a shutdown or a start is already there when the guest comes up, so the
        // same run does not have to both add it and restart for it.
        List<String> diskLines = attachDisks(u);
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
      } catch (LibvirtException e) {
        log.debug("[ {} ] update failed for '{}'", group, s.getId(), e);
        report.skip(s.getId(), "update failed: " + cause(e));
      }
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

      boolean hotPlugged =
          domainOps.attachDisk(name, s.buildExtraDiskXml(disk, target, volume.path()), live);

      used.add(target);
      lines.add(diskLine(disk, target, volume, live && !hotPlugged));
    }
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

  private void create(Report report) {
    for (Server s : plan.getToCreate().values()) {
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
        if (!s.isLaunch()) toSettle.add(s);
        report.add("create", "+", s.getId(), s.isLaunch() ? "" : "off after init");
        report.sub(diskLines);
      } catch (LibvirtException e) {
        log.debug("[ {} ] create failed for '{}'", group, s.getId(), e);
        CloudInitServer.unregister(s.getName());
        report.skip(s.getId(), "create failed: " + cause(e));
      }
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
  public void settle() {
    Report report = new Report();
    for (Server s : toSettle) {
      try {
        domainOps.shutdownDomain(s.getName());
        report.add("stop", "-", s.getId(), "initialized");
      } catch (LibvirtException e) {
        log.debug("[ {} ] shutdown failed for '{}'", group, s.getId(), e);
        report.skip(s.getId(), "shutdown failed: " + cause(e));
      }
    }
    report.print(group);
  }

  public boolean hasPendingStop() {
    return !toSettle.isEmpty();
  }

  private static String cause(Throwable e) {
    String msg = e.getMessage();
    return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg.trim();
  }
}
