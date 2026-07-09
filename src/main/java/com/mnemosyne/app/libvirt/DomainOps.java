package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.utils.XmlUtil;
import java.util.ArrayList;
import java.util.List;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DomainOps {

  private static final Logger log = LoggerFactory.getLogger(DomainOps.class);
  private final Connect connect;

  public DomainOps(Connect connect) {
    this.connect = connect;
  }

  void delete(List<String> toDelete) {
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

  public List<DomainState> readActualState() throws LibvirtException {
    Domain[] domains = connect.listAllDomains(0);
    try {
      List<DomainState> actual = new ArrayList<>(domains.length);
      for (Domain d : domains) actual.add(XmlUtil.getShortState(d.getXMLDesc(0)));
      return actual;
    } finally {
      for (Domain d : domains) freeDomainQuietly(d);
    }
  }

  boolean joinDomain(String group, Server s) {
    Domain d = null;
    try {
      d = connect.domainLookupByName(s.getId());

      int flags =
          (d.isActive() == 1)
              ? Domain.ModificationImpact.CONFIG | Domain.ModificationImpact.LIVE
              : Domain.ModificationImpact.CONFIG;

      d.setMetadata(
          Domain.MetadataType.ELEMENT,
          s.buildMnemosyneMetadataXml(),
          "mnem",
          XmlUtil.MNEM_NS,
          flags);

      return true;
    } catch (LibvirtException e) {
      log.error(
          "Failed to join domain '{}' in group '{}': {}", s.getId(), group, e.getMessage(), e);
      return false;
    } finally {
      if (d != null) freeDomainQuietly(d);
    }
  }
}
