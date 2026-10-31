package com.mnemosyne.app.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mnemosyne.app.utils.Ipv4;
import com.mnemosyne.app.utils.XmlUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * A named set of firewall rules under the group's {@code firewalls} map, applied to every VM that
 * names it in {@code firewall}.
 *
 * <p>It becomes one libvirt network filter (nwfilter) called {@code mnem-<name>}, referenced from
 * the VM's interface. The rules are enforced on the hypervisor, outside the guest: nothing running
 * as root inside the VM can turn them off. Redefining the filter updates every running VM that uses
 * it at once, so a rule change needs no restart.
 *
 * <p>The filter is built in a fixed order, lowest nwfilter priority first:
 *
 * <ol>
 *   <li>{@code clean-traffic} when {@code antiSpoofing} is on — the VM can send only with its own
 *       MAC and its inventory {@code ip}, and cannot spoof ARP;
 *   <li>replies to connections already accepted ({@code ESTABLISHED}/{@code RELATED}), so a rule
 *       only ever has to describe who opens a connection;
 *   <li>DHCP, when {@code allowDhcp} is on, so the VM gets its address on the first boot;
 *   <li>the VM's own cloud-init seed server ({@code metaUrl}) when {@code allowSeed} is on —
 *       without it a new VM behind a {@code default: drop} firewall is never configured;
 *   <li>the {@code rules}, in the order they are written: the first that matches decides;
 *   <li>the {@code default}: with {@code drop}, everything no rule accepted, IPv4 and IPv6, in both
 *       directions.
 * </ol>
 */
@Getter
@Setter
public class FirewallSpec {

  /** Prefix of every nwfilter Mnemosyne owns; nothing without it is ever changed or deleted. */
  public static final String FILTER_PREFIX = "mnem-";

  public static final String NAME_PATTERN = "^[a-z0-9][a-z0-9_.-]{0,40}$";

  public static final String SEED_IP_VAR = "MNEM_SEED_IP";
  public static final String SEED_PORT_VAR = "MNEM_SEED_PORT";

  /** The most nwfilter rules one firewall may expand to; keeps every priority below the default. */
  static final int MAX_EXPANDED_RULES = 1000;

  private static final int PRIORITY_ESTABLISHED = -300;
  private static final int PRIORITY_DHCP = -260;
  private static final int PRIORITY_SEED = -250;
  private static final int PRIORITY_FIRST_RULE = -200;
  private static final int PRIORITY_DEFAULT = 900;

  /** The map key. */
  @NotBlank(message = "Firewall name is required")
  @Pattern(
      regexp = NAME_PATTERN,
      message = "Firewall name must be 1-41 characters of a-z, 0-9, '_', '.' and '-'")
  private String name;

  /** What happens to traffic no rule matched: {@code drop} (an allow-list) or {@code accept}. */
  @JsonProperty("default")
  @Pattern(regexp = "^(drop|accept)$", message = "Firewall default must be drop or accept")
  private String defaultAction = "drop";

  /** Pull in libvirt's {@code clean-traffic}: no MAC, IP or ARP spoofing from the VM. */
  private boolean antiSpoofing = true;

  /** Always let the VM reach its own cloud-init seed server. */
  private boolean allowSeed = true;

  /**
   * Let the VM ask for and receive a DHCP lease. On a network Mnemosyne creates, the first boot
   * gets the VM's address this way, before cloud-init writes it down as static.
   */
  private boolean allowDhcp = true;

  @Valid
  @Size(max = 500, message = "At most 500 rules per firewall")
  private List<@Valid FirewallRule> rules = List.of();

  public void setRules(List<FirewallRule> rules) {
    this.rules = rules == null ? List.of() : rules;
  }

  public void setDefaultAction(String defaultAction) {
    this.defaultAction = defaultAction == null ? "drop" : defaultAction;
  }

  public String filterName() {
    return FILTER_PREFIX + name;
  }

  public boolean dropsByDefault() {
    return "drop".equals(defaultAction);
  }

  @AssertTrue(message = "Firewall rules expand to more than 1000 entries (remote x ports)")
  public boolean isExpansionBounded() {
    long n = 0;
    for (FirewallRule r : rules)
      n += (long) Math.max(1, r.getRemote().size()) * Math.max(1, r.getPorts().size());
    return n <= MAX_EXPANDED_RULES;
  }

