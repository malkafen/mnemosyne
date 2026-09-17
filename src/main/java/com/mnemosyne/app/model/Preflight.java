package com.mnemosyne.app.model;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;

/**
 * What a group needs before a VM can be created: the storage pool and base image the new disk is
 * cloned from, the libvirt network the domain XML points at, and the template files rendered on
 * this machine. Collected while the plan is built, so a missing pool stops the run before the first
 * VM instead of halfway through a batch.
 */
@Getter
public final class Preflight {

  /** One unmet prerequisite: what is missing, and why it fails. */
  public record Problem(String resource, String reason) {}

  /**
   * A pool, an image or a template is normally shared by every VM in the group, so a resource is
   * reported once, with the servers that need it: the operator fixes one thing, not one thing per
   * VM, but still sees which VMs are held up by it.
   */
  private final Map<Problem, Set<String>> problems = new LinkedHashMap<>();

  public void add(Problem problem, Collection<String> serverIds) {
    problems.computeIfAbsent(problem, p -> new LinkedHashSet<>()).addAll(serverIds);
  }

  public void add(Problem problem, String serverId) {
    add(problem, List.of(serverId));
  }

  public boolean ok() {
    return problems.isEmpty();
  }

  /**
   * The templates are read from the machine running Mnemosyne, not from the hypervisor, so this
   * costs nothing over the wire. Only readability is checked here; a template that parses but has
   * no {@code <disk>} to fill in is still reported by the builders at creation time.
   */
  public void checkTemplates(Server server) {
    Templates templates = server.getTemplates();
    if (templates == null) {
      add(new Problem("templates", "not configured"), server.getId());
      return;
    }
    for (String path : templates.paths()) {
      File file = new File(path);
      if (!file.isFile() || !file.canRead()) {
        add(new Problem("template '" + path + "'", "not readable on this machine"), server.getId());
      }
    }
  }

  /**
   * Why this server cannot be created, in the wording the plan prints next to it. Empty for a
   * server whose prerequisites are all in place.
   */
  public List<String> blockers(String serverId) {
    return problems.entrySet().stream()
        .filter(e -> e.getValue().contains(serverId))
        .map(e -> e.getKey().resource() + " " + e.getKey().reason())
        .toList();
  }
}
