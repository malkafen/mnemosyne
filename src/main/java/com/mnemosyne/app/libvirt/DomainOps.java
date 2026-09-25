package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.utils.XmlUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.Error;
import org.libvirt.LibvirtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DomainOps {

  private static final Logger log = LoggerFactory.getLogger(DomainOps.class);

  /** How long a guest is given to shut down on its own before it is destroyed. */
  private static final long SHUTDOWN_TIMEOUT_MS = 60_000L;

  private static final long SHUTDOWN_POLL_MS = 2000L;

  private static final long BYTES_PER_GIB = 1024L * 1024 * 1024;

  /** {@code VIR_DOMAIN_BLOCK_RESIZE_BYTES}: libvirt-java exposes no constant for it. */
  private static final int VIR_DOMAIN_BLOCK_RESIZE_BYTES = 1;

  private final Connect connect;

  public DomainOps(Connect connect) {
    this.connect = connect;
  }

  record DomainSpec(String name, String domainXml, boolean isLaunch, Boolean autostart) {}

  void setupDomain(DomainSpec spec) throws LibvirtException {
    Domain d = defineDomain(spec);
    try {
      try {
        if (spec.autostart() != null) setAutostart(d, spec.name(), spec.autostart());
        if (spec.isLaunch()) createDomain(d, spec.name());
      } catch (LibvirtException e) {
        try {
          undefineDomain(spec.name());
        } catch (LibvirtException u) {
          e.addSuppressed(u);
        }
        throw e;
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
      log.debug(
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
      log.debug("Failed to create domain '{}': {}.", name, e.getMessage(), e);
      throw e;
    }
  }

  /** Boots an already defined domain. */
  void startDomain(String name) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    try {
      createDomain(d, name);
    } finally {
      freeDomainQuietly(d);
    }
  }

  /**
   * Asks the guest to shut down and waits for it; a guest that is still running after {@link
   * #SHUTDOWN_TIMEOUT_MS} is destroyed, so the run always converges on the inventory.
   */
  void shutdownDomain(String name) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    try {
      if (d.isActive() == 0) {
        log.debug("Domain '{}' is already shut off", name);
        return;
      }
      log.debug("Domain '{}': requesting shutdown...", name);
      d.shutdown();

      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_TIMEOUT_MS);
      while (d.isActive() == 1) {
        if (System.nanoTime() >= deadline) {
          log.debug(
              "Domain '{}' still running after {}s, destroying", name, SHUTDOWN_TIMEOUT_MS / 1000);
          d.destroy();
          return;
        }
        Thread.sleep(SHUTDOWN_POLL_MS);
      }
      log.debug("Domain '{}' shut down successfully", name);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Interrupted while waiting for domain '{}' to shut down", name);
    } catch (LibvirtException e) {
      log.debug("Failed to shut down domain '{}'", name, e);
      throw e;
    } finally {
      freeDomainQuietly(d);
    }
  }

  /**
   * Attaches one disk to a domain that already exists, and says whether the guest can see it yet.
   *
   * <p>The persistent config is written first, in a call of its own. If the live attach then fails
   * — no free PCIe slot, a QEMU too old to hot-plug a virtio disk, a guest that refuses the request
   * — the disk is nonetheless in the domain's config and appears the next time the domain is
   * started. The run converges either way, and the only difference the operator sees is a note
   * saying so. {@code No more available PCI slots} is the usual reason: libvirt keeps only a small
   * spare of hot-pluggable ports, so several disks at once rarely all fit.
   *
   * <p>"Started" means a power cycle of the domain. A reboot from inside the guest keeps the same
   * QEMU process, and with it exactly the devices the domain was launched with.
   *
   * <p>The reverse order would be worse: a disk hot-plugged into a running guest and missing from
   * the config disappears on the next reboot, after the guest has been told to use it.
   */
  boolean attachDisk(String name, String diskXml, boolean live) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    try {
      log.trace("Disk XML attached to domain '{}': {}", name, diskXml);
      d.attachDeviceFlags(diskXml, Domain.DeviceModifyFlags.CONFIG);
      log.debug("Domain '{}': disk added to the persistent config", name);

      if (!live) return false;
      try {
        d.attachDeviceFlags(diskXml, Domain.DeviceModifyFlags.LIVE);
        log.debug("Domain '{}': disk hot-plugged into the running guest", name);
        return true;
      } catch (LibvirtException e) {
        // Not fatal, and deliberately not rethrown: the config already has the disk.
        log.debug("Domain '{}': hot-plug failed, the disk applies after restart", name, e);
        return false;
      }
    } catch (LibvirtException e) {
      log.debug("Failed to attach a disk to domain '{}'", name, e);
      throw e;
    } finally {
      freeDomainQuietly(d);
    }
  }

  /**
   * Target device names in use on a domain right now.
   *
   * <p>Read from the live definition, not the stored one: a disk somebody hot-plugged is in the
   * former and not the latter, and handing out a name it already answers to would attach the new
   * disk over it.
   */
  Set<String> usedDiskTargets(String name) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    try {
      return XmlUtil.usedDiskTargets(d.getXMLDesc(0));
    } finally {
      freeDomainQuietly(d);
    }
  }

  /**
   * Grows one disk of a running domain, through QEMU.
   *
   * <p>This is the whole point of doing it live: QEMU resizes the image and raises a
   * capacity-change event on the virtio device, so the guest kernel sees the new size straight away
   * and the disk does not have to wait for a power cycle. Nothing is written to the domain XML,
   * which does not record a disk's size in the first place.
   *
   * <p>The size is passed in bytes, which libvirt only accepts with {@code
   * VIR_DOMAIN_BLOCK_RESIZE_BYTES}. Without that flag the argument is read as KiB — the same number
   * would grow the disk 1024-fold — and libvirt-java hands both straight to the C API without
   * touching either, so the flag is not optional.
   */
  void blockResize(String name, String target, long targetGiB) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    try {
      long bytes = targetGiB * BYTES_PER_GIB;
      log.debug("Domain '{}': resizing '{}' to {} bytes ({} GiB)", name, target, bytes, targetGiB);
      d.blockResize(target, bytes, VIR_DOMAIN_BLOCK_RESIZE_BYTES);
    } catch (LibvirtException e) {
      log.debug("Domain '{}': failed to resize '{}' to {} GiB", name, target, targetGiB, e);
      throw e;
    } finally {
      freeDomainQuietly(d);
    }
  }

  void updateAutostart(String name, boolean autostart) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    try {
      setAutostart(d, name, autostart);
    } finally {
      freeDomainQuietly(d);
    }
  }

  private static void setAutostart(Domain d, String name, boolean autostart)
      throws LibvirtException {
    try {
      log.debug("Domain '{}': setting autostart to {}", name, autostart);
      d.setAutostart(autostart);
    } catch (LibvirtException e) {
      log.debug("Failed to set autostart for domain '{}'", name, e);
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
      log.debug("Failed to destroy domain '{}'", name, e);
      throw e;
    } finally {
      freeDomainQuietly(d);
    }
  }

  /**
   * Whether a domain of this name is defined. Any answer but libvirt's "no such domain" is an
   * error, not a no: the caller uses it to decide whether volumes are free to delete.
   */
  boolean isDefined(String name) throws LibvirtException {
    Domain d;
    try {
      d = connect.domainLookupByName(name);
    } catch (LibvirtException e) {
      if (e.getError() != null && e.getError().getCode() == Error.ErrorNumber.VIR_ERR_NO_DOMAIN)
        return false;
      throw e;
    }
    freeDomainQuietly(d);
    return true;
  }

  void undefineDomain(String name) throws LibvirtException {
    log.debug("Undefining domain '{}'...", name);
    Domain d = connect.domainLookupByName(name);
    try {
      d.undefine();
      log.debug("Domain '{}' undefined successfully", name);
    } catch (LibvirtException e) {
      log.debug("Failed to undefine domain '{}'", name, e);
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
        actual.add(
            XmlUtil.getShortState(d.getXMLDesc(Domain.XMLFlags.INACTIVE))
                .withRuntime(d.isActive() == 1, d.getAutostart()));
      return actual;
    } finally {
      for (Domain d : domains) freeDomainQuietly(d);
    }
  }

  boolean joinDomain(String name, String metadata) {
    try {
      writeMetadata(name, metadata);
      return true;
    } catch (LibvirtException e) {
      log.debug("Failed to join domain '{}'", name, e);
      return false;
    }
  }

  /**
   * Replaces the domain's {@code <mnem:mnemosyne>} element, in the live definition too if running.
   */
  void writeMetadata(String name, String metadata) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    try {
      int flags =
          (d.isActive() == 1)
              ? Domain.ModificationImpact.CONFIG | Domain.ModificationImpact.LIVE
              : Domain.ModificationImpact.CONFIG;
      d.setMetadata(Domain.MetadataType.ELEMENT, metadata, "mnem", XmlUtil.MNEM_NS, flags);
    } finally {
      freeDomainQuietly(d);
    }
  }

  List<String> getDiskPaths(String name) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    final List<String> diskPaths;
    try {
      String domainXml = d.getXMLDesc(0);
      diskPaths = XmlUtil.diskPaths(domainXml);
    } catch (LibvirtException e) {
      log.debug("Failed to get XML description for domain '{}'", name, e);
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

  boolean updateRam(String name, long ramMiB) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    try {
      log.debug("Domain '{}': setting memory to {} MiB (config only)", name, ramMiB);
      // libvirt-java has no setMemoryFlags, so the persistent config is redefined instead.
      // INACTIVE is the stored config, so a running domain keeps its live memory untouched.
      String patched = XmlUtil.withMemory(d.getXMLDesc(Domain.XMLFlags.INACTIVE), ramMiB);
      freeDomainQuietly(connect.domainDefineXML(patched));
      log.debug("Domain '{}': ram updated (applies after restart)", name);
      return true;
    } catch (LibvirtException e) {
      log.debug("Failed to update ram for domain '{}'", name, e);
      throw e;
    } finally {
      freeDomainQuietly(d);
    }
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
      log.debug("Failed to update cpu for domain '{}'", name, e);
      throw e;
    } finally {
      freeDomainQuietly(d);
    }
  }
}
