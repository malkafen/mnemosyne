package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.mnemosyne.app.model.DomainState.Nic;
import com.mnemosyne.app.model.NetworkPlan.FilterState;
import com.mnemosyne.app.utils.XmlUtil;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Network plan")
class NetworkPlanTest {

  private static NetworkSpec net(String name, String address) {
    NetworkSpec n = new NetworkSpec();
    n.setName(name);
    n.setAddress(address);
    return n;
  }

  private static FirewallSpec fw(String name) {
    FirewallSpec f = new FirewallSpec();
    f.setName(name);
    return f;
  }

  private static NetState host(String name, boolean managed, String address) {
    return new NetState(
        name, "u-" + name, managed, true, true, new NetState.Shape("nat", address, "virbr9", null));
  }

  private static DomainState domain(String name, boolean active, Nic nic) {
    return new DomainState(name, 1, 512, null, null, null, List.of(), active, false)
        .withNics(List.of(nic));
  }

  private static Map<String, NetworkSpec> nets(NetworkSpec... specs) {
    Map<String, NetworkSpec> m = new LinkedHashMap<>();
    for (NetworkSpec s : specs) m.put(s.getName(), s);
    return m;
  }

  private static Map<String, FirewallSpec> fws(FirewallSpec... specs) {
    Map<String, FirewallSpec> m = new LinkedHashMap<>();
    for (FirewallSpec s : specs) m.put(s.getName(), s);
    return m;
  }

  private static FilterState hostFilter(FirewallSpec spec) {
    return new FilterState(spec.filterName(), "fu", XmlUtil.canonicalFilter(spec.toXml(null)));
  }

  @Test
  void aMissingNetworkAndFilterAreCreated() {
    NetworkPlan p =
        new NetworkPlan(
            nets(net("agents", "10.50.0.1/24")),
            fws(fw("egress")),
            List.of(host("default", false, "192.168.122.1/24")),
            List.of(),
            List.of(),
            null,
            Map.of(),
            false);
    assertThat(p.getNetCreate()).containsOnlyKeys("agents");
    assertThat(p.getFilterCreate()).containsOnlyKeys("egress");
    assertThat(p.getNetDelete()).as("an unmanaged network is never deleted").isEmpty();
    assertThat(p.hasChanges()).isTrue();
  }

  @Test
  void aMatchingNetworkAndFilterAreLeftAlone() {
    FirewallSpec f = fw("egress");
    NetworkPlan p =
        new NetworkPlan(
            nets(net("agents", "10.50.0.1/24")),
            fws(f),
            List.of(host("agents", true, "10.50.0.1/24")),
            List.of(hostFilter(f)),
            List.of(),
            null,
            Map.of(),
            false);
    assertThat(p.hasChanges()).isFalse();
  }

  @Test
  void aChangedNetworkIsUpdatedButNotRestartedUnderRunningGuests() {
    NetworkPlan p =
        new NetworkPlan(
            nets(net("agents", "10.50.0.1/16")),
            fws(),
            List.of(host("agents", true, "10.50.0.1/24")),
            List.of(),
            List.of(domain("vm1", true, new Nic("agents", null, Map.of()))),
            null,
            Map.of(),
            false);
    NetworkPlan.NetUpdate u = p.getNetUpdate().get("agents");
    assertThat(u.diff()).containsExactly("address 10.50.0.1/24->10.50.0.1/16");
    assertThat(u.running()).containsExactly("vm1");
    assertThat(u.restartable()).isFalse();
  }

  @Test
  void aChangedFilterIsUpdatedAndNamesItsRunningUsers() {
    FirewallSpec old = fw("egress");
    FirewallSpec now = fw("egress");
    now.setDefaultAction("accept");
    NetworkPlan p =
        new NetworkPlan(
            nets(),
            fws(now),
            List.of(),
            List.of(hostFilter(old)),
            List.of(domain("vm1", true, new Nic("default", "mnem-egress", Map.of()))),
            null,
            Map.of(),
            false);
    assertThat(p.getFilterUpdate().get("egress").running()).containsExactly("vm1");
  }

