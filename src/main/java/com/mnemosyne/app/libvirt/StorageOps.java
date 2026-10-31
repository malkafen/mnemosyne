package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.exception.*;
import com.mnemosyne.app.model.Preflight.Problem;
import com.mnemosyne.app.utils.XmlUtil;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.libvirt.Connect;
import org.libvirt.Error;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;
import org.libvirt.StorageVol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StorageOps {

  private static final Logger log = LoggerFactory.getLogger(StorageOps.class);
  private final Connect connect;

  StorageOps(Connect connect) {
    this.connect = connect;
  }

  record VolumeSpec(
      String volumeName, String poolName, String volXml, String cloneSource, long targetCapacity) {
    static final long GIB = 1024L * 1024 * 1024;

    VolumeSpec {
      targetCapacity = targetCapacity * GIB;
    }

    static VolumeSpec blank(String volumeName, String poolName, String volXml) {
      return new VolumeSpec(volumeName, poolName, volXml, null, 0);
    }
  }

  record Provisioned(String path, boolean reused) {}

  record PoolCheck(Optional<Problem> problem, String targetPath) {
    static PoolCheck broken(Problem problem) {
      return new PoolCheck(Optional.of(problem), null);
    }
  }

  PoolCheck checkPool(String poolName) {
    StoragePool pool;
    try {
      pool = connect.storagePoolLookupByName(poolName);
    } catch (LibvirtException e) {
      log.debug("Preflight: storage pool '{}' not found: {}", poolName, e.getMessage(), e);
      return PoolCheck.broken(new Problem("pool '" + poolName + "'", "not found on the host"));
    }
    try {
      if (pool.isActive() != 1) {
        return PoolCheck.broken(
            new Problem("pool '" + poolName + "'", "not running; start it with virsh pool-start"));
      }
      return new PoolCheck(Optional.empty(), poolTargetPath(pool, poolName));
    } catch (LibvirtException e) {
      log.debug("Preflight: checking pool '{}' failed: {}", poolName, e.getMessage(), e);
      return PoolCheck.broken(new Problem("pool '" + poolName + "'", cause(e)));
    } finally {
      freePoolQuietly(pool);
    }
  }

  private String poolTargetPath(StoragePool pool, String poolName) {
    try {
      String path = XmlUtil.poolTargetPath(pool.getXMLDesc(0));
      if (path == null) log.debug("Pool '{}' declares no <target><path>", poolName);
      return path;
    } catch (LibvirtException | RuntimeException e) {
      log.debug("Could not read the target path of pool '{}': {}", poolName, e.getMessage(), e);
      return null;
    }
  }

  Optional<Problem> checkBaseImage(String poolName, String volName) {
    StoragePool pool;
    try {
      pool = connect.storagePoolLookupByName(poolName);
    } catch (LibvirtException e) {
      log.debug("Preflight: storage pool '{}' not found: {}", poolName, e.getMessage(), e);
      return Optional.of(new Problem("pool '" + poolName + "'", "not found on the host"));
    }
    try {
      if (pool.isActive() != 1) {
        return Optional.of(
            new Problem("pool '" + poolName + "'", "not running; start it with virsh pool-start"));
      }
      if (findExistingVolumePath(pool, volName).isPresent()) return Optional.empty();

      log.debug("Preflight: base image '{}' not in pool '{}' cache, refreshing", volName, poolName);
      pool.refresh(0);
      if (findExistingVolumePath(pool, volName).isPresent()) return Optional.empty();

      return Optional.of(
          new Problem("image '" + volName + "'", "not found in pool '" + poolName + "'"));
    } catch (LibvirtException e) {
      log.debug("Preflight: checking pool '{}' failed: {}", poolName, e.getMessage(), e);
      return Optional.of(new Problem("pool '" + poolName + "'", cause(e)));
    } finally {
      freePoolQuietly(pool);
    }
  }

  private static String cause(LibvirtException e) {
    String msg = e.getMessage();
    return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg.trim();
  }

  void deleteVolumes(List<String> diskPaths, String domainName) throws VolumeCleanupException {
    if (diskPaths == null || diskPaths.isEmpty()) {
      log.debug("No volumes to delete for domain '{}'", domainName);
      return;
    }
    int deleted = 0;
    int failed = 0;

    for (String path : diskPaths) {
      StorageVol vol = null;
      try {
        vol = connect.storageVolLookupByPath(path);
        if (vol == null) continue;
        vol.delete(0);
        deleted++;
        log.debug("Deleted a volume of domain '{}'", domainName);
      } catch (LibvirtException e) {
        log.debug(
            "Failed to delete a volume of domain '{}'; continuing with the rest", domainName, e);
        failed++;
      } finally {
        if (vol != null) freeVolumeQuietly(vol);
      }
    }
    if (failed > 0) {
      throw new VolumeCleanupException(
          String.format(
              "Failed to delete %d of %d volumes for domain '%s'",
              failed, deleted + failed, domainName));
    } else {
      log.debug("Volume cleanup for domain '{}': {} deleted", domainName, deleted);
    }
  }

  Provisioned provisionVolume(VolumeSpec spec) throws LibvirtException {
    log.debug(
        "Provisioning volume for domain '{}' in storage pool '{}'",
        spec.volumeName(),
        spec.poolName());
    StoragePool pool = lookupPool(spec.poolName());
    try {
      Optional<String> path = findExistingVolumePath(pool, spec.volumeName());
      if (path.isPresent()) {
        log.debug("Volume '{}' already exists, reusing it as is", spec.volumeName());
        return new Provisioned(path.get(), true);
      }
      return new Provisioned(newVolume(pool, spec), false);
    } finally {
      freePoolQuietly(pool);
    }
  }

  Provisioned provisionBlankVolume(VolumeSpec spec) throws LibvirtException {
    log.debug(
        "Provisioning blank volume '{}' in storage pool '{}'", spec.volumeName(), spec.poolName());
    StoragePool pool = lookupPool(spec.poolName());
    StorageVol vol = null;
    try {
      Optional<String> existing = findExistingVolumePath(pool, spec.volumeName());
      if (existing.isPresent()) {
        log.debug("Volume '{}' already exists, reusing it as is", spec.volumeName());
        return new Provisioned(existing.get(), true);
      }
      log.trace("Volume XML for '{}':\n{}", spec.volumeName(), spec.volXml());
      vol = pool.storageVolCreateXML(spec.volXml(), 0);
      return new Provisioned(vol.getPath(), false);
    } finally {
      if (vol != null) freeVolumeQuietly(vol);
      freePoolQuietly(pool);
    }
  }

  OptionalLong capacityGiB(String path) {
    StorageVol vol = null;
    try {
      vol = connect.storageVolLookupByPath(path);
      return OptionalLong.of(vol.getInfo().capacity / VolumeSpec.GIB);
    } catch (LibvirtException e) {
      log.debug("Could not read the capacity of volume '{}': {}", path, e.getMessage(), e);
      return OptionalLong.empty();
    } finally {
      if (vol != null) freeVolumeQuietly(vol);
    }
  }

  void growVolume(String path, long targetGiB) throws LibvirtException {
    StorageVol vol = connect.storageVolLookupByPath(path);
    try {
      long bytes = targetGiB * VolumeSpec.GIB;
      log.debug("Resizing volume '{}' to {} bytes ({} GiB)", path, bytes, targetGiB);
      vol.resize(bytes, 0);
    } catch (LibvirtException e) {
      log.debug("Failed to resize volume '{}' to {} GiB", path, targetGiB, e);
      throw e;
    } finally {
      freeVolumeQuietly(vol);
    }
  }

  private StoragePool lookupPool(String name) throws LibvirtException {
    StoragePool pool;
    try {
      pool = connect.storagePoolLookupByName(name);
    } catch (LibvirtException e) {
      log.debug("Storage pool '{}' not found: {}", name, e.getMessage(), e);
      throw e;
    }
    try {
      pool.refresh(0);
    } catch (LibvirtException e) {
      log.debug("Storage pool '{}' found, refresh skipped: {}", name, e.getMessage(), e);
    }
    log.debug("Storage pool '{}' ready", name);
    return pool;
  }

  private Optional<String> findExistingVolumePath(StoragePool pool, String domainName)
      throws LibvirtException {
    log.debug("Fetch a storage volume '{}'", domainName);
    StorageVol vol = null;
    try {
      vol = pool.storageVolLookupByName(domainName);
      String path = vol.getPath();
      log.debug("Volume '{}' found (path: {})", domainName, path);
      return Optional.of(path);
    } catch (LibvirtException e) {
      if (e.getError().getCode() == Error.ErrorNumber.VIR_ERR_NO_STORAGE_VOL) {
        log.debug("Volume '{}' doesn't exist", domainName);
        return Optional.empty();
      }
      log.debug("Failed to lookup volume '{}': {}", domainName, e.getMessage(), e);
      throw e;
    } finally {
      if (vol != null) freeVolumeQuietly(vol);
    }
  }

  private String newVolume(StoragePool pool, VolumeSpec spec) throws LibvirtException {
    log.trace(
        "Volume XML for '{}' (pool '{}'):\n{}", spec.volumeName(), spec.poolName(), spec.volXml());
    log.debug("Looking up clone source '{}' in pool '{}'", spec.cloneSource(), spec.poolName());
    StorageVol cloneVol = pool.storageVolLookupByName(spec.cloneSource());
    StorageVol newVol = null;

    log.debug(
        "Cloning '{}' -> '{}' in pool '{}'",
        spec.cloneSource(),
        spec.volumeName(),
        spec.poolName());
    try {
      newVol = pool.storageVolCreateXMLFrom(spec.volXml(), cloneVol, 0);
      resizeVolume(newVol, spec);
      return newVol.getPath();
    } finally {
      freeVolumeQuietly(cloneVol);
      if (newVol != null) freeVolumeQuietly(newVol);
    }
  }

  private void resizeVolume(StorageVol vol, VolumeSpec spec) throws LibvirtException {
    log.debug("Resizing volume '{}' to {} bytes", spec.volumeName(), spec.targetCapacity());
    try {
      vol.resize(spec.targetCapacity(), 0);
    } catch (LibvirtException e) {
      log.debug(
          "Failed to resize volume '{}' to {} bytes, rolling back",
          spec.volumeName(),
          spec.targetCapacity(),
          e);
      rollbackVolume(vol, spec);
      throw e;
    }
  }

  private void rollbackVolume(StorageVol vol, VolumeSpec spec) {
    try {
      log.debug("Rolling back: deleting volume '{}'", spec.volumeName());
      vol.delete(0);
      log.debug("Rollback successful: volume '{}' deleted", spec.volumeName());
    } catch (LibvirtException e) {
      log.debug(
          "Rollback failed: could not delete volume '{}': {}",
          spec.volumeName(),
          e.getMessage(),
          e);
    }
  }

  private void freePoolQuietly(StoragePool pool) {
    try {
      pool.free();
    } catch (LibvirtException e) {
      log.debug("Failed to free StoragePool handle; ignoring", e);
    }
  }

  private void freeVolumeQuietly(StorageVol vol) {
    try {
      vol.free();
    } catch (LibvirtException e) {
      log.debug("Failed to free StorageVol handle (domain cleanup); ignoring", e);
    }
  }
}
