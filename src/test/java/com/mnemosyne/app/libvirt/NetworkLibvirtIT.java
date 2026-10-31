package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mnemosyne.app.http.CloudInitServer;
import com.mnemosyne.app.model.FirewallSpec;
import com.mnemosyne.app.model.NetState;
import com.mnemosyne.app.model.NetworkPlan;
import com.mnemosyne.app.model.NetworkSpec;
import com.mnemosyne.app.model.Preflight;
import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.model.Templates;
import com.mnemosyne.app.utils.XmlUtil;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;

/**
 * Networks against libvirt's in-memory test driver: that the network XML Mnemosyne writes is
 * accepted, that the ownership marker survives the round trip through libvirt, and that a second
 * run finds nothing to do. The test driver has no nwfilter, which is itself worth a test: a
 * firewall on such a host has to stop the run in preflight rather than fail halfway.
 */
@DisplayName("Networks against libvirt's test driver")
public class NetworkLibvirtIT {

  private Connect connect;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private PrintStream stdout;

  @BeforeAll
  static void libvirtPresent() {
    try {
      new Connect("test:///default").close();
    } catch (UnsatisfiedLinkError | NoClassDefFoundError | LibvirtException e) {
      if ("true".equals(System.getenv("CI"))) throw new IllegalStateException(e);
      Assumptions.abort("libvirt client library not found - install libvirt0");
    }
  }

