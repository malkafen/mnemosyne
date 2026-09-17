package com.mnemosyne.app.libvirt;

import com.mnemosyne.app.model.Preflight.Problem;
import java.util.Optional;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.libvirt.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class NetworkOps {

  private static final Logger log = LoggerFactory.getLogger(NetworkOps.class);
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

  private static String cause(LibvirtException e) {
    String msg = e.getMessage();
    return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg.trim();
  }

  private static void freeNetworkQuietly(Network network) {
    try {
      network.free();
    } catch (LibvirtException e) {
      log.debug("Failed to free Network handle; ignoring", e);
    }
  }
}
