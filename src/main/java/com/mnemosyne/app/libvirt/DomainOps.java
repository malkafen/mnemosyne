package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.model.DomainState;
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

  record DomainSpec(String name, String domainXml, boolean isLaunch) {}

  void setupDomain(DomainSpec spec) throws LibvirtException {
    Domain d = defineDomain(spec);
    try {
      if (spec.isLaunch()) {
        try {
          createDomain(d, spec.name());
        } catch (LibvirtException e) {
          try {
            undefineDomain(spec.name());
          } catch (LibvirtException u) {
            e.addSuppressed(u);
          }
          throw e;
        }
      }
    } finally {
      freeDomainQuietly(d);
    }
  }

  private Domain defineDomain(DomainSpec spec) throws LibvirtException {
    try {
      log.trace("Domain XML for server '{}': {}", spec.name(), spec.domainXml());
      Domain d = connect.domainDefineXML(spec.domainXml());
      return d;
    } catch (LibvirtException e) {
      log.error(
          "Libvirt operation failed when define for server '{}' (code: {})",
          spec.name(),
          e.getError() != null ? e.getError().getCode() : "unknown",
          e);
      throw e;
    }
  }

  private void createDomain(Domain d, String name) throws LibvirtException {
    try {
      log.debug("Creating domain '{}'...", name);
      d.create();
      log.debug("Domain '{}' has been started successfully.", name);
    } catch (LibvirtException e) {
      log.error("Failed to create domain '{}': {}.", name, e.getMessage(), e);
      throw e;
    }
  }

  void destroyDomain(String name) throws LibvirtException {
    log.debug("Destroying domain '{}'...", name);
    Domain d = connect.domainLookupByName(name);
    try {
      if (d.isActive() == 1) {
        log.debug("Domain '{}' is active, destroying...", name);
        d.destroy();
        log.debug("Domain '{}' destroyed successfully", name);
      }
    } catch (LibvirtException e) {
      log.error("Failed to destroy domain '{}'", name, e);
      throw e;
    } finally {
      freeDomainQuietly(d);
    }
  }

  void undefineDomain(String name) throws LibvirtException {
    log.debug("Undefining domain '{}'...", name);
    Domain d = connect.domainLookupByName(name);
    try {
      d.undefine();
      log.debug("Domain '{}' undefined successfully", name);
    } catch (LibvirtException e) {
      log.error("Failed to undefine domain '{}'", name, e);
      throw e;
    } finally {
      freeDomainQuietly(d);
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
      for (Domain d : domains)
        actual.add(XmlUtil.getShortState(d.getXMLDesc(Domain.XMLFlags.INACTIVE)));
      return actual;
    } finally {
      for (Domain d : domains) freeDomainQuietly(d);
    }
  }

  boolean joinDomain(String name, String metadata) {
    Domain d = null;
    try {
      d = connect.domainLookupByName(name);

      int flags =
          (d.isActive() == 1)
              ? Domain.ModificationImpact.CONFIG | Domain.ModificationImpact.LIVE
              : Domain.ModificationImpact.CONFIG;

      d.setMetadata(Domain.MetadataType.ELEMENT, metadata, "mnem", XmlUtil.MNEM_NS, flags);

      return true;
    } catch (LibvirtException e) {
      log.error("Failed to join domain '{}'", name, e);
      return false;
    } finally {
      if (d != null) freeDomainQuietly(d);
    }
  }

  List<String> getDiskPaths(String name) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    final List<String> diskPaths;
    try {
      String domainXml = d.getXMLDesc(0);
      diskPaths = XmlUtil.diskPaths(domainXml);
    } catch (LibvirtException e) {
      log.error("Failed to get XML description for domain '{}'", name, e);
      throw e;
    } finally {
      if (d != null) freeDomainQuietly(d);
    }

    if (diskPaths.isEmpty()) {
      log.debug("Domain '{}' has no file-backed disk paths", name);
      return List.of();
    }
    return diskPaths;
  }

  boolean updateCpu(String name, int cpu) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    try {
      log.debug("Domain '{}': setting vcpus to {} (config only)", name, cpu);
      d.setVcpusFlags(cpu, Domain.VcpuFlags.CONFIG | Domain.VcpuFlags.MAXIMUM);
      d.setVcpusFlags(cpu, Domain.VcpuFlags.CONFIG);
      log.debug("Domain '{}': cpu updated (applies after restart)", name);
      return true;
    } catch (LibvirtException e) {
      log.error("Failed to update cpu for domain '{}'", name);
      throw e;
    } finally {
      freeDomainQuietly(d);
    }
  }
}
