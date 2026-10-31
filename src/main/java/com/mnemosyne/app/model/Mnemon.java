package com.mnemosyne.app.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mnemosyne.app.config.*;
import com.mnemosyne.app.utils.Ipv4;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;

@Getter
@Setter
public class Mnemon {
  @NotBlank(message = "Group name must not be blank")
  private String group;

  // user and host end up in the libvirt URI as they are: a '?', '&' or '/' in either would add
  // URI parameters of its own (command= runs a program locally), so both are held to their syntax.
  @NotBlank(message = "User must not be blank")
  @Pattern(
      regexp = "^[A-Za-z_][A-Za-z0-9_.-]{0,31}$",
      message =
          "User must be a user name: letters, digits, '_', '.' and '-', not starting with a digit,"
              + " '.' or '-', 32 characters at most")
  private String user;

  @NotBlank(message = "SSH key path must not be blank: set `key` in the group, --key or MNEM_KEY")
  private String key;

  @Min(value = 1, message = "Port must be >= 1")
  @Max(value = 65535, message = "Port must be <= 65535")
  private int port;

  @NotBlank(message = "Host must not be blank")
  @Pattern(regexp = Server.HOSTNAME_PATTERN, message = "Host " + Server.HOSTNAME_MESSAGE)
  private String host;

  @jakarta.validation.Valid @com.fasterxml.jackson.annotation.JsonMerge
  private Templates templates = Templates.defaults();

  @NotBlank(message = "volLookup must not be blank")
  private String volLookup = "noble-server-cloudimg-amd64.img";

  @NotBlank(message = "Cloud-init metadata base URL is required")
  @Pattern(regexp = "^https?://.+", message = "metaUrl must be a valid HTTP/HTTPS URL")
  private String metaUrl = "http://127.0.0.1:80/files/";

  /**
   * The VMs this hypervisor must hold, keyed by server id.
   *
   * <p>An absent, empty or null {@code servers} block is a group of no VMs, not a mistake: it
   * describes the connection and says the host must hold nothing of Mnemosyne's, so an existing
   * group can be emptied down to zero VMs instead of being deleted from the inventory — which would
   * leave its VMs behind, unmanaged and unmentioned. The deletions it implies are in the plan like
   * any other, so nothing is removed that was not printed first.
   */
  @Valid private Map<String, Server> servers = new LinkedHashMap<>();

  /** Kept non-null whatever the YAML says, because every reader iterates it unguarded. */
  public void setServers(Map<String, Server> servers) {
    this.servers = servers == null ? new LinkedHashMap<>() : servers;
  }

  /**
   * The libvirt networks this hypervisor must have, keyed by network name. Created before any VM,
   * so a server can be put on a network declared in the same run. A managed network that leaves
   * this map is deleted once no domain uses it.
   */
  @Valid private Map<String, NetworkSpec> networks = new LinkedHashMap<>();

  /** Named firewalls a server can reference with {@code firewall}, keyed by firewall name. */
  @Valid private Map<String, FirewallSpec> firewalls = new LinkedHashMap<>();

  /**
   * The firewall of every server that names none of its own; a server opts out with {@code
   * firewall: none}.
   */
  private String firewall;

  public void setNetworks(Map<String, NetworkSpec> networks) {
    this.networks = networks == null ? new LinkedHashMap<>() : networks;
  }

  public void setFirewalls(Map<String, FirewallSpec> firewalls) {
    this.firewalls = firewalls == null ? new LinkedHashMap<>() : firewalls;
  }

