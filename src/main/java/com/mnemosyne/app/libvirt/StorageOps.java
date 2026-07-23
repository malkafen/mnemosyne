package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.exception.*;
import java.util.List;
import java.util.Optional;
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
    private static final long GIB = 1024L * 1024 * 1024;

    VolumeSpec {
      targetCapacity = targetCapacity * GIB;
    }
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
        log.error(
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

  String provisionVolume(VolumeSpec spec) throws LibvirtException {
    log.debug(
        "Provisioning volume for domain '{}' in storage pool '{}'",
        spec.volumeName(),
        spec.poolName());
    StoragePool pool = lookupPool(spec.poolName());
    try {
      Optional<String> path = findExistingVolumePath(pool, spec.volumeName());
      if (path.isPresent()) return path.get();
      return newVolume(pool, spec);
    } finally {
      freePoolQuietly(pool);
    }
  }

  private StoragePool lookupPool(String name) throws LibvirtException {
    StoragePool pool;
    try {
      pool = connect.storagePoolLookupByName(name);
    } catch (LibvirtException e) {
      log.error("Storage pool '{}' not found: {}", name, e.getMessage());
      throw e;
    }
    try {
      pool.refresh(0);
    } catch (LibvirtException e) {
      log.error("Storage pool '{}' found, but refresh failed: {}", name, e.getMessage());
      freePoolQuietly(pool);
      throw e;
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
      log.error("Failed to lookup volume '{}': {}", domainName, e.getMessage());
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
      log.error(
          "Failed to resize volume '{}' to {} bytes, rolling back",
          spec.volumeName(),
          spec.targetCapacity());
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
      log.error(
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