  @BeforeEach
  void openHost() throws Exception {
    Path node = Path.of(NetworkLibvirtIT.class.getResource("/libvirt/test-node.xml").toURI());
    connect = new Connect("test://" + node.toAbsolutePath());
    stdout = System.out;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void closeHost() throws LibvirtException {
    System.setOut(stdout);
    CloudInitServer.unregister("web-01");
    if (connect != null) connect.close();
  }

  private static NetworkSpec agents(String address) {
    NetworkSpec n = new NetworkSpec();
    n.setName("agents");
    n.setAddress(address);
    n.setDhcp(null);
    return n;
  }

  private static Map<String, NetworkSpec> nets(NetworkSpec... specs) {
    Map<String, NetworkSpec> m = new LinkedHashMap<>();
    for (NetworkSpec s : specs) m.put(s.getName(), s);
    return m;
  }

  private static Templates templates() {
    Templates t = new Templates();
    t.setServerTmpl("templates/server.xml");
    t.setVolTmpl("templates/volume.xml");
    t.setMetaDataTmpl(NetworkLibvirtIT.class.getResource("/meta-data.yml").getPath());
    t.setUserDataTmpl(NetworkLibvirtIT.class.getResource("/user-data.yml").getPath());
    t.setNetworkConfigTmpl(NetworkLibvirtIT.class.getResource("/network-config.yml").getPath());
    return t;
  }

  private static Server web(String network) {
    Server s = new Server();
    s.setId("web-01");
    s.setCpu(1);
    s.setRam(512);
    s.setDisk(20);
    s.setPool("default");
    s.setNetwork(network);
    s.setIp("10.50.0.10/24");
    s.setGateway("10.50.0.1");
    s.setVolLookup("debian-13-genericcloud-amd64.qcow2");
    s.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    s.setTemplates(templates());
    return s;
  }

  /** One run: plan VMs and networks, preflight, apply. */
  private Harmonia run(
      Map<String, Server> servers,
      Map<String, NetworkSpec> networks,
      Map<String, FirewallSpec> firewalls)
      throws LibvirtException {
    Harmonia h = new Harmonia("it", connect);
    h.plan(servers, false);
    h.planNetworks(networks, firewalls, false);
    Preflight p = h.preflight();
    assertThat(p.getProblems()).isEmpty();
    h.reconcile(1);
    return h;
  }

  private NetState network(String name) throws LibvirtException {
    return new NetworkOps(connect)
        .readNetworks().stream().filter(n -> n.name().equals(name)).findFirst().orElse(null);
  }

  @Test
  void aDeclaredNetworkIsCreatedRunningManagedAndThenLeftAlone() throws Exception {
    Harmonia first = run(Map.of(), nets(agents("10.50.0.1/24")), Map.of());
    assertThat(first.failures()).isZero();

    NetState n = network("agents");
    assertThat(n).isNotNull();
    assertThat(n.managed()).as("the ownership marker survives libvirt").isTrue();
    assertThat(n.active()).isTrue();
    assertThat(n.autostart()).isTrue();
    assertThat(n.shape())
        .isEqualTo(new NetState.Shape("nat", "10.50.0.1/24", n.shape().bridge(), null));

    Harmonia second = new Harmonia("it", connect);
    second.plan(Map.of(), false);
    assertThat(second.planNetworks(nets(agents("10.50.0.1/24")), Map.of(), false).hasChanges())
        .isFalse();
  }

  /**
   * The redefine and the restart go through; that the address actually changes is not visible here,
   * because the test driver keeps a running network's old definition across a restart. A real
   * libvirt swaps the new one in on {@code net-destroy}, which is what the restart is for.
   */
  @Test
  void aChangedNetworkWithoutGuestsIsRedefinedAndRestarted() throws Exception {
    run(Map.of(), nets(agents("10.50.0.1/24")), Map.of());
    Harmonia h = run(Map.of(), nets(agents("10.50.0.1/16")), Map.of());
    assertThat(h.failures()).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("~ network agents  (address 10.50.0.1/24->10.50.0.1/16)");
    assertThat(network("agents").active()).isTrue();
    assertThat(network("agents").managed()).isTrue();
  }

  @Test
  void aManagedNetworkLeavingTheInventoryIsDeleted_theDefaultOneIsNot() throws Exception {
    run(Map.of(), nets(agents("10.50.0.1/24")), Map.of());
    run(Map.of(), Map.of(), Map.of());
    assertThat(network("agents")).isNull();
    assertThat(network("default")).as("not Mnemosyne's").isNotNull();
  }

  @Test
  void aVmIsCreatedOnANetworkDeclaredInTheSameRun() throws Exception {
    Harmonia h = run(Map.of("web-01", web("agents")), nets(agents("10.50.0.1/24")), Map.of());
    assertThat(h.failures()).isZero();
    Domain d = connect.domainLookupByName("web-01");
    try {
      assertThat(XmlUtil.getShortState(d.getXMLDesc(0)).primaryNic().orElseThrow().network())
          .isEqualTo("agents");
    } finally {
      d.free();
    }

    // The network is in use now: dropping it from the inventory keeps it.
    Harmonia again = new Harmonia("it", connect);
    again.plan(Map.of("web-01", web("agents")), false);
    NetworkPlan p = again.planNetworks(Map.of(), Map.of(), false);
    assertThat(p.getNetDelete()).isEmpty();
    assertThat(p.getNetKept()).containsEntry("agents", List.of("web-01"));
  }

  @Test
  void aNameTakenByAnUnmanagedNetworkBlocksTheRun() throws Exception {
    NetworkSpec taken = agents("10.99.0.1/24");
    taken.setName("default");
    Harmonia h = new Harmonia("it", connect);
    h.plan(Map.of(), false);
    h.planNetworks(nets(taken), Map.of(), false);
    assertThat(h.preflight().blockers(NetworkPlan.netId("default")))
        .singleElement()
        .asString()
        .contains("name taken by a network mnemosyne does not manage");
  }

  @Test
  void aNetworkOverlappingAnExistingOneBlocksTheRun() throws Exception {
    Harmonia h = new Harmonia("it", connect);
    h.plan(Map.of(), false);
    h.planNetworks(nets(agents("192.168.122.200/24")), Map.of(), false);
    assertThat(h.preflight().blockers(NetworkPlan.netId("agents")))
        .singleElement()
        .asString()
        .contains("overlaps network 'default' (192.168.122.1/24)");
  }

  @Test
  void aFirewallOnAHostWithoutNwfilterBlocksTheRun() throws Exception {
    FirewallSpec fw = new FirewallSpec();
    fw.setName("egress");
    Server s = web("agents");
    s.setFirewall("egress");
    Harmonia h = new Harmonia("it", connect);
    h.plan(Map.of("web-01", s), false);
    h.planNetworks(nets(agents("10.50.0.1/24")), Map.of("egress", fw), false);
    Preflight p = h.preflight();
    assertThat(p.blockers(NetworkPlan.fwId("egress"))).isNotEmpty();
    assertThat(p.blockers("web-01")).anyMatch(b -> b.startsWith("nwfilter"));
  }
}
