package com.mnemosyne.app.model;

import java.util.List;
import java.util.Optional;

public record DomainState(
    String name,
    int cpu,
    long ram,
    String serverId,
    String specVersion,
    String managedBy,
    List<Disk> disks,
    boolean active,
    boolean autostart,
    List<String> detachedOwned,
    List<String> reusedPaths,
    InitMarker init) {

  public DomainState(
      String name,
      int cpu,
      long ram,
      String serverId,
      String specVersion,
      String managedBy,
      List<Disk> disks,
      boolean active,
      boolean autostart,
      List<String> detachedOwned,
      List<String> reusedPaths) {
    this(
        name,
        cpu,
        ram,
        serverId,
        specVersion,
        managedBy,
        disks,
        active,
        autostart,
        detachedOwned,
        reusedPaths,
        InitMarker.ASSUMED_FINISHED);
  }

  public DomainState(
      String name,
      int cpu,
      long ram,
      String serverId,
      String specVersion,
      String managedBy,
      List<Disk> disks,
      boolean active,
      boolean autostart,
      List<String> detachedOwned) {
    this(
        name,
        cpu,
        ram,
        serverId,
        specVersion,
        managedBy,
        disks,
        active,
        autostart,
        detachedOwned,
        List.of());
  }

  public DomainState(
      String name,
      int cpu,
      long ram,
      String serverId,
      String specVersion,
      String managedBy,
      List<Disk> disks,
      boolean active,
      boolean autostart) {
    this(name, cpu, ram, serverId, specVersion, managedBy, disks, active, autostart, List.of());
  }

  public record Disk(String target, String path, String serial, long capacityGiB, boolean owned) {

    public Disk(String target, String path, String serial) {
      this(target, path, serial, CAPACITY_UNKNOWN, false);
    }

    public Disk(String target, String path, String serial, long capacityGiB) {
      this(target, path, serial, capacityGiB, false);
    }

    public String volName() {
      if (path == null || path.isBlank()) return null;
      int slash = path.lastIndexOf('/');
      return slash < 0 ? path : path.substring(slash + 1);
    }

    public Disk withCapacity(long capacityGiB) {
      return new Disk(target, path, serial, capacityGiB, owned);
    }

    public Disk markOwned() {
      return new Disk(target, path, serial, capacityGiB, true);
    }

    public boolean capacityKnown() {
      return capacityGiB > CAPACITY_UNKNOWN;
    }
  }

  public static final long CAPACITY_UNKNOWN = -1;

  public boolean managed() {
    return "mnemosyne".equals(managedBy);
  }

  public Optional<Disk> diskFor(ExtraDisk disk, String vmName) {
    String volName = disk.volName(vmName);
    return disks.stream()
        .filter(d -> disk.getName().equals(d.serial()) || volName.equals(d.volName()))
        .findFirst();
  }

  public Optional<Disk> rootDisk() {
    return disks.isEmpty() ? Optional.empty() : Optional.of(disks.get(0));
  }

  public List<String> diskPaths() {
    return disks.stream().map(Disk::path).toList();
  }

  public List<String> ownedPaths() {
    return disks.stream().filter(Disk::owned).map(Disk::path).toList();
  }

  public boolean reused(String path) {
    return reusedPaths.contains(path);
  }

  public List<String> detachedReused() {
    List<String> attached = diskPaths();
    return reusedPaths.stream().filter(p -> !attached.contains(p)).toList();
  }

  public DomainState withRuntime(boolean active, boolean autostart) {
    return new DomainState(
        name,
        cpu,
        ram,
        serverId,
        specVersion,
        managedBy,
        disks,
        active,
        autostart,
        detachedOwned,
        reusedPaths,
        init);
  }

  public DomainState withDisks(List<Disk> disks) {
    return new DomainState(
        name,
        cpu,
        ram,
        serverId,
        specVersion,
        managedBy,
        List.copyOf(disks),
        active,
        autostart,
        detachedOwned,
        reusedPaths,
        init);
  }
}
