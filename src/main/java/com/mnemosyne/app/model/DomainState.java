package com.mnemosyne.app.model;

import java.util.List;
import java.util.Optional;

/**
 * Read-only snapshot ("passport") of a libvirt domain as reported by libvirt.
 *
 * <p>{@code active} and {@code autostart} are runtime facts that the domain XML does not carry;
 * they are read from the domain handle and attached with {@link #withRuntime(boolean, boolean)}.
 */
public record DomainState(
    String name,
    int cpu,
    long ram,
    String serverId,
    String specVersion,
    String managedBy,
    List<Disk> disks,
    boolean active,
    boolean autostart) {

  /**
   * One file-backed disk of the domain, in the order the domain XML lists it.
   *
   * <p>{@code target} is the guest-visible device name ({@code vda}), {@code serial} is what
   * Mnemosyne writes for an extra disk and is null for anything else. Both are needed to reconcile
   * disks without guessing: the target says which letters are taken, and the volume file name says
   * which inventory entry a disk belongs to.
   */
  public record Disk(String target, String path, String serial) {

    /** The volume's name inside its pool, which is what the inventory can predict. */
    public String volName() {
      if (path == null || path.isBlank()) return null;
      int slash = path.lastIndexOf('/');
      return slash < 0 ? path : path.substring(slash + 1);
    }
  }

  /** Whether this domain carries mnemosyne metadata (created or patched by us). */
  public boolean managed() {
    return "mnemosyne".equals(managedBy);
  }

  /**
   * The disk that answers to one of the inventory's extra disks, or empty when the domain does not
   * have it.
   *
   * <p>Matched on the {@code <serial>} first, and only then on the volume file name. The serial is
   * the disk's identity: renaming a VM changes the name its next volume would get, and matching on
   * the file name alone would make every data disk of a renamed VM look missing — the VM would then
   * be handed a second, empty set of disks while the originals stayed attached with the data on
   * them. The file name remains as a fallback for a disk that carries no serial.
   */
  public Optional<Disk> diskFor(ExtraDisk disk, String vmName) {
    String volName = disk.volName(vmName);
    return disks.stream()
        .filter(d -> disk.getName().equals(d.serial()) || volName.equals(d.volName()))
        .findFirst();
  }

  /** Paths of every file-backed disk, which is what volume deletion works on. */
  public List<String> diskPaths() {
    return disks.stream().map(Disk::path).toList();
  }

  /** The same snapshot with the power state and autostart flag filled in. */
  public DomainState withRuntime(boolean active, boolean autostart) {
    return new DomainState(
        name, cpu, ram, serverId, specVersion, managedBy, disks, active, autostart);
  }
}
