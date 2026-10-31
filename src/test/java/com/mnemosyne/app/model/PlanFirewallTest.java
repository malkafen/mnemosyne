package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.mnemosyne.app.model.DomainState.Nic;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The firewall of an existing VM, as the VM plan diffs it. */
@DisplayName("Plan: firewall of an existing VM")
class PlanFirewallTest {

  private static Server server(String firewall) {
    Server s = new Server();
    s.setId("vm1");
    s.setCpu(1);
    s.setRam(512);
    s.setIp("10.50.0.10/24");
    s.setNetwork("agents");
    s.setFirewall(firewall);
    s.setSeedIp("192.0.2.5");
    s.setSeedPort(8080);
    return s;
  }

  private static DomainState domain(Nic nic) {
    return new DomainState(
            "vm1",
            1,
            512,
            "vm1",
            "1",
            "mnemosyne",
            List.of(),
            true,
            false,
            List.of(),
            List.of(),
            InitMarker.ASSUMED_FINISHED)
        .withNics(List.of(nic));
  }

  private static Plan plan(Server s, Nic nic) {
    return new Plan(List.of(domain(nic)), Map.of("vm1", s), false);
  }

  @Test
  void aFirewallIsPutOnAVmThatHasNone() {
    Plan p = plan(server("egress"), new Nic("agents", null, Map.of()));
    assertThat(p.getToUpdate().get("vm1").diff()).isEqualTo("firewall none->egress");
  }

  @Test
  void aMatchingFirewallIsNoChange() {
    Server s = server("egress");
    Plan p = plan(s, new Nic("agents", "mnem-egress", s.filterParams()));
    assertThat(p.getToUpdate()).isEmpty();
  }

  @Test
  void changedParametersAreAChange() {
    Server s = server("egress");
    Plan p = plan(s, new Nic("agents", "mnem-egress", Map.of("IP", "10.50.0.99")));
    assertThat(p.getToUpdate().get("vm1").diff()).isEqualTo("firewall egress parameters");
  }

  @Test
  void ourFirewallIsRemovedWhenTheInventoryDropsIt() {
    Plan p = plan(server(null), new Nic("agents", "mnem-egress", Map.of()));
    assertThat(p.getToUpdate().get("vm1").diff()).isEqualTo("firewall egress->none");
  }

  @Test
  void aFilterPutOnByHandIsLeftAlone() {
    Plan p = plan(server(null), new Nic("agents", "clean-traffic", Map.of()));
    assertThat(p.getToUpdate()).isEmpty();
  }

  @Test
  void aVmOnAnotherNetworkGetsANoteNotAMove() {
    Server s = server(null);
    Plan p = plan(s, new Nic("default", null, Map.of()));
    assertThat(p.getToUpdate()).isEmpty();
    assertThat(p.getNotes().get("vm1"))
        .singleElement()
        .asString()
        .contains("network 'default' on the host, 'agents' in the inventory - not moved");
  }
}
