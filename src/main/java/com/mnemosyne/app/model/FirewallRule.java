package com.mnemosyne.app.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.mnemosyne.app.utils.Ipv4;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * One line of a firewall: what to do with traffic in one direction, to or from which addresses, on
 * which protocol and ports. Every field but {@code action} and {@code direction} narrows the match;
 * left out, it matches everything.
 *
 * <p>Directions are the guest's: {@code out} is traffic the VM sends (and the connections it
 * opens), {@code in} is traffic sent to it. {@code remote} is always the other end — the
 * destination of {@code out}, the source of {@code in} — and {@code ports} are always the
 * destination port: the remote service for {@code out}, the VM's own listening port for {@code in}.
 */
@Getter
@Setter
public class FirewallRule {

  @NotBlank(message = "Firewall rule action is required (accept, drop or reject)")
  @Pattern(
      regexp = "^(accept|drop|reject)$",
      message = "Firewall rule action must be accept, drop or reject")
  private String action;

  @NotBlank(message = "Firewall rule direction is required (out or in)")
  @Pattern(regexp = "^(out|in)$", message = "Firewall rule direction must be out or in")
  private String direction;

  @Pattern(
      regexp = "^(tcp|udp|icmp|all)$",
      message = "Firewall rule protocol must be tcp, udp, icmp or all")
  private String protocol = "all";

  /** Addresses or CIDR blocks of the other end; one value or a list. Empty means any. */
  @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
  @Size(max = 64, message = "At most 64 remote addresses per firewall rule")
  private List<
          @Pattern(
              regexp = Ipv4.ADDRESS_OR_CIDR_PATTERN,
              message = "Firewall rule remote must be an IPv4 address or CIDR (e.g. 10.0.0.0/8)")
          String>
      remote = List.of();

  /** Destination ports, {@code 443} or {@code 8000-8100}; one value or a list. Empty means any. */
  @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
  @Size(max = 64, message = "At most 64 port entries per firewall rule")
  private List<
          @Pattern(
              regexp = "^\\d{1,5}(\\s*-\\s*\\d{1,5})?$",
              message = "Firewall rule port must be a number or a range (e.g. 443 or 8000-8100)")
          String>
      ports = List.of();

  /** Free text, shown in the plan; also written into the iptables rule's comment. */
  @Size(max = 200, message = "Firewall rule comment must be at most 200 characters")
  private String comment;

  public void setRemote(List<String> remote) {
    this.remote = remote == null ? List.of() : remote;
  }

  public void setPorts(List<String> ports) {
    this.ports = ports == null ? List.of() : ports;
  }

  public void setProtocol(String protocol) {
    this.protocol = protocol == null ? "all" : protocol;
  }

  @AssertTrue(message = "Firewall rule ports need protocol tcp or udp")
  public boolean isPortsWithProtocol() {
    return ports.isEmpty() || "tcp".equals(protocol) || "udp".equals(protocol);
  }

  @AssertTrue(message = "Firewall rule port must be 1-65535 and a range must not run backwards")
  public boolean isPortsInRange() {
    for (String p : ports) {
      int[] r = portRange(p);
      if (r == null) continue; // the pattern reports it
      if (r[0] < 1 || r[1] > 65535 || r[0] > r[1]) return false;
    }
    return true;
  }

  /** {@code [start, end]} of one {@code ports} entry, or null when it does not parse. */
  static int[] portRange(String p) {
    if (p == null || !p.matches("^\\d{1,5}(\\s*-\\s*\\d{1,5})?$")) return null;
    String[] parts = p.split("-");
    int start = Integer.parseInt(parts[0].trim());
    int end = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : start;
    return new int[] {start, end};
  }

  /** One line for the plan, e.g. {@code accept out tcp to 10.0.0.5 port 443}. */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append(action).append(' ').append(direction).append(' ').append(protocol);
    if (!remote.isEmpty())
      sb.append("out".equals(direction) ? " to " : " from ").append(String.join(",", remote));
    if (!ports.isEmpty()) sb.append(" port ").append(String.join(",", ports).replace(" ", ""));
    if (comment != null && !comment.isBlank()) sb.append("  # ").append(comment.trim());
    return sb.toString();
  }
}
