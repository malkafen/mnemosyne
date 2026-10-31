package com.mnemosyne.app.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mnemosyne.app.exception.XmlParseException;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.GuestNetwork;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("XmlUtil, network")
public class XmlUtilNetworkTest {

  private static String domain(String metadataExtra, String interfaces) {
    return """
        <domain type='kvm'>
          <name>web-01</name>
          <metadata>
            <mnem:mnemosyne xmlns:mnem="https://mnemosyne.dev/schema/v1">
              <mnem:managedBy>mnemosyne</mnem:managedBy>
              <mnem:serverId>web-01</mnem:serverId>
              <mnem:init state='finished' token='t'/>
              %s
            </mnem:mnemosyne>
          </metadata>
          <memory unit='KiB'>2097152</memory>
          <vcpu placement='static'>2</vcpu>
          <devices>
            %s
          </devices>
        </domain>
        """
        .formatted(metadataExtra, interfaces);
  }

  private static final String LAN =
      """
      <interface type='network'>
        <mac address='52:54:00:46:96:dc'/>
        <source network='lan'/>
        <model type='virtio'/>
      </interface>
      """;

  private static final String LIVE_LAN =
      """
      <interface type='network'>
        <mac address='52:54:00:46:96:dc'/>
        <source network='lan' portid='0c4c22c0-b37f-429d-b47b-d9c7db86a6b5' bridge='br0'/>
        <target dev='vnet1'/>
        <model type='virtio'/>
        <alias name='net0'/>
      </interface>
      """;

  @Test
  void readsTheRecordedAddressAndGateway() {
    DomainState s =
        XmlUtil.getShortState(domain("<mnem:network ip='10.0.0.5/24' gateway='10.0.0.1'/>", LAN));
    assertThat(s.guestNetwork()).isEqualTo(new GuestNetwork("10.0.0.5/24", "10.0.0.1"));
    assertThat(s.interfaceNetwork()).isEqualTo("lan");
  }

  @Test
  void aRecordWithoutGatewayReadsAsNone() {
    DomainState s = XmlUtil.getShortState(domain("<mnem:network ip='10.0.0.5/24'/>", LAN));
    assertThat(s.guestNetwork()).isEqualTo(new GuestNetwork("10.0.0.5/24", null));
  }

  @Test
  void noRecordReadsAsNull_notAsAssumed() {
    DomainState s = XmlUtil.getShortState(domain("", LAN));
    assertThat(s.guestNetwork()).isNull();
  }

  @Test
  void aRecordWithoutAnAddressReadsAsNull() {
    DomainState s = XmlUtil.getShortState(domain("<mnem:network gateway='10.0.0.1'/>", LAN));
    assertThat(s.guestNetwork()).isNull();
  }

  @Test
  void onlyTheFirstInterfaceCounts() {
    String second = LAN.replace("'lan'", "'dmz'");
    assertThat(XmlUtil.getShortState(domain("", second + LAN)).interfaceNetwork()).isEqualTo("dmz");
  }

  @Test
  void aBridgeInterfaceHasNoLibvirtNetwork() {
    String bridge = "<interface type='bridge'><source bridge='br0'/></interface>";
    assertThat(XmlUtil.getShortState(domain("", bridge)).interfaceNetwork()).isNull();
  }

  @Test
  void aDomainWithoutInterfacesHasNoLibvirtNetwork() {
    assertThat(XmlUtil.getShortState(domain("", "")).interfaceNetwork()).isNull();
  }

  @Test
  void anUnmanagedDomainStillReportsItsInterface() {
    String xml =
        "<domain><name>x</name><memory>1024</memory><vcpu>1</vcpu><devices>"
            + LAN
            + "</devices></domain>";
    DomainState s = XmlUtil.getShortState(xml);
    assertThat(s.interfaceNetwork()).isEqualTo("lan");
    assertThat(s.guestNetwork()).isNull();
  }

  @Test
  void anInterfaceInsideForeignMetadataIsIgnored() {
    String xml =
        domain("", LAN)
            .replace(
                "<metadata>",
                "<metadata><x:tool xmlns:x='urn:x'><interface type='network'>"
                    + "<source network='bogus'/></interface></x:tool>");
    assertThat(XmlUtil.getShortState(xml).interfaceNetwork()).isEqualTo("lan");
    assertThat(XmlUtil.interfaceWithNetwork(xml, "dmz")).doesNotContain("bogus");
  }

  @Test
  void theGuestAgentChannelIsDetected() {
    String agent =
        "<channel type='unix'><target type='virtio' name='org.qemu.guest_agent.0'/></channel>";
    String spice =
        "<channel type='spicevmc'><target type='virtio' name='com.redhat.spice.0'/></channel>";
    assertThat(XmlUtil.getShortState(domain("", LAN + agent)).guestAgent()).isTrue();
    assertThat(XmlUtil.getShortState(domain("", LAN + spice)).guestAgent()).isFalse();
    assertThat(XmlUtil.getShortState(domain("", LAN)).guestAgent()).isFalse();
  }

  @Test
  void aSnapshotBuiltInCodeAssumesAnAgent() {
    assertThat(
            new DomainState("x", 1, 1024, "x", "1", "mnemosyne", java.util.List.of(), true, false)
                .guestAgent())
        .isTrue();
  }

  @Test
  void rewiringKeepsTheMacAndModel_andChangesOnlyTheNetwork() {
    String iface = XmlUtil.interfaceWithNetwork(domain("", LAN), "dmz");
    assertThat(iface)
        .startsWith("<interface")
        .contains("52:54:00:46:96:dc")
        .contains("network=\"dmz\"")
        .contains("virtio")
        .doesNotContain("lan")
        .doesNotContain("<?xml");
  }

  @Test
  void rewiringDropsWhatBelongsToTheOldNetwork() {
    String iface = XmlUtil.interfaceWithNetwork(domain("", LIVE_LAN), "dmz");
    assertThat(iface).contains("network=\"dmz\"").doesNotContain("portid").doesNotContain("br0");
  }

  @Test
  void rewiringABridgeInterfaceIsRefused() {
    String bridge = "<interface type='bridge'><source bridge='br0'/></interface>";
    assertThatThrownBy(() -> XmlUtil.interfaceWithNetwork(domain("", bridge), "dmz"))
        .isInstanceOf(XmlParseException.class)
        .hasMessageContaining("interface type='network'");
  }

  @Test
  void rewiringAnInterfaceWithoutSourceIsRefused() {
    String noSource = "<interface type='network'><model type='virtio'/></interface>";
    assertThatThrownBy(() -> XmlUtil.interfaceWithNetwork(domain("", noSource), "dmz"))
        .isInstanceOf(XmlParseException.class)
        .hasMessageContaining("<source>");
  }
}
