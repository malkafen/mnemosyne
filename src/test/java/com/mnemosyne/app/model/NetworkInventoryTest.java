package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mnemosyne.app.config.Config;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code networks}, {@code firewalls} and {@code firewall} keys of the inventory. */
@DisplayName("Networks and firewalls in the inventory")
class NetworkInventoryTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @TempDir Path dir;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static final String GROUP =
      """
      - group: hv01
        host: 192.0.2.10
        user: virtops
        port: 22
        key: /k
        metaUrl: "http://192.0.2.5:8080/cloud-init/"
      """;

  private List<Mnemon> load(String yaml) throws IOException {
    Path file = dir.resolve("servers.yml");
    Files.writeString(file, yaml);
    Config config = mock(Config.class);
    when(config.getServersPath()).thenReturn(file.toString());
    return Mnemon.loadMnemones(config);
  }

  private static List<String> problems(Mnemon m) {
    Set<ConstraintViolation<Mnemon>> v = validator.validate(m);
    List<String> all =
        new java.util.ArrayList<>(
            v.stream().map(c -> c.getPropertyPath() + " " + c.getMessage()).toList());
    if (all.isEmpty()) all.addAll(m.referenceErrors());
    return all;
  }

  @Test
  void aFullSandboxLoadsAndInherits() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  networks:
                    agents:
                      mode: nat
                      address: 10.50.0.1/24
                  firewalls:
                    egress:
                      rules:
                        - action: accept
                          direction: out
                          protocol: tcp
                          remote: 10.0.0.5
                          ports: 443
                        - action: accept
                          direction: out
                          protocol: udp
                          remote: [8.8.8.8, 1.1.1.1]
                          ports: [53]
                  firewall: egress
                  servers:
                    agent-01:
                      cpu: 1
                      ram: 512
                      ip: 10.50.0.10/24
                      network: agents
                    agent-02:
                      cpu: 1
                      ram: 512
                      ip: 10.50.0.11/24
                      network: agents
                      firewall: none
                """)
            .get(0);

    assertThat(problems(m)).isEmpty();
    assertThat(m.getNetworks().get("agents").getName()).isEqualTo("agents");
    assertThat(m.getFirewalls().get("egress").getRules()).hasSize(2);
    assertThat(m.getFirewalls().get("egress").getRules().get(0).getRemote())
        .containsExactly("10.0.0.5");
    assertThat(m.getFirewalls().get("egress").getRules().get(0).getPorts()).containsExactly("443");

    Server a = m.getServers().get("agent-01");
    assertThat(a.getFirewall()).isEqualTo("egress");
    assertThat(a.getGateway())
        .as("gateway defaults to the network's address")
        .isEqualTo("10.50.0.1");
    assertThat(a.desiredFilter()).isEqualTo("mnem-egress");
    assertThat(a.filterParams())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("IP", "10.50.0.10", "MNEM_SEED_IP", "192.0.2.5", "MNEM_SEED_PORT", "8080"));

    assertThat(a.getMac()).isEqualTo(Server.macFor("agent-01"));

    Server b = m.getServers().get("agent-02");
    assertThat(b.getFirewall()).as("`none` opts out of the group default").isNull();
    assertThat(b.filterParams()).isEmpty();
  }

  @Test
  void anUndeclaredFirewallIsRefused() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  servers:
                    vm:
                      cpu: 1
                      ram: 512
                      ip: 192.0.2.40/24
                      firewall: nope
                """)
            .get(0);
    assertThat(problems(m)).anyMatch(p -> p.contains("'nope' is not declared under firewalls"));
  }

  @Test
  void anAddressOutsideTheDeclaredNetworkIsRefused() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  networks:
                    agents:
                      address: 10.50.0.1/24
                  servers:
                    vm:
                      cpu: 1
                      ram: 512
                      ip: 10.60.0.10/24
                      network: agents
                """)
            .get(0);
    assertThat(problems(m)).anyMatch(p -> p.contains("is not in network 'agents'"));
  }

  @Test
  void theNetworksOwnAddressIsRefusedForAServer() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  networks:
                    agents:
                      address: 10.50.0.1/24
                  servers:
                    vm:
                      cpu: 1
                      ram: 512
                      ip: 10.50.0.1/24
                      network: agents
                """)
            .get(0);
    assertThat(problems(m)).anyMatch(p -> p.contains("is the address of network 'agents'"));
  }

  @Test
  void overlappingNetworksAreRefused() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  networks:
                    a:
                      address: 10.50.0.1/16
                    b:
                      address: 10.50.7.1/24
                """)
            .get(0);
    assertThat(problems(m)).anyMatch(p -> p.contains("overlap"));
  }

  @Test
  void bridgeModeNeedsABridgeAndNoAddress() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  networks:
                    lan:
                      mode: bridge
                      address: 10.0.0.1/24
                """)
            .get(0);
    assertThat(problems(m)).anyMatch(p -> p.contains("bridge mode needs"));
  }

  @Test
  void aRuleWithPortsButNoTcpOrUdpIsRefused() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  firewalls:
                    bad:
                      rules:
                        - action: accept
                          direction: out
                          protocol: icmp
                          ports: 80
                """)
            .get(0);
    assertThat(problems(m)).anyMatch(p -> p.contains("ports need protocol tcp or udp"));
  }

  @Test
  void badRuleValuesAreRefusedByName() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  firewalls:
                    bad:
                      rules:
                        - action: allow
                          direction: sideways
                          protocol: tcp
                          remote: 10.0.0.300
                          ports: 70000
                """)
            .get(0);
    List<String> p = problems(m);
    assertThat(p).anyMatch(s -> s.contains("action must be accept, drop or reject"));
    assertThat(p).anyMatch(s -> s.contains("direction must be out or in"));
    assertThat(p).anyMatch(s -> s.contains("remote must be an IPv4 address or CIDR"));
    assertThat(p).anyMatch(s -> s.contains("port must be 1-65535"));
  }

  @Test
  void aDhcpRangeOutsideTheSubnetIsRefused() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  networks:
                    agents:
                      address: 10.50.0.1/24
                      dhcp: 10.50.0.100-10.50.1.20
                """)
            .get(0);
    assertThat(problems(m)).anyMatch(s -> s.contains("dhcp range must lie inside"));
  }

  @Test
  void aGroupWithOnlyNetworksAndNoServersIsValid() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  networks:
                    agents:
                      address: 10.50.0.1/24
                      dhcp: 10.50.0.100-10.50.0.199
                  servers:
                """)
            .get(0);
    assertThat(problems(m)).isEmpty();
    assertThat(m.getNetworks().get("agents").getName()).isEqualTo("agents");
  }

  @Test
  void aServerOnAnIsolatedNetworkCannotReachAnOutsideSeed() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  networks:
                    lab:
                      mode: isolated
                      address: 10.60.0.1/24
                  servers:
                    vm:
                      cpu: 1
                      ram: 512
                      ip: 10.60.0.10/24
                      network: lab
                """)
            .get(0);
    assertThat(problems(m)).anyMatch(s -> s.contains("is outside isolated network 'lab'"));
    assertThat(m.getServers().get("vm").getGateway())
        .as("an isolated network gives no default gateway")
        .isNull();
  }

  @Test
  void aServerOnAHostNetworkKeepsLibvirtsMac() throws IOException {
    Mnemon m =
        load(GROUP
                + """
                  servers:
                    vm:
                      cpu: 1
                      ram: 512
                      ip: 192.0.2.40/24
                      network: host-bridge
                """)
            .get(0);
    assertThat(m.getServers().get("vm").getMac()).isNull();
  }
}
