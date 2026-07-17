package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.exception.*;
import java.util.List;
import org.libvirt.Connect;
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

  String provisionVolume(String domainName, String poolName, String volXml)
      throws LibvirtException {
    log.debug("Provisioning volume for domain '{}' in storage pool '{}'", domainName, poolName);
    StoragePool pool = lookupPool(poolName);

    return null;
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
