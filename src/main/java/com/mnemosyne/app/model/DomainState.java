package com.mnemosyne.app.model;

import java.util.List;

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
    List<String> disks,
    boolean active,
    boolean autostart) {

  /** Whether this domain carries mnemosyne metadata (created or patched by us). */
  public boolean managed() {
    return "mnemosyne".equals(managedBy);
  }

  /** The same snapshot with the power state and autostart flag filled in. */
  public DomainState withRuntime(boolean active, boolean autostart) {
    return new DomainState(
        name, cpu, ram, serverId, specVersion, managedBy, disks, active, autostart);
  }
}
