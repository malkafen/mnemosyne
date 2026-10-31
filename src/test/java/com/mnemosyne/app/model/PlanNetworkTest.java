package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Plan, guest network")
public class PlanNetworkTest {

  private static Server server(String ip, String gateway, String network) {
    Server s = new Server();
    s.setId("web-01");
    s.setName("web-01");
    s.setCpu(2);
    s.setRam(2048);
    s.setIp(ip);
    s.setGateway(gateway);
    s.setNetwork(network);
    return s;
  }

  private static DomainState domain(String interfaceNetwork, GuestNetwork recorded) {
    return new DomainState("web-01", 2, 2048, "web-01", "1", "mnemosyne", List.of(), true, false)
        .withNetwork(interfaceNetwork, recorded);
  }

  private static Plan plan(DomainState d, Server s) {
    return new Plan(List.of(d), Map.of(s.getId(), s), false);
  }

  @Test
  void aMatchingDomainHasNothingToDo() {
    Plan plan =
        plan(
            domain("lan", new GuestNetwork("10.0.0.5/24", "10.0.0.1")),
            server("10.0.0.5/24", "10.0.0.1", "lan"));
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getNotes()).isEmpty();
  }

  @Test
  void aNewIpIsAnUpdateThatShowsBothAddresses() {
    Plan plan =
        plan(
            domain("lan", new GuestNetwork("10.0.0.5/24", "10.0.0.1")),
            server("10.0.0.6/24", "10.0.0.1", "lan"));
    Plan.Update u = plan.getToUpdate().get("web-01");
    assertThat(u.addressChanged()).isTrue();
    assertThat(u.networkChanged()).isFalse();
    assertThat(u.diff()).isEqualTo("ip 10.0.0.5/24->10.0.0.6/24");
  }

  @Test
  void aGatewayChangeIsShownOnItsOwn() {
    Plan plan =
        plan(
            domain("lan", new GuestNetwork("10.0.0.5/24", "10.0.0.1")),
            server("10.0.0.5/24", "10.0.0.254", "lan"));
    assertThat(plan.getToUpdate().get("web-01").diff()).isEqualTo("gateway 10.0.0.1->10.0.0.254");
  }

  @Test
  void aMissingGatewayOnEitherSideReadsAsNone() {
    Plan removed =
        plan(
            domain("lan", new GuestNetwork("10.0.0.5/24", "10.0.0.1")),
            server("10.0.0.5/24", null, "lan"));
    Plan added =
        plan(
            domain("lan", new GuestNetwork("10.0.0.5/24", null)),
            server("10.0.0.5/24", "10.0.0.1", "lan"));
    assertThat(removed.getToUpdate().get("web-01").diff()).isEqualTo("gateway 10.0.0.1->none");
    assertThat(added.getToUpdate().get("web-01").diff()).isEqualTo("gateway none->10.0.0.1");
  }

  @Test
  void aNewLibvirtNetworkIsAnUpdate() {
    Plan plan =
        plan(
            domain("lan", new GuestNetwork("10.0.0.5/24", "10.0.0.1")),
            server("10.0.0.5/24", "10.0.0.1", "dmz"));
    Plan.Update u = plan.getToUpdate().get("web-01");
    assertThat(u.networkChanged()).isTrue();
    assertThat(u.addressChanged()).isFalse();
    assertThat(u.diff()).isEqualTo("network lan->dmz");
  }

  @Test
  void everyNetworkChangeIsListedInOneLine() {
    Plan plan =
        plan(
            domain("lan", new GuestNetwork("10.0.0.5/24", "10.0.0.1")),
            server("10.1.0.5/24", "10.1.0.1", "dmz"));
    assertThat(plan.getToUpdate().get("web-01").diff())
        .isEqualTo("network lan->dmz, ip 10.0.0.5/24->10.1.0.5/24, gateway 10.0.0.1->10.1.0.1");
  }

  @Test
  void anInterfaceThatIsNotOnALibvirtNetworkIsLeftAlone() {
    Plan plan =
        plan(
            domain(null, new GuestNetwork("10.0.0.5/24", "10.0.0.1")),
            server("10.0.0.5/24", "10.0.0.1", "dmz"));
    assertThat(plan.getToUpdate()).isEmpty();
  }

  @Test
  void anUnrecordedAddressIsNeverChanged_onlyNoted() {
    Plan plan = plan(domain("lan", null), server("10.0.0.6/24", "10.0.0.1", "lan"));
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getNotes().get("web-01")).containsExactly(Plan.UNRECORDED_NETWORK_NOTE);
  }

  @Test
  void anUnrecordedAddressDoesNotHoldBackANetworkChange() {
    Plan plan = plan(domain("lan", null), server("10.0.0.6/24", "10.0.0.1", "dmz"));
    Plan.Update u = plan.getToUpdate().get("web-01");
    assertThat(u.networkChanged()).isTrue();
    assertThat(u.addressChanged()).isFalse();
    assertThat(u.notes()).containsExactly(Plan.UNRECORDED_NETWORK_NOTE);
  }

  @Test
  void aSnapshotBuiltInCodeAssumesTheAddressIsCurrent() {
    DomainState d =
        new DomainState("web-01", 2, 2048, "web-01", "1", "mnemosyne", List.of(), true, false);
    Plan plan = plan(d, server("10.0.0.6/24", "10.0.0.1", "default"));
    assertThat(d.guestNetwork().assumed()).isTrue();
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getNotes()).isEmpty();
  }

  @Test
  void aPendingDomainIsNotDiffed() {
    DomainState d =
        new DomainState(
                "web-01",
                2,
                2048,
                "web-01",
                "1",
                "mnemosyne",
                List.of(),
                true,
                false,
                List.of(),
                List.of(),
                InitMarker.pending(java.time.Instant.EPOCH))
            .withNetwork("lan", new GuestNetwork("10.0.0.5/24", "10.0.0.1"));
    Plan plan = plan(d, server("10.0.0.6/24", "10.0.0.1", "dmz"));
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getPendingInit()).containsKey("web-01");
  }
}
