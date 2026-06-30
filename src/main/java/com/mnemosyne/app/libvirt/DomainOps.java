package com.mnemosyne.app.libvirt;

import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.libvirt.Connect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

public class DomainOps {

  private static final Logger log = LoggerFactory.getLogger(DomainOps.class); 
  private final Connect connect;

  public DomainOps(Connect connect){ this.connect = connect; }

  public void delete(List<String> toDelete) {
    for (String name : toDelete) {
        Domain d = null;
      try {
        d = connect.domainLookupByName(name);
        destroyDomain(d, name);
        undefineDomain(d, name);
      } catch (Exception e) {
        log.error("Domain '{}' will be skipping..", name, e);
        continue;
      } finally {
        if (d != null) freeDomainQuietly(d);
      }
    }
  }

  private static void destroyDomain(Domain d, String name) throws LibvirtException {
    try {
      if (d.isActive() == 1) {
        log.debug("Domain '{}' is active, destroying...", name);
        d.destroy();
        log.debug("Domain '{}' destroyed successfully", name);
      }
    } catch (LibvirtException e) {
      log.error("Failed to destroy domain '{}'", name, e);
      throw e;
    }
  }

  private static void undefineDomain(Domain d, String name) throws LibvirtException {
    log.debug("Undefining domain '{}'...", name);
    try {
      d.undefine();
      log.debug("Domain '{}' undefined successfully", name);
    } catch (LibvirtException e) {
      log.error("Failed to undefine domain '{}'", name, e);
      throw e;
    }
  }

    private static void freeDomainQuietly(Domain d) {
    try {
      d.free();
    } catch (LibvirtException e) {
      log.debug("Failed to free domain handle (domain cleanup); ignoring", e);
    }
  }


}
