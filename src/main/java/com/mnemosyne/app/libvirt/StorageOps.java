package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.exception.*;
import java.util.List;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.libvirt.StorageVol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StorageOps {

  private static final Logger log = LoggerFactory.getLogger(StorageOps.class);
  private final Connect connect;

  StorageOps(Connect connect) {
    this.connect = connect;
  }

  void deleteVolumes(List<StorageVol> volumes, String name) throws VolumeCleanupException {
    if (volumes == null || volumes.isEmpty()) {
      log.debug("No volumes to delete for domain '{}'", name);
      return;
    }
    int deleted = 0;
    int failed = 0;
    for (StorageVol vol : volumes) {
      if (vol == null) continue;
      try {
        vol.delete(0);
        deleted++;
        log.debug("Deleted a volume of domain '{}'", name);
      } catch (LibvirtException e) {
        log.error("Failed to delete a volume of domain '{}'; continuing with the rest", name, e);
        failed++;
      } finally {
        freeVolumeQuietly(vol);
      }
    }
    if (failed > 0) {
      throw new VolumeCleanupException(
          String.format(
              "Failed to delete %d of %d volumes for domain '%s'", failed, deleted + failed, name));
    } else {
      log.debug("Volume cleanup for domain '{}': {} deleted", name, deleted);
    }
  }

  void freeVolumesQuietly(List<StorageVol> volumes) {
    if (volumes == null || volumes.isEmpty()) {
      return;
    }
    for (StorageVol vol : volumes) {
      if (vol == null) continue;
      freeVolumeQuietly(vol);
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