  /** One line for the plan. */
  public String describe() {
    return String.format(
        "default %s, %d rule%s%s%s%s",
        defaultAction,
        rules.size(),
        rules.size() == 1 ? "" : "s",
        antiSpoofing ? ", anti-spoofing" : "",
        allowSeed ? ", seed allowed" : "",
        allowDhcp ? ", dhcp allowed" : "");
  }

  /**
   * The nwfilter XML. It names no VM and no address of its own: the VM's IP and its seed server
   * come in as the filterref's parameters ({@code IP}, {@value #SEED_IP_VAR}, {@value
   * #SEED_PORT_VAR}), so one filter serves every VM that uses it.
   */
  public String toXml(String uuid) {
    StringBuilder sb = new StringBuilder();
    sb.append("<filter name='").append(XmlUtil.escape(filterName())).append("' chain='root'>");
    if (uuid != null) sb.append("<uuid>").append(XmlUtil.escape(uuid)).append("</uuid>");
    if (antiSpoofing) sb.append("<filterref filter='clean-traffic'/>");

    sb.append(rule("accept", "in", PRIORITY_ESTABLISHED, "all", "state='ESTABLISHED,RELATED'"));
    sb.append(rule("accept", "out", PRIORITY_ESTABLISHED, "all", "state='ESTABLISHED,RELATED'"));
    if (allowDhcp) {
      sb.append(
          rule(
              "accept",
              "out",
              PRIORITY_DHCP,
              "udp",
              "srcportstart='68' dstportstart='67' comment='mnemosyne: dhcp'"));
      sb.append(
          rule(
              "accept",
              "in",
              PRIORITY_DHCP,
              "udp",
              "srcportstart='67' dstportstart='68' comment='mnemosyne: dhcp'"));
    }
    if (allowSeed)
      sb.append(
          rule(
              "accept",
              "out",
              PRIORITY_SEED,
              "tcp",
              "dstipaddr='$"
                  + SEED_IP_VAR
                  + "' dstportstart='$"
                  + SEED_PORT_VAR
                  + "' state='NEW' comment='mnemosyne: cloud-init seed'"));

    int priority = PRIORITY_FIRST_RULE;
    for (FirewallRule r : rules) {
      for (String attrs : expand(r)) {
        sb.append(rule(r.getAction(), r.getDirection(), priority++, r.getProtocol(), attrs));
      }
    }

    if (dropsByDefault()) {
      sb.append(rule("drop", "inout", PRIORITY_DEFAULT, "all", ""));
      sb.append(rule("drop", "inout", PRIORITY_DEFAULT + 1, "all-ipv6", ""));
    }
    sb.append("</filter>");
    return sb.toString();
  }

  /** One rule's attribute strings, one per remote x port combination. */
  private static List<String> expand(FirewallRule r) {
    boolean out = "out".equals(r.getDirection());
    List<String> remotes = r.getRemote().isEmpty() ? List.of("") : r.getRemote();
    List<String> ports = r.getPorts().isEmpty() ? List.of("") : r.getPorts();
    List<String> result = new ArrayList<>();
    for (String remote : remotes) {
      for (String port : ports) {
        StringBuilder a = new StringBuilder();
        if (!remote.isEmpty()) {
          Ipv4.Cidr c = Ipv4.cidr(remote);
          String side = out ? "dst" : "src";
          a.append(side)
              .append("ipaddr='")
              .append(c.prefix() == 32 ? c.host() : c.networkAddress())
              .append("' ");
          if (c.prefix() != 32) a.append(side).append("ipmask='").append(c.prefix()).append("' ");
        }
        if (!port.isEmpty()) {
          int[] range = FirewallRule.portRange(port);
          a.append("dstportstart='").append(range[0]).append("' ");
          if (range[1] != range[0]) a.append("dstportend='").append(range[1]).append("' ");
        }
        if ("accept".equals(r.getAction())) a.append("state='NEW' ");
        if (r.getComment() != null && !r.getComment().isBlank())
          a.append("comment='").append(XmlUtil.escape(r.getComment().trim())).append("' ");
        result.add(a.toString().trim());
      }
    }
    return result;
  }

  private static String rule(
      String action, String direction, int priority, String protocol, String attrs) {
    return String.format(
        "<rule action='%s' direction='%s' priority='%d'><%s%s/></rule>",
        action, direction, priority, protocol, attrs.isEmpty() ? "" : " " + attrs);
  }
}
