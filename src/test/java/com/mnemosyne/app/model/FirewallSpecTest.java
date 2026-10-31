package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.mnemosyne.app.utils.XmlUtil;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Firewall and network XML")
class FirewallSpecTest {

  private static FirewallRule rule(
      String action, String direction, String protocol, List<String> remote, List<String> ports) {
    FirewallRule r = new FirewallRule();
    r.setAction(action);
    r.setDirection(direction);
    r.setProtocol(protocol);
    r.setRemote(remote);
    r.setPorts(ports);
    return r;
  }

  private static FirewallSpec firewall(FirewallRule... rules) {
    FirewallSpec f = new FirewallSpec();
    f.setName("egress");
    f.setRules(List.of(rules));
    return f;
  }

  @Test
  void anOutRuleMatchesTheDestination() {
    String xml =
        firewall(rule("accept", "out", "tcp", List.of("10.0.0.5"), List.of("443"))).toXml(null);
    assertThat(xml)
        .contains("<filter name='mnem-egress' chain='root'>")
        .contains(
            "<rule action='accept' direction='out' priority='-200'><tcp dstipaddr='10.0.0.5'"
                + " dstportstart='443' state='NEW'/></rule>");
  }

  @Test
  void anInRuleMatchesTheSourceAndTheVmsOwnPort() {
    String xml =
        firewall(rule("accept", "in", "tcp", List.of("192.168.5.0/24"), List.of("22"))).toXml(null);
    assertThat(xml)
        .contains("<tcp srcipaddr='192.168.5.0' srcipmask='24' dstportstart='22' state='NEW'/>");
  }

  @Test
  void aCidrWithHostBitsIsWrittenAsItsNetwork() {
    String xml = firewall(rule("drop", "out", "all", List.of("10.1.2.3/8"), List.of())).toXml(null);
    assertThat(xml).contains("<all dstipaddr='10.0.0.0' dstipmask='8'/>");
  }

  @Test
  void remotesAndPortsExpandInOrderWithRisingPriority() {
    String xml =
        firewall(
                rule("accept", "out", "udp", List.of("8.8.8.8", "1.1.1.1"), List.of("53")),
                rule("accept", "out", "tcp", List.of(), List.of("8000-8100")))
            .toXml(null);
    assertThat(xml)
        .contains("priority='-200'><udp dstipaddr='8.8.8.8'")
        .contains("priority='-199'><udp dstipaddr='1.1.1.1'")
        .contains("priority='-198'><tcp dstportstart='8000' dstportend='8100' state='NEW'/>");
  }

  @Test
  void dropByDefaultClosesIpv4AndIpv6AfterTheRules() {
    String xml = firewall().toXml(null);
    assertThat(xml)
        .contains("<rule action='drop' direction='inout' priority='900'><all/></rule>")
        .contains("<rule action='drop' direction='inout' priority='901'><all-ipv6/></rule>");
  }

  @Test
  void acceptByDefaultAddsNoCatchAll() {
    FirewallSpec f = firewall(rule("drop", "out", "all", List.of("10.0.0.0/8"), List.of()));
    f.setDefaultAction("accept");
    assertThat(f.toXml(null)).doesNotContain("priority='900'");
  }

  @Test
  void repliesAntiSpoofingAndTheSeedAreAllowedUnlessTurnedOff() {
    String xml = firewall().toXml(null);
    assertThat(xml)
        .contains("<filterref filter='clean-traffic'/>")
        .contains("direction='in' priority='-300'><all state='ESTABLISHED,RELATED'/>")
        .contains("dstipaddr='$MNEM_SEED_IP' dstportstart='$MNEM_SEED_PORT'");

    FirewallSpec bare = firewall();
    bare.setAntiSpoofing(false);
    bare.setAllowSeed(false);
    assertThat(bare.toXml(null)).doesNotContain("clean-traffic").doesNotContain("MNEM_SEED");
  }

