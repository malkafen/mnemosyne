package com.mnemosyne.app.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * Disk facts that are not in the domain XML, collected from the host between the plan and its
 * printing — currently the capacity of the extra disks a VM already has.
 *
 * <p>It exists because a volume's size costs a lookup per disk, which {@link Plan} has no
 * connection to make, and because none of it is ever acted on. Growing a disk is a separate
 * operation with its own risks, and shrinking one destroys whatever sits past the new end, so a
 * size that does not match the inventory is reported and left exactly as it is. Reporting it is
 * still worth the lookup: an inventory that says 50 and a disk that is 40 would otherwise look like
 * agreement.
 */
@Getter
public final class DiskAudit {

  /** Informational lines per server id, in the order they were found. */
  private final Map<String, List<String>> notes = new LinkedHashMap<>();

  public void add(String serverId, String note) {
    notes.computeIfAbsent(serverId, id -> new ArrayList<>()).add(note);
  }

  public List<String> notes(String serverId) {
    return notes.getOrDefault(serverId, List.of());
  }

  public boolean isEmpty() {
    return notes.isEmpty();
  }
}
