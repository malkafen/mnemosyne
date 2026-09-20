package com.mnemosyne.app.model;

import java.util.List;
import java.util.Optional;

/**
 * Read-only snapshot ("passport") of a libvirt domain as reported by libvirt.
 *
 * <p>{@code active} and {@code autostart} are runtime facts that the domain XML does not carry;
 * they are read from the domain handle and attached with {@link #withRuntime(boolean, boolean)}.
 * Disk capacities are not in the XML either and are filled in the same way, with {@link
 * #withDisks(List)}.
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
   *
   * <p>{@code capacityGiB} is the volume's virtual size. It is the one field here that the domain
   * XML cannot answer — it costs a volume lookup on the host — so it arrives later than the rest
   * and is {@link #CAPACITY_UNKNOWN} until someone fills it in. Unknown is not zero: a disk whose
   * size could not be read is left out of every size decision rather than being treated as empty
   * and grown to the inventory's figure.
   */
  public record Disk(String target, String path, String serial, long capacityGiB) {

    /** A disk as the XML describes it, before its capacity has been read from the host. */
    public Disk(String target, String path, String serial) {
      this(target, path, serial, CAPACITY_UNKNOWN);
    }

    /** The volume's name inside its pool, which is what the inventory can predict. */
    public String volName() {
      if (path == null || path.isBlank()) return null;
      int slash = path.lastIndexOf('/');
      return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The same disk with its capacity filled in. */
    public Disk withCapacity(long capacityGiB) {
      return new Disk(target, path, serial, capacityGiB);
    }

    public boolean capacityKnown() {
      return capacityGiB > CAPACITY_UNKNOWN;
    }
  }

  /** Capacity of a disk nobody has measured, or whose volume could not be read. */
  public static final long CAPACITY_UNKNOWN = -1;

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

  /**
   * The domain's root disk: the first file-backed one the XML lists.
   *
   * <p>Position is the only thing that identifies it. A VM adopted with {@code --join} can have its
   * root volume named anything at all, so neither the name the inventory would give it nor a serial
   * is any use here — which is also why everything else about disks skips the first one.
   */
  public Optional<Disk> rootDisk() {
    return disks.isEmpty() ? Optional.empty() : Optional.of(disks.get(0));
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

  /** The same snapshot carrying a different disk list, used to attach the capacities. */
  public DomainState withDisks(List<Disk> disks) {
    return new DomainState(
        name, cpu, ram, serverId, specVersion, managedBy, List.copyOf(disks), active, autostart);
  }
}
