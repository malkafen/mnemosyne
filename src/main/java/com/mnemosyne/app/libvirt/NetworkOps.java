package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.model.FirewallSpec;
import com.mnemosyne.app.model.NetState;
import com.mnemosyne.app.model.NetworkPlan.FilterState;
import com.mnemosyne.app.model.Preflight.Problem;
import com.mnemosyne.app.utils.XmlUtil;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.libvirt.Network;
import org.libvirt.NetworkFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class NetworkOps {

  private static final Logger log = LoggerFactory.getLogger(NetworkOps.class);

  /** {@code VIR_NETWORK_XML_INACTIVE}: the persistent config, not the running one. */
  private static final int VIR_NETWORK_XML_INACTIVE = 1;

  private final Connect connect;

  NetworkOps(Connect connect) {
    this.connect = connect;
  }

  /**
   * Read-only check that a domain defined against this network could start. A name libvirt does not
   * know is accepted by {@code domainDefineXML} and only fails when the domain boots, which is late
   * enough for the disk to have been cloned already.
   */
  Optional<Problem> checkNetwork(String name) {
    Network network;
    try {
      network = connect.networkLookupByName(name);
    } catch (LibvirtException e) {
      log.debug("Preflight: network '{}' not found: {}", name, e.getMessage(), e);
      return Optional.of(new Problem("network '" + name + "'", "not found on the host"));
    }
    try {
      if (network.isActive() != 1) {
        return Optional.of(
            new Problem("network '" + name + "'", "not running; start it with virsh net-start"));
      }
      return Optional.empty();
    } catch (LibvirtException e) {
      log.debug("Preflight: checking network '{}' failed: {}", name, e.getMessage(), e);
      return Optional.of(new Problem("network '" + name + "'", cause(e)));
    } finally {
      freeNetworkQuietly(network);
    }
  }

  /** Every network on the host, running or not, with its persistent config. */
  List<NetState> readNetworks() throws LibvirtException {
    Set<String> names = new LinkedHashSet<>();
    names.addAll(orEmpty(connect.listNetworks()));
    names.addAll(orEmpty(connect.listDefinedNetworks()));
    List<NetState> found = new ArrayList<>(names.size());
    for (String name : names) {
      Network n = connect.networkLookupByName(name);
      try {
        String xml = persistentXml(n);
        found.add(
            new NetState(
                name,
                XmlUtil.uuid(xml),
                XmlUtil.networkManaged(xml),
                n.isActive() == 1,
                n.getAutostart(),
                XmlUtil.networkShape(xml)));
      } finally {
        freeNetworkQuietly(n);
      }
    }
    return List.copyOf(found);
  }

  /**
   * Every nwfilter on the host. Only Mnemosyne's own ({@code mnem-*}) are read in full; the rest
   * are listed by name so a reference to one can be told from a missing filter. Empty when the host
   * has no nwfilter driver, which {@link #filtersSupported} then reports.
   */
  List<FilterState> readFilters() throws LibvirtException {
    List<FilterState> found = new ArrayList<>();
    for (String name : orEmpty(connect.listNetworkFilters())) {
      if (!name.startsWith(FirewallSpec.FILTER_PREFIX)) {
        found.add(new FilterState(name, null, null));
        continue;
      }
      NetworkFilter f = connect.networkFilterLookupByName(name);
      try {
        String xml = f.getXMLDesc();
        found.add(new FilterState(name, XmlUtil.uuid(xml), XmlUtil.canonicalFilter(xml)));
      } finally {
        freeFilterQuietly(f);
      }
    }
    return List.copyOf(found);
  }

  /** Whether the host answers nwfilter calls at all; a test or a stripped-down host may not. */
  Optional<Problem> checkFilters() {
    try {
      connect.listNetworkFilters();
      return Optional.empty();
    } catch (LibvirtException e) {
      log.debug("Preflight: nwfilter not available: {}", e.getMessage(), e);
      return Optional.of(
          new Problem(
              "nwfilter",
              "the host has no network filter driver (install libvirt-daemon-driver-nwfilter): "
                  + cause(e)));
    }
  }

  /** Defines (or redefines) a network, and brings it to {@code active} and {@code autostart}. */
  void defineNetwork(String name, String xml, boolean autostart) throws LibvirtException {
    log.trace("Network XML for '{}': {}", name, xml);
    Network n = connect.networkDefineXML(xml);
    try {
      n.setAutostart(autostart);
      if (n.isActive() != 1) n.create();
    } finally {
      freeNetworkQuietly(n);
    }
  }

  /** Stops and starts a running network, so its new config takes effect. */
  void restartNetwork(String name) throws LibvirtException {
    Network n = connect.networkLookupByName(name);
    try {
      if (n.isActive() == 1) n.destroy();
      n.create();
    } finally {
      freeNetworkQuietly(n);
    }
  }

  void setNetworkAutostart(String name, boolean autostart) throws LibvirtException {
    Network n = connect.networkLookupByName(name);
    try {
      n.setAutostart(autostart);
    } finally {
      freeNetworkQuietly(n);
    }
  }

  void startNetwork(String name) throws LibvirtException {
    Network n = connect.networkLookupByName(name);
    try {
      if (n.isActive() != 1) n.create();
    } finally {
      freeNetworkQuietly(n);
    }
  }

  void deleteNetwork(String name) throws LibvirtException {
    Network n = connect.networkLookupByName(name);
    try {
      if (n.isActive() == 1) n.destroy();
      n.undefine();
    } finally {
      freeNetworkQuietly(n);
    }
  }

  /**
   * Defines or redefines a filter. libvirt re-instantiates the rules on every running VM that
   * references it, so this alone is what makes a changed firewall apply.
   */
  void defineFilter(String name, String xml) throws LibvirtException {
    log.trace("Network filter XML for '{}': {}", name, xml);
    freeFilterQuietly(connect.networkFilterDefineXML(xml));
  }

  void deleteFilter(String name) throws LibvirtException {
    NetworkFilter f = connect.networkFilterLookupByName(name);
    try {
      f.undefine();
    } finally {
      freeFilterQuietly(f);
    }
  }

  // virNetworkUpdate's enums; libvirt-java binds neither them nor the call.
  private static final int UPDATE_DELETE = 2;
  private static final int UPDATE_ADD_LAST = 3;
  private static final int SECTION_IP_DHCP_HOST = 4;
  private static final int AFFECT_LIVE = 1;
  private static final int AFFECT_CONFIG = 2;

  /**
   * Reserves {@code ip} for {@code mac} in the network's DHCP, in the running dnsmasq and in the
   * config, so the VM is handed its inventory address on its first boot. An older reservation for
   * either the address or the MAC — a VM of the same name created before, a retried creation — is
   * taken back first: libvirt refuses a second entry that matches either.
   *
   * <p>No network restart is involved; libvirt only reloads dnsmasq, so the guests already on the
   * network keep their connections.
   */
  void reserveDhcp(String network, String mac, String ip, String name) throws LibvirtException {
    Network n = connect.networkLookupByName(network);
    try {
      int flags = AFFECT_CONFIG | (n.isActive() == 1 ? AFFECT_LIVE : 0);
      tryUpdate(n, UPDATE_DELETE, "<host ip='" + XmlUtil.escape(ip) + "'/>", flags);
      tryUpdate(n, UPDATE_DELETE, "<host mac='" + XmlUtil.escape(mac) + "'/>", flags);
      update(
          n,
          UPDATE_ADD_LAST,
          String.format(
              "<host mac='%s' name='%s' ip='%s'/>",
              XmlUtil.escape(mac), XmlUtil.escape(name), XmlUtil.escape(ip)),
          flags);
    } finally {
      freeNetworkQuietly(n);
    }
  }

  /**
   * Takes back the reservation of a VM being deleted. Not finding one is fine — the VM may never
   * have had one — and so is a network that is gone already.
   */
  void releaseDhcp(String network, String mac) {
    Network n;
    try {
      n = connect.networkLookupByName(network);
    } catch (LibvirtException e) {
      log.debug("No network '{}' to release {} from", network, mac);
      return;
    }
    try {
      int flags = AFFECT_CONFIG | (n.isActive() == 1 ? AFFECT_LIVE : 0);
      tryUpdate(n, UPDATE_DELETE, "<host mac='" + XmlUtil.escape(mac) + "'/>", flags);
    } catch (LibvirtException e) {
      log.debug("Releasing {} in '{}' failed", mac, network, e);
    } finally {
      freeNetworkQuietly(n);
    }
  }

  private void tryUpdate(Network n, int command, String xml, int flags) {
    try {
      update(n, command, xml, flags);
    } catch (LibvirtException | RuntimeException e) {
      log.debug("Network update {} ignored: {}", xml, e.getMessage());
    }
  }

  /**
   * {@code virNetworkUpdate}, called through JNA directly: libvirt-java 0.5.4 binds the network
   * calls but not this one. The handle's pointer is a package-private field of {@link Network},
   * read by reflection. Any failure is raised as an {@link IllegalStateException} carrying
   * libvirt's own message.
   */
  void update(Network n, int command, String xml, int flags) throws LibvirtException {
    Object pointer;
    try {
      java.lang.reflect.Field vnp = Network.class.getDeclaredField("vnp");
      vnp.setAccessible(true);
      pointer = vnp.get(n);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("libvirt-java has no network handle to update", e);
    }
    com.sun.jna.NativeLibrary lib = com.sun.jna.NativeLibrary.getInstance("virt");
    int rc =
        lib.getFunction("virNetworkUpdate")
            .invokeInt(new Object[] {pointer, command, SECTION_IP_DHCP_HOST, -1, xml, flags});
    if (rc < 0) {
      String msg = lib.getFunction("virGetLastErrorMessage").invokeString(new Object[0], false);
      throw new IllegalStateException(msg == null ? "virNetworkUpdate failed" : msg);
    }
  }

  /** The DHCP reservations in a network's persistent config. */
  List<String> dhcpHosts(String network) throws LibvirtException {
    Network n = connect.networkLookupByName(network);
    try {
      return XmlUtil.dhcpHosts(persistentXml(n));
    } finally {
      freeNetworkQuietly(n);
    }
  }

  /**
   * The network's persistent config, which is what a redefine would change. A driver that cannot
   * tell it apart from the running one (libvirt's test driver) is asked for the only one it has:
   * for a network nobody has edited by hand since it was started, the two are the same.
   */
  private static String persistentXml(Network n) throws LibvirtException {
    try {
      return n.getXMLDesc(VIR_NETWORK_XML_INACTIVE);
    } catch (LibvirtException e) {
      log.debug("Network XML without the inactive flag: {}", e.getMessage());
      return n.getXMLDesc(0);
    }
  }

  private static List<String> orEmpty(String[] names) {
    return names == null ? List.of() : List.of(names);
  }

  private static String cause(LibvirtException e) {
    String msg = e.getMessage();
    return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg.trim();
  }

  private static void freeNetworkQuietly(Network network) {
    if (network == null) return;
    try {
      network.free();
    } catch (LibvirtException e) {
      log.debug("Failed to free Network handle; ignoring", e);
    }
  }

  private static void freeFilterQuietly(NetworkFilter filter) {
    if (filter == null) return;
    try {
      filter.free();
    } catch (LibvirtException e) {
      log.debug("Failed to free NetworkFilter handle; ignoring", e);
    }
  }
}
