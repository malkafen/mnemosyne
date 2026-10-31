package com.mnemosyne.app.model;

import com.mnemosyne.app.utils.Ipv4;
import com.mnemosyne.app.utils.XmlUtil;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.regex.Matcher;
import lombok.Getter;
import lombok.Setter;

/**
 * One libvirt network the inventory wants on a hypervisor, under the group's {@code networks} map.
 *
 * <p>The four modes are the four ways libvirt connects guests to the world:
 *
 * <ul>
 *   <li>{@code nat} — a private bridge with an address on the host, guests reach anything the host
 *       can through NAT, nothing outside reaches the guests. The usual choice for a sandbox:
 *       combine it with a {@code firewall} to say which of "anything" is actually allowed.
 *   <li>{@code route} — the same bridge, routed instead of masqueraded; the rest of the network
 *       needs a route back to {@code address}.
 *   <li>{@code isolated} — the bridge and nothing else: guests talk to each other and to the host's
 *       address on the bridge, and to nothing beyond it.
 *   <li>{@code bridge} — an existing host bridge ({@code br0}) handed to libvirt as a network, so
 *       guests sit directly on the host's LAN.
 * </ul>
 *
 * <p>A network Mnemosyne defines carries {@code <mnem:managedBy>} in its metadata, the way a domain
 * does; only those are ever changed or deleted. A name the host already holds without that marker
 * stops the run instead of being taken over.
 */
@Getter
@Setter
public class NetworkSpec {

