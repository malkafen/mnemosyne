package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.exception.VolumeCleanupException;
import com.mnemosyne.app.http.CloudInitServer;
import com.mnemosyne.app.libvirt.DomainOps.DomainSpec;
import com.mnemosyne.app.libvirt.StorageOps.VolumeSpec;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.Plan;
import com.mnemosyne.app.model.Preflight;
import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.output.Report;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    List<DomainState> actual = domainOps.readActualState();
    this.plan = new Plan(actual, servers, deleteDisable);
    return this.plan;
  }

  /**
   * Checks what every VM the plan would create needs from the host, before anything is applied.
   * Nothing is cloned or defined for an update, an adoption or a delete, so only {@code toCreate}
   * is checked and a group with nothing to create costs no calls at all. Pools, images and networks
   * are looked up once per distinct value, not once per VM.
   */
  public Preflight preflight() {
    Preflight preflight = new Preflight();
    if (this.plan == null || this.plan.getToCreate().isEmpty()) {
      log.debug("[ {} ] nothing to check (no VM to create)", group);
      return preflight;
    }
    // Grouped by the value first, so a pool, an image or a network shared by twenty VMs is looked
    // up once and reported once, against all the servers that need it.
    Map<String, List<Server>> byImage = new LinkedHashMap<>();
    Map<String, List<Server>> byNetwork = new LinkedHashMap<>();

    for (Server s : this.plan.getToCreate().values()) {
      byImage
          .computeIfAbsent(s.getPool() + "\u001f" + s.getVolLookup(), k -> new ArrayList<>())
          .add(s);
      byNetwork.computeIfAbsent(s.getNetwork(), k -> new ArrayList<>()).add(s);
      preflight.checkTemplates(s);
    }
    for (List<Server> servers : byImage.values()) {
      Server first = servers.get(0);
      storageOps
          .checkBaseImage(first.getPool(), first.getVolLookup())
          .ifPresent(p -> preflight.add(p, ids(servers)));
    }
    for (List<Server> servers : byNetwork.values()) {
      networkOps
          .checkNetwork(servers.get(0).getNetwork())
          .ifPresent(p -> preflight.add(p, ids(servers)));
    }
    log.debug("[ {} ] preflight found {} problem(s)", group, preflight.getProblems().size());
    return preflight;
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
        if (u.cpuChanged()) domainOps.updateCpu(name, s.getCpu());
        if (u.ramChanged()) domainOps.updateRam(name, s.getRam());
        if (u.autostartChanged()) domainOps.updateAutostart(name, s.getAutostart());
        if (u.powerChanged()) {
          // Every managed VM has been through cloud-init at creation, so a start needs no seed.
          if (s.isLaunch()) domainOps.startDomain(name);
          else domainOps.shutdownDomain(name);
        }
        report.add("update", "~", s.getId(), u.diff() + restartNote(u));
      } catch (LibvirtException e) {
        log.debug("[ {} ] update failed for '{}'", group, s.getId(), e);
        report.skip(s.getId(), "update failed: " + cause(e));
      }
    }
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
        // A new VM always boots once so cloud-init can configure it; launch:false is honoured
        // afterwards, in settle().
        DomainSpec domainSpec =
            new DomainSpec(s.getName(), s.buildServerXml(), true, s.getAutostart());
        CloudInitServer.register(s.buildSeed());
        domainOps.setupDomain(domainSpec);
        if (!s.isLaunch()) toSettle.add(s);
        report.add("create", "+", s.getId(), s.isLaunch() ? "" : "off after init");
      } catch (LibvirtException e) {
        log.debug("[ {} ] create failed for '{}'", group, s.getId(), e);
        CloudInitServer.unregister(s.getName());
        report.skip(s.getId(), "create failed: " + cause(e));
      }
    }
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
