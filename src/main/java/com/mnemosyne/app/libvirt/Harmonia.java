package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.Plan;
import com.mnemosyne.app.model.Server;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.libvirt.StorageVol;
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

  public Plan plan(Map<String, Server> servers) throws LibvirtException {
    List<DomainState> actual = domainOps.readActualState();
    this.plan = new Plan(actual, servers);
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

    List<String> joined = new ArrayList<>();
    Map<String, String> skipped = new LinkedHashMap<>();

    for (Server s : this.plan.getToAdopt().values()) {

      if (domainOps.joinDomain(group, s)) joined.add(s.getId());
      else skipped.put(s.getId(), "join failed (see log)");
    }

    if (joined.isEmpty()) {
      System.out.printf("%n[ %s ]  nothing joined, skipped: %d%n", group, skipped.size());
    } else {
      System.out.printf(
          "%n[ %s ]  joined: %d, skipped: %d%n", group, joined.size(), skipped.size());
    }
    joined.forEach(n -> System.out.println("  + " + n));
    skipped.forEach((n, reason) -> System.out.printf("  · %s  (%s)%n", n, reason));
    System.out.println();
  }

  public void reconcile() throws LibvirtException {
    if (this.plan == null) {
      log.debug("[ {} ] nothing to reconcile (no plan)", group);
      return;
    }
    delete();
    update();
    create();
  }

  // Reconcile methods
  private void delete() throws LibvirtException {
    for (String name : plan.getToDelete()) {
      List<StorageVol> volumes = domainOps.getVolumes(name);
      try {
        domainOps.destroyDomain(name);
        domainOps.undefineDomain(name);
      } catch (LibvirtException e) {
        storageOps.freeVolumesQuietly(volumes);
        throw e;
      }
      storageOps.deleteVolumes(volumes, name);
    }
  }

  private void update() throws LibvirtException {
    Map<String, String> updated = new LinkedHashMap<>();
    for (Plan.Update u : plan.getToUpdate().values()) {
      if (u.cpuChanged())
        if (domainOps.updateCpu(u.actual().name(), u.server().getCpu()))
          updated.put(u.server().getId(), u.diff());
    }

    if (updated.isEmpty()) {
      log.debug("[ {} ] nothing to update", group);
      return;
    }
    System.out.printf(
        "%n[ %s ]  updated: %d  (applies after domain restart)%n", group, updated.size());
    updated.forEach((id, diff) -> System.out.printf("  ~ %s  (%s)%n", id, diff));
    System.out.println();
  }

  private void create() throws LibvirtException {
    System.out.println("Create " + plan.getToCreate());
  }
}