  public static final String NAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,49}$";

  /** The map key; libvirt's network name. */
  @NotBlank(message = "Network name is required")
  @Pattern(
      regexp = NAME_PATTERN,
      message = "Network name must be 1-50 letters, digits, '_', '.' and '-'")
  private String name;

  @NotBlank(message = "Network mode is required")
  @Pattern(
      regexp = "^(nat|route|isolated|bridge)$",
      message = "Network mode must be one of nat, route, isolated, bridge")
  private String mode = "nat";

  /**
   * The host's own address on the bridge, with the prefix of the guests' subnet: {@code
   * 10.50.0.1/24}. It is also the guests' gateway in {@code nat} and {@code route} mode.
   */
  @Pattern(
      regexp = Ipv4.CIDR_PATTERN,
      message = "Network address must be the host address in CIDR notation (e.g. 10.50.0.1/24)")
  private String address;

  /**
   * Linux bridge name. Optional for the modes that create a bridge (libvirt then picks {@code
   * virbrN}); required for {@code bridge} mode, where it names the host bridge to use.
   */
  @Pattern(
      regexp = "^[A-Za-z0-9_.-]{1,15}$",
      message = "Bridge name must be 1-15 letters, digits, '_', '.' and '-'")
  private String bridge;

  /** An optional DHCP range inside the subnet: {@code 10.50.0.100-10.50.0.199}. */
  @Pattern(
      regexp = "^" + Ipv4.ADDRESS_PATTERN + "\\s*-\\s*" + Ipv4.ADDRESS_PATTERN + "$",
      message = "dhcp must be a range of two addresses (e.g. 10.50.0.100-10.50.0.199)")
  private String dhcp;

  /** Whether libvirt starts the network when the hypervisor boots. */
  private boolean autostart = true;

  public boolean isBridgeMode() {
    return "bridge".equals(mode);
  }

  /** Whether guests have a default route through {@link #address}. */
  public boolean routes() {
    return "nat".equals(mode) || "route".equals(mode);
  }

  public Ipv4.Cidr cidr() {
    return address == null ? null : Ipv4.cidr(address);
  }

  @AssertTrue(message = "bridge mode needs `bridge` (the host bridge, e.g. br0) and no `address`")
  public boolean isBridgeModeComplete() {
    return !isBridgeMode() || (bridge != null && address == null && dhcp == null);
  }

  @AssertTrue(message = "nat, route and isolated networks need `address` (e.g. 10.50.0.1/24)")
  public boolean isAddressPresent() {
    return isBridgeMode() || address != null;
  }

  @AssertTrue(
      message =
          "Network address must be a host address: not the subnet's network or broadcast address,"
              + " and the prefix at most /30")
  public boolean isAddressUsable() {
    if (address == null || !address.matches(Ipv4.CIDR_PATTERN)) return true;
    Ipv4.Cidr c = cidr();
    return c.prefix() <= 30 && c.address() != c.network() && c.address() != c.broadcast();
  }

  @AssertTrue(
      message = "dhcp range must lie inside the network's subnet and not include its address")
  public boolean isDhcpInSubnet() {
    if (dhcp == null || address == null || !address.matches(Ipv4.CIDR_PATTERN)) return true;
    long[] r = dhcpRange();
    if (r == null) return true;
    Ipv4.Cidr c = cidr();
    return r[0] <= r[1]
        && c.contains(r[0])
        && c.contains(r[1])
        && r[0] > c.network()
        && r[1] < c.broadcast()
        && (c.address() < r[0] || c.address() > r[1]);
  }

  /** The DHCP range as two numbers, or null when there is none or it does not parse. */
  long[] dhcpRange() {
    if (dhcp == null) return null;
    String[] p = dhcp.split("-");
    if (p.length != 2 || !Ipv4.isAddress(p[0].trim()) || !Ipv4.isAddress(p[1].trim())) return null;
    return new long[] {Ipv4.parse(p[0].trim()), Ipv4.parse(p[1].trim())};
  }

  /** {@code start-end}, normalised, for comparing against what the host reports. */
  public String dhcpNormalized() {
    long[] r = dhcpRange();
    return r == null ? null : Ipv4.format(r[0]) + "-" + Ipv4.format(r[1]);
  }

  /** The settings that make up the network, in the form the host's state is compared against. */
  public NetState.Shape shape() {
    return new NetState.Shape(
        mode, address == null ? null : cidr().toString(), bridge, dhcpNormalized());
  }

  /** One line for the plan: {@code nat 10.50.0.1/24, dhcp ..., bridge ...}. */
  public String describe() {
    StringBuilder sb = new StringBuilder(mode);
    if (address != null) sb.append(' ').append(address);
    if (bridge != null) sb.append(", bridge ").append(bridge);
    if (dhcp != null) sb.append(", dhcp ").append(dhcpNormalized());
    return sb.toString();
  }

  /**
   * The network's XML for {@code virNetworkDefineXML}. {@code uuid} is the existing network's when
   * it is being redefined, so libvirt updates it rather than refusing a second network of the name.
   */
  public String toXml(String uuid) {
    return toXml(uuid, List.of());
  }

  /**
   * As {@link #toXml(String)}, carrying over DHCP host reservations: the ones Mnemosyne made for
   * its VMs live in the network's config, and a redefine that dropped them would hand the next VM
   * created on it a wrong address. Reservations outside the new subnet are left out.
   */
  public String toXml(String uuid, List<String> dhcpHosts) {
    StringBuilder sb = new StringBuilder();
    sb.append("<network>");
    sb.append("<name>").append(XmlUtil.escape(name)).append("</name>");
    if (uuid != null) sb.append("<uuid>").append(XmlUtil.escape(uuid)).append("</uuid>");
    sb.append("<metadata><mnem:mnemosyne xmlns:mnem='")
        .append(XmlUtil.MNEM_NS)
        .append("'><mnem:managedBy>mnemosyne</mnem:managedBy></mnem:mnemosyne></metadata>");
    switch (mode) {
      case "nat" -> sb.append("<forward mode='nat'/>");
      case "route" -> sb.append("<forward mode='route'/>");
      case "bridge" -> sb.append("<forward mode='bridge'/>");
      default -> {
        // isolated: no <forward> at all is what makes it isolated
      }
    }
    if (isBridgeMode()) {
      sb.append("<bridge name='").append(XmlUtil.escape(bridge)).append("'/>");
    } else {
      if (bridge != null)
        sb.append("<bridge name='").append(XmlUtil.escape(bridge)).append("' stp='on' delay='0'/>");
      Ipv4.Cidr c = cidr();
      sb.append("<ip address='")
          .append(c.host())
          .append("' prefix='")
          .append(c.prefix())
          .append("'>");
      long[] r = dhcpRange();
      List<String> hosts = dhcpHosts.stream().filter(h -> hostInSubnet(h, c)).toList();
      if (r != null || !hosts.isEmpty()) {
        sb.append("<dhcp>");
        if (r != null)
          sb.append("<range start='")
              .append(Ipv4.format(r[0]))
              .append("' end='")
              .append(Ipv4.format(r[1]))
              .append("'/>");
        hosts.forEach(sb::append);
        sb.append("</dhcp>");
      }
      sb.append("</ip>");
    }
    sb.append("</network>");
    return sb.toString();
  }

  private static final java.util.regex.Pattern HOST_IP =
      java.util.regex.Pattern.compile("ip=[\"'](" + Ipv4.ADDRESS_PATTERN + ")[\"']");

  private static boolean hostInSubnet(String hostXml, Ipv4.Cidr subnet) {
    Matcher m = HOST_IP.matcher(hostXml);
    return m.find() && subnet.contains(Ipv4.parse(m.group(1)));
  }
}
