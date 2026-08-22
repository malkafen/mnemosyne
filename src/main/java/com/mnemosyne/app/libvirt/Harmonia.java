package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.exception.VolumeCleanupException;
import com.mnemosyne.app.http.CloudInitServer;
import com.mnemosyne.app.libvirt.DomainOps.DomainSpec;
import com.mnemosyne.app.libvirt.StorageOps.VolumeSpec;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.Plan;
import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.output.Report;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Harmonia implements AutoCloseable {

  private final DomainOps domainOps;
  private final StorageOps storageOps;
  private final Connect connect;
  private final String group;
  private Plan plan;
  private static final Logger log = LoggerFactory.getLogger(Harmonia.class);

  public Harmonia(String group, String user, String key, String host, int port)
      throws LibvirtException, IOException {
    Connect c = Hypervisor.connect(user, key, host, port);
    this.domainOps = new DomainOps(c);
    this.storageOps = new StorageOps(c);
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
    for (String name : plan.getToDelete()) {
      try {
        List<String> diskPaths = domainOps.getDiskPaths(name);
        domainOps.destroyDomain(name);
        domainOps.undefineDomain(name);
        storageOps.deleteVolumes(diskPaths, name);
        report.add("delete", "-", name, "");
      } catch (LibvirtException | VolumeCleanupException e) {
        log.debug("[ {} ] delete failed for '{}'", group, name, e);
        report.skip(name, "delete failed: " + cause(e));
      }
    }
  }

  private void update(Report report) {
    for (Plan.Update u : plan.getToUpdate().values()) {
      try {
        if (u.cpuChanged())
          if (domainOps.updateCpu(u.actual().name(), u.server().getCpu()))
            report.add("update", "~", u.server().getId(), u.diff() + ", applies after restart");
      } catch (LibvirtException e) {
        log.debug("[ {} ] update failed for '{}'", group, u.server().getId(), e);
        report.skip(u.server().getId(), "update failed: " + cause(e));
      }
    }
  }

  private void create(Report report) {
    for (Server s : plan.getToCreate().values()) {
      try {
        VolumeSpec volSpec =
            new VolumeSpec(
                s.getName(), s.getPool(), s.buildVolumeXml(), s.getVolLookup(), s.getDisk());
        s.setVolPath(storageOps.provisionVolume(volSpec));
        DomainSpec domainSpec = new DomainSpec(s.getName(), s.buildServerXml(), s.isLaunch());
        if (s.isLaunch()) CloudInitServer.register(s.buildSeed());
        domainOps.setupDomain(domainSpec);
        report.add("create", "+", s.getId(), "");
      } catch (LibvirtException e) {
        log.debug("[ {} ] create failed for '{}'", group, s.getId(), e);
        CloudInitServer.unregister(s.getName());
        report.skip(s.getId(), "create failed: " + cause(e));
      }
    }
  }

  private static String cause(Throwable e) {
    String msg = e.getMessage();
    return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg.trim();
  }
}
