package com.mnemosyne.app.model;

/** Read-only snapshot ("passport") of a libvirt domain as reported by libvirt. */
public record DomainState(
    String name, String serverId, String specHash, String specVersion, String managedBy) {

  /** Whether this domain carries mnemosyne metadata (created or patched by us). */
  public boolean managed() {
    return "mnemosyne".equals(managedBy);
  }
}