  @Test
  void aNameHeldByAnUnmanagedNetworkIsNotTakenOver() {
    NetworkPlan p =
        new NetworkPlan(
            nets(net("default", "10.50.0.1/24")),
            fws(),
            List.of(host("default", false, "192.168.122.1/24")),
            List.of(),
            List.of(),
            null,
            Map.of(),
            false);
    assertThat(p.getNetTaken()).containsOnlyKeys("default");
    assertThat(p.getNetCreate()).isEmpty();
    assertThat(p.getNetUpdate()).isEmpty();
  }

  @Test
  void aManagedNetworkLeavingTheInventoryIsDeletedOnlyWhenUnused() {
    List<NetState> hostNets =
        List.of(host("old", true, "10.70.0.1/24"), host("busy", true, "10.71.0.1/24"));
    FirewallSpec gone = fw("gone");
    FirewallSpec used = fw("used");
    NetworkPlan p =
        new NetworkPlan(
            nets(),
            fws(),
            hostNets,
            List.of(
                hostFilter(gone), hostFilter(used), new FilterState("clean-traffic", null, null)),
            List.of(domain("vm1", false, new Nic("busy", "mnem-used", Map.of()))),
            null,
            Map.of(),
            false);
    assertThat(p.getNetDelete()).containsExactly("old");
    assertThat(p.getNetKept()).containsEntry("busy", List.of("vm1"));
    assertThat(p.getFilterDelete()).containsExactly("mnem-gone");
    assertThat(p.getFilterKept()).containsEntry("mnem-used", List.of("vm1"));
  }

  @Test
  void noDeleteKeepsEverything() {
    NetworkPlan p =
        new NetworkPlan(
            nets(),
            fws(),
            List.of(host("old", true, "10.70.0.1/24")),
            List.of(hostFilter(fw("gone"))),
            List.of(),
            null,
            Map.of(),
            true);
    assertThat(p.getNetDelete()).isEmpty();
    assertThat(p.getFilterDelete()).isEmpty();
  }

  @Test
  void aVmTheRunDeletesNoLongerHoldsItsNetwork() {
    DomainState vm =
        new DomainState(
                "vm1",
                1,
                512,
                "vm1",
                "1",
                "mnemosyne",
                List.of(),
                false,
                false,
                List.of(),
                List.of(),
                InitMarker.ASSUMED_FINISHED)
            .withNics(List.of(new Nic("old", "mnem-gone", Map.of())));
    Plan vmPlan = new Plan(List.of(vm), Map.of(), false);
    NetworkPlan p =
        new NetworkPlan(
            nets(),
            fws(),
            List.of(host("old", true, "10.70.0.1/24")),
            List.of(hostFilter(fw("gone"))),
            List.of(vm),
            vmPlan,
            Map.of(),
            false);
    assertThat(p.getNetDelete()).containsExactly("old");
    assertThat(p.getFilterDelete()).containsExactly("mnem-gone");
  }

  @Test
  void theNetworksBlockIsPrintedBeforeTheVms() {
    NetworkPlan p =
        new NetworkPlan(
            nets(net("agents", "10.50.0.1/24")),
            fws(fw("egress")),
            List.of(host("old", true, "10.70.0.1/24")),
            List.of(),
            List.of(),
            null,
            Map.of(),
            false);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream stdout = System.out;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      p.print("hv01", new Preflight());
    } finally {
      System.setOut(stdout);
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("[ hv01 · networks ]  create: 2, delete: 1")
        .contains("+ network agents  (nat 10.50.0.1/24)")
        .contains(
            "+ firewall egress  (default drop, 0 rules, anti-spoofing, seed allowed, dhcp allowed)")
        .contains("- network old");
  }
}