  @Test
  void aCommentIsEscaped() {
    FirewallRule r = rule("accept", "out", "tcp", List.of(), List.of("443"));
    r.setComment("api <prod> & 'co'");
    assertThat(firewall(r).toXml(null)).contains("comment='api &lt;prod&gt; &amp; &apos;co&apos;'");
  }

  @Test
  void theCanonicalFormIgnoresUuidOrderAndStateWordOrder() {
    String ours = firewall(rule("accept", "out", "tcp", List.of(), List.of("443"))).toXml(null);
    // libvirt hands the filter back with a uuid, double quotes and its own layout
    String host =
        ours.replace("chain='root'>", "chain='root'>\n  <uuid>1b2c</uuid>\n")
            .replace("ESTABLISHED,RELATED", "RELATED,ESTABLISHED")
            .replace('\'', '"');
    assertThat(XmlUtil.canonicalFilter(host)).isEqualTo(XmlUtil.canonicalFilter(ours));

    String changed = ours.replace("dstportstart='443'", "dstportstart='444'");
    assertThat(XmlUtil.canonicalFilter(changed)).isNotEqualTo(XmlUtil.canonicalFilter(ours));
  }

  @Test
  void aNatNetworkIsWrittenWithMetadataAndDhcp() {
    NetworkSpec n = new NetworkSpec();
    n.setName("agents");
    n.setAddress("10.50.0.1/24");
    n.setDhcp("10.50.0.100 - 10.50.0.199");
    n.setBridge("virbr-agents");
    String xml = n.toXml("uuid-1");
    assertThat(xml)
        .contains("<name>agents</name><uuid>uuid-1</uuid>")
        .contains("<mnem:managedBy>mnemosyne</mnem:managedBy>")
        .contains("<forward mode='nat'/>")
        .contains("<bridge name='virbr-agents' stp='on' delay='0'/>")
        .contains("<ip address='10.50.0.1' prefix='24'>")
        .contains("<range start='10.50.0.100' end='10.50.0.199'/>");

    assertThat(XmlUtil.networkManaged(xml)).isTrue();
    assertThat(XmlUtil.networkShape(xml)).isEqualTo(n.shape());
  }

  @Test
  void anIsolatedNetworkHasNoForward() {
    NetworkSpec n = new NetworkSpec();
    n.setName("lab");
    n.setMode("isolated");
    n.setAddress("10.60.0.1/24");
    String xml = n.toXml(null);
    assertThat(xml).doesNotContain("<forward");
    assertThat(XmlUtil.networkShape(xml).mode()).isEqualTo("isolated");
  }

  @Test
  void aBridgeNetworkNamesTheHostBridgeOnly() {
    NetworkSpec n = new NetworkSpec();
    n.setName("lan");
    n.setMode("bridge");
    n.setBridge("br0");
    String xml = n.toXml(null);
    assertThat(xml).contains("<forward mode='bridge'/><bridge name='br0'/>").doesNotContain("<ip");
    assertThat(XmlUtil.networkShape(xml))
        .isEqualTo(new NetState.Shape("bridge", null, "br0", null));
  }

  @Test
  void theHostsNetmaskFormIsReadAsAPrefix() {
    String host =
        "<network><name>default</name><forward/><bridge name='virbr0'/>"
            + "<ip address='192.168.122.1' netmask='255.255.255.0'/></network>";
    assertThat(XmlUtil.networkShape(host))
        .isEqualTo(new NetState.Shape("nat", "192.168.122.1/24", "virbr0", null));
    assertThat(XmlUtil.networkManaged(host)).isFalse();
  }