  /**
   * What Bean Validation cannot see field by field: references between the sections of one group. A
   * server's firewall must be declared in {@code firewalls}; a server on a declared network must
   * have its {@code ip} inside that network's subnet; two declared networks must not overlap; and a
   * firewall that lets the seed through needs a {@code metaUrl} that resolves.
   */
  public List<String> referenceErrors() {
    List<String> errors = new ArrayList<>();
    if (firewall != null
        && !Server.NO_FIREWALL.equals(firewall)
        && !firewalls.containsKey(firewall))
      errors.add("[firewall] '" + firewall + "' is not declared under firewalls");

    List<NetworkSpec> declared = new ArrayList<>(networks.values());
    for (int i = 0; i < declared.size(); i++) {
      for (int j = i + 1; j < declared.size(); j++) {
        NetworkSpec a = declared.get(i), b = declared.get(j);
        if (cidrOf(a) != null && cidrOf(b) != null && cidrOf(a).overlaps(cidrOf(b)))
          errors.add(
              "[networks] '"
                  + a.getName()
                  + "' ("
                  + a.getAddress()
                  + ") and '"
                  + b.getName()
                  + "' ("
                  + b.getAddress()
                  + ") overlap");
        if (a.getBridge() != null && a.getBridge().equals(b.getBridge()) && !a.isBridgeMode())
          errors.add(
              "[networks] '"
                  + a.getName()
                  + "' and '"
                  + b.getName()
                  + "' both use bridge "
                  + a.getBridge());
      }
    }

    Map<String, String> macs = new LinkedHashMap<>();
    servers.forEach(
        (id, s) -> {
          if (s.getMac() == null) return;
          String other = macs.putIfAbsent(s.getMac(), id);
          if (other != null)
            errors.add(
                "[servers["
                    + id
                    + "]] derives the same MAC as '"
                    + other
                    + "' ("
                    + s.getMac()
                    + "); rename one of the two server ids");
        });

    servers.forEach(
        (id, s) -> {
          String fw = s.getFirewall();
          if (fw != null && !firewalls.containsKey(fw))
            errors.add(
                "[servers[" + id + "].firewall] '" + fw + "' is not declared under firewalls");
          if (fw != null
              && firewalls.containsKey(fw)
              && firewalls.get(fw).isAllowSeed()
              && s.getSeedIp() == null)
            errors.add(
                "[servers["
                    + id
                    + "].metaUrl] '"
                    + s.getMetaUrl()
                    + "' does not resolve to an IPv4 address, which firewall '"
                    + fw
                    + "' needs to let the seed through (or set allowSeed: false)");
          NetworkSpec net = networks.get(s.getNetwork());
          Ipv4.Cidr subnet = net == null ? null : cidrOf(net);
          // An isolated network routes nowhere: the guest can fetch its seed only from the host's
          // own address on the bridge, which means Mnemosyne running on the hypervisor itself.
          if (net != null
              && "isolated".equals(net.getMode())
              && subnet != null
              && seedHost(s.getMetaUrl()) != null
              && !subnet.contains(Ipv4.parse(seedHost(s.getMetaUrl()))))
            errors.add(
                "[servers["
                    + id
                    + "].metaUrl] the seed server "
                    + seedHost(s.getMetaUrl())
                    + " is outside isolated network '"
                    + net.getName()
                    + "', which routes nowhere - the guest could never fetch its cloud-init; use"
                    + " mode nat with a firewall instead");
          if (subnet != null && s.getIp() != null && s.getIp().matches(Ipv4.CIDR_PATTERN)) {
            Ipv4.Cidr ip = Ipv4.cidr(s.getIp());
            if (!subnet.contains(ip.address()) || ip.prefix() != subnet.prefix())
              errors.add(
                  "[servers["
                      + id
                      + "].ip] "
                      + s.getIp()
                      + " is not in network '"
                      + net.getName()
                      + "' ("
                      + net.getAddress()
                      + ")");
            else if (ip.address() == subnet.address()
                || ip.address() == subnet.network()
                || ip.address() == subnet.broadcast())
              errors.add(
                  "[servers["
                      + id
                      + "].ip] "
                      + s.getIp()
                      + " is the address of network '"
                      + net.getName()
                      + "' itself, or its network or broadcast address");
          }
        });
    return errors;
  }

