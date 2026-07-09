package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.Plan;
import com.mnemosyne.app.model.Server;
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
  private static final Logger log = LoggerFactory.getLogger(Harmonia.class);

  public Harmonia(String user, String key, String host, int port) throws Exception {
    Connect c = Hypervisor.connect(user, key, host, port);
    this.domainOps = new DomainOps(c);
    this.storageOps = new StorageOps(c);
    this.connect = c;
  }

  @Override
  public void close() throws LibvirtException {
    if (connect == null) {
      log.debug("Harmonia has no active connection, nothing to close");
      return;
    }
    log.debug("Closing connection for Harmonia...");
    connect.close();
    log.debug("Harmonia connection closed, go to bed");
  }

  public Plan plan(Map<String, Server> servers) throws LibvirtException {
    List<DomainState> actual = domainOps.readActualState();
    return new Plan(actual, servers);
  }

  public void reconcile(Plan plan) {
    System.out.println("Create " + plan.getToCreate());
    System.out.println("Update " + plan.getToUpdate());
    System.out.println("Delete " + plan.getToDelete());
  }
}
