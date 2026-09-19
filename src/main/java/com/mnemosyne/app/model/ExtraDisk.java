package com.mnemosyne.app.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * One additional disk of a VM: a blank qcow2 volume, attached next to the root disk and handed to
 * the guest unpartitioned and unformatted. Mnemosyne creates it and never touches its contents —
 * partitioning, filesystems and mounts are the administrator's.
 *
 * <p>Extra disks are add-only. One that disappears from the inventory is reported and left alone,
 * because a data disk cannot be re-created from a template the way the root disk can.
 */
@Getter
@Setter
public class ExtraDisk {

  /**
   * The disk's name in the inventory. It ends up in two places: the volume file name, and the
   * domain XML's {@code <serial>}, which the guest exposes as {@code
   * /dev/disk/by-id/virtio-<name>}. That link is the stable way to address the disk inside the
   * guest, and it is why the name is restricted to what udev keeps intact and cut off at 20
   * characters, where the by-id link for virtio truncates.
   */
  @NotBlank(message = "Extra disk name is required")
  @Pattern(
      regexp = "^[a-z0-9][a-z0-9-]{0,19}$",
      message =
          "Extra disk name must be 1-20 characters of a-z, 0-9 and '-', starting with a letter or"
              + " digit (it becomes /dev/disk/by-id/virtio-<name> in the guest)")
  private String name;

  /**
   * Size in GiB. Unlike the root disk there is no lower bound worth enforcing beyond 1 GiB: nothing
   * is cloned into this volume, so no base image has to fit in it.
   */
  @Min(value = 1, message = "Extra disk size must be at least 1 GiB")
  @Max(value = 65536, message = "Extra disk size must not exceed 65536 GiB (64 TiB)")
  private int size;

  /**
   * Storage pool for this disk. Left out, it is filled in with the server's own {@code pool} when
   * the inventory is loaded, so a VM's disks stay together unless one is deliberately moved.
   */
  @NotBlank(message = "Extra disk pool name is required")
  private String pool;

  /**
   * The volume's name inside the pool. A pool's namespace is flat and shared by every VM on the
   * host, so the VM's name is part of it: {@code data} alone would collide the moment a second VM
   * wanted a disk called {@code data}.
   */
  public String volName(String vmName) {
    return vmName + "-" + name + ".qcow2";
  }
}