  /** The IPv4 literal host of a metaUrl, or null for a name or anything unparsable. */
  private static String seedHost(String metaUrl) {
    try {
      String host = metaUrl == null ? null : URI.create(metaUrl).getHost();
      return Ipv4.isAddress(host) ? host : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static Ipv4.Cidr cidrOf(NetworkSpec n) {
    try {
      return n.getAddress() == null || !n.getAddress().matches(Ipv4.CIDR_PATTERN) ? null : n.cidr();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The seed server's IPv4 address and port from a {@code metaUrl}: an address literal as it is, a
   * name resolved once, here. Null when it cannot be told.
   */
  static Object[] seedEndpoint(String metaUrl) {
    if (metaUrl == null) return null;
    try {
      URI uri = URI.create(metaUrl);
      String host = uri.getHost();
      if (host == null) return null;
      int port = uri.getPort() > 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
      if (Ipv4.isAddress(host)) return new Object[] {host, port};
      for (InetAddress a : InetAddress.getAllByName(host))
        if (a instanceof Inet4Address) return new Object[] {a.getHostAddress(), port};
      return null;
    } catch (IOException | RuntimeException e) {
      log.debug("metaUrl '{}' does not resolve to an IPv4 address", metaUrl, e);
      return null;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(Mnemon.class);

  public static List<Mnemon> loadMnemones(Config config) throws IOException {
    String path = config.getServersPath();
    log.debug("Loading servers from {}", path);

    LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setAllowDuplicateKeys(false);
    ObjectMapper mapper =
        new ObjectMapper(YAMLFactory.builder().loaderOptions(loaderOptions).build());
    // `servers:` with nothing under it is an empty scalar, which Jackson otherwise refuses rather
    // than reads as the empty map it means. A group of no VMs has to be writable that way: the
    // alternative is `servers: {}`, which nobody types by hand.
    mapper
        .coercionConfigFor(LogicalType.Map)
        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsEmpty);

    File file = new File(path);
    List<Mnemon> mnemones =
        mapper.readValue(
            file, mapper.getTypeFactory().constructCollectionType(List.class, Mnemon.class));

    // An empty document deserializes to null, not to an empty list.
    if (mnemones == null || mnemones.isEmpty()) {
      throw new IllegalArgumentException("Inventory is empty or defines no groups: " + path);
    }

    log.debug("Loaded {} groups", mnemones.size());

    for (Mnemon m : mnemones) {
      if (m.getKey() == null || m.getKey().isBlank()) {
        m.setKey(config.getKey());
        log.debug("Group '{}' uses the default SSH key {}", m.getGroup(), config.getKey());
      }
      m.getNetworks().forEach((name, n) -> n.setName(name));
      m.getFirewalls().forEach((name, f) -> f.setName(name));

      // A group may legitimately hold no VMs; it still connects, so that whatever Mnemosyne
      // manages on that host is deleted.
      if (m.getServers().isEmpty()) {
        log.info(
            "Group '{}' has no servers: all managed VMs on {} will be deleted",
            m.getGroup(),
            m.getHost());
        continue;
      }

      Templates groupTmpl = m.getTemplates();
      m.getServers()
          .forEach(
              (key, s) -> {
                s.setId(key);
                Templates override = s.getTemplates();
                s.setTemplates(override == null ? groupTmpl : override.resolveOver(groupTmpl));

                if (s.getVolLookup() == null) s.setVolLookup(m.getVolLookup());
                if (s.getMetaUrl() == null) s.setMetaUrl(m.getMetaUrl());

                // The group's firewall unless the server names its own; `none` opts out of both.
                String fw = s.getFirewall() == null ? m.getFirewall() : s.getFirewall();
                s.setFirewall(fw == null || Server.NO_FIREWALL.equals(fw) ? null : fw);
                if (s.getFirewall() != null) {
                  Object[] seed = seedEndpoint(s.getMetaUrl());
                  if (seed != null) {
                    s.setSeedIp((String) seed[0]);
                    s.setSeedPort((Integer) seed[1]);
                  }
                }

                // On a network the inventory declares with a gateway of its own, that gateway is
                // the default one: the server only has to say where it is, not how out.
                NetworkSpec net = m.getNetworks().get(s.getNetwork());
                if (s.getGateway() == null
                    && net != null
                    && net.routes()
                    && net.getAddress() != null)
                  s.setGateway(net.getAddress().substring(0, net.getAddress().indexOf('/')));
                // A network Mnemosyne creates has no DHCP of anybody else's: it reserves the
                // server's address itself, against a MAC it chooses.
                if (net != null && net.getAddress() != null) s.setMac(Server.macFor(key));

                // An extra disk without a pool of its own lives where the VM's root disk lives.
                // Filled in here rather than read lazily, so validation and the plan see one value.
                s.getExtraDisks().stream()
                    .filter(d -> d.getPool() == null || d.getPool().isBlank())
                    .forEach(d -> d.setPool(s.getPool()));
              });
    }
    return mnemones;
  }
}