  @Test
  void interfacesAndTheirFiltersAreRead() throws Exception {
    String domain =
        "<domain><name>a</name><vcpu>1</vcpu><memory>1048576</memory><devices>"
            + "<interface type='network'><mac address='52:54:00:00:00:01'/>"
            + "<source network='agents'/><model type='virtio'/>"
            + "<filterref filter='mnem-egress'><parameter name='IP' value='10.50.0.10'/>"
            + "</filterref></interface>"
            + "<interface type='bridge'><source bridge='br0'/></interface>"
            + "</devices></domain>";
    DomainState d = XmlUtil.getShortState(domain);
    assertThat(d.nics())
        .containsExactly(
            new DomainState.Nic(
                "agents", "mnem-egress", Map.of("IP", "10.50.0.10"), "52:54:00:00:00:01"),
            new DomainState.Nic(null, null, Map.of()));

    String swapped =
        XmlUtil.interfaceWithFilter(domain, "mnem-other", Map.of("IP", "10.50.0.10", "B", "2"));
    assertThat(swapped)
        .contains("52:54:00:00:00:01")
        .contains("filter=\"mnem-other\"")
        .doesNotContain("mnem-egress")
        .doesNotContain("br0");

    String removed = XmlUtil.interfaceWithFilter(domain, null, Map.of());
    assertThat(removed).doesNotContain("filterref").contains("agents");
  }

  @Test
  void dhcpIsLetThroughSoTheFirstBootGetsItsAddress() {
    String xml = firewall().toXml(null);
    assertThat(xml)
        .contains("direction='out' priority='-260'><udp srcportstart='68' dstportstart='67'")
        .contains("direction='in' priority='-260'><udp srcportstart='67' dstportstart='68'");
    FirewallSpec f = firewall();
    f.setAllowDhcp(false);
    assertThat(f.toXml(null)).doesNotContain("srcportstart='68'");
  }

  @Test
  void aRedefinedNetworkKeepsTheReservationsInsideItsSubnet() {
    NetworkSpec n = new NetworkSpec();
    n.setName("agents");
    n.setAddress("10.50.0.1/24");
    String host =
        "<network><name>agents</name><ip address='10.50.0.1' prefix='24'><dhcp>"
            + "<host mac='52:54:00:00:00:01' name='a' ip='10.50.0.10'/>"
            + "<host mac='52:54:00:00:00:02' name='b' ip='10.60.0.10'/>"
            + "</dhcp></ip></network>";
    List<String> hosts = XmlUtil.dhcpHosts(host);
    assertThat(hosts).hasSize(2);
    String xml = n.toXml("u", hosts);
    assertThat(xml).contains("10.50.0.10").doesNotContain("10.60.0.10").contains("<dhcp>");
    assertThat(XmlUtil.dhcpHosts(xml)).hasSize(1);
  }

  @Test
  void aServerOnADeclaredNetworkGetsItsMacInTheDomainXml() throws Exception {
    Server s = new Server();
    s.setId("agent-01");
    s.setCpu(1);
    s.setRam(512);
    s.setIp("10.50.0.10/24");
    s.setNetwork("agents");
    s.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    s.setMac(Server.macFor("agent-01"));
    s.setFirewall("egress");
    s.setSeedIp("192.0.2.5");
    s.setSeedPort(8080);
    s.setVolPath("/var/lib/libvirt/images/agent-01.qcow2");
    s.setInit(InitMarker.pending(java.time.Instant.now()));
    Templates t = new Templates();
    t.setServerTmpl("templates/server.xml");
    s.setTemplates(t);

    assertThat(Server.macFor("agent-01"))
        .matches("^52:54:00(:[0-9a-f]{2}){3}$")
        .isEqualTo(Server.macFor("agent-01"))
        .isNotEqualTo(Server.macFor("agent-02"));

    DomainState d = XmlUtil.getShortState(s.buildServerXml());
    DomainState.Nic nic = d.primaryNic().orElseThrow();
    assertThat(nic.mac()).isEqualTo(Server.macFor("agent-01"));
    assertThat(nic.network()).isEqualTo("agents");
    assertThat(nic.filter()).isEqualTo("mnem-egress");
    assertThat(nic.params()).isEqualTo(s.filterParams());
  }
}
