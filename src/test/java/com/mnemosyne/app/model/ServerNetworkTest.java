package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mnemosyne.app.exception.TemplateException;
import com.mnemosyne.app.utils.XmlUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Server, guest network")
public class ServerNetworkTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static Server server(String ip, String gateway) {
    Templates t = new Templates();
    t.setServerTmpl(ServerNetworkTest.class.getResource("/server-template.xml").getPath());
    t.setNetworkConfigTmpl(ServerNetworkTest.class.getResource("/network-config.yml").getPath());

    Server s = new Server();
    s.setInit(
        new InitMarker(
            "pending", "0123456789abcdef0123456789abcdef", "2026-09-25T10:00:00Z", null));
    s.setId("web-01");
    s.setTemplates(t);
    s.setCpu(2);
    s.setRam(2048);
    s.setIp(ip);
    s.setGateway(gateway);
    s.setVolPath("/var/lib/libvirt/images/web-01.qcow2");
    s.setNetwork("lan");
    s.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    return s;
  }

  @Test
  void aNewDomainRecordsItsAddressAndNetwork() {
    DomainState state = XmlUtil.getShortState(server("10.0.0.5/24", "10.0.0.1").buildServerXml());
    assertThat(state.guestNetwork()).isEqualTo(new GuestNetwork("10.0.0.5/24", "10.0.0.1"));
    assertThat(state.interfaceNetwork()).isEqualTo("lan");
  }

  @Test
  void aNewDomainWithoutAGatewayRecordsNone() {
    String xml = server("10.0.0.5/24", null).buildServerXml();
    assertThat(xml).doesNotContain("gateway=");
    assertThat(XmlUtil.getShortState(xml).guestNetwork())
        .isEqualTo(new GuestNetwork("10.0.0.5/24", null));
  }

  @Test
  void aJustCreatedDomainHasNoNetworkDrift() {
    Server s = server("10.0.0.5/24", "10.0.0.1");
    Plan.Update u = new Plan.Update(s, XmlUtil.getShortState(s.buildServerXml()));
    assertThat(u.networkChanged()).isFalse();
    assertThat(u.addressChanged()).isFalse();
  }

  @Test
  void theMetadataRecordCarriesTheNetwork() {
    String xml =
        server("10.0.0.5/24", "10.0.0.1")
            .buildMnemosyneMetadataXml(
                List.of(), List.of(), null, new GuestNetwork("10.0.0.6/24", null));
    assertThat(xml).endsWith("<network ip='10.0.0.6/24'/></mnemosyne>");
  }

  @Test
  void theMetadataRecordLeavesAnUnknownOrAssumedNetworkOut() {
    Server s = server("10.0.0.5/24", "10.0.0.1");
    assertThat(s.buildMnemosyneMetadataXml(List.of(), List.of(), null, null))
        .doesNotContain("<network");
    assertThat(
            s.buildMnemosyneMetadataXml(List.of(), List.of(), null, GuestNetwork.ASSUMED_CURRENT))
        .doesNotContain("<network");
  }

  @Test
  void theMetadataRecordReadsBack() {
    String record =
        server("10.0.0.5/24", "10.0.0.1")
            .buildMnemosyneMetadataXml(
                List.of(), List.of(), null, new GuestNetwork("10.0.0.6/24", "10.0.0.254"));
    String domain =
        "<domain><name>web-01</name><memory>2097152</memory><vcpu>2</vcpu><metadata>"
            + record.replace("<mnemosyne>", "<mnemosyne xmlns='https://mnemosyne.dev/schema/v1'>")
            + "</metadata></domain>";
    assertThat(XmlUtil.getShortState(domain).guestNetwork())
        .isEqualTo(new GuestNetwork("10.0.0.6/24", "10.0.0.254"));
  }

  @Test
  void theNetplanFileIsTheNetworkConfigUnderANetworkKey() throws IOException {
    JsonNode netplan = YAML.readTree(server("10.0.0.5/24", "10.0.0.1").buildNetplanYaml());
    JsonNode vif0 = netplan.path("network").path("ethernets").path("vif0");
    assertThat(netplan.path("network").path("version").asInt()).isEqualTo(2);
    assertThat(vif0.path("addresses").get(0).asText()).isEqualTo("10.0.0.5/24");
    assertThat(vif0.path("gateway4").asText()).isEqualTo("10.0.0.1");
    assertThat(vif0.path("set-name").asText()).isEqualTo("vif0");
    assertThat(vif0.path("nameservers").path("addresses").get(0).asText()).isEqualTo("8.8.8.8");
  }

  @Test
  void theNetplanFileOfAServerWithoutAGatewayHasNoGatewayKey() throws IOException {
    JsonNode vif0 =
        YAML.readTree(server("10.0.0.5/24", null).buildNetplanYaml())
            .path("network")
            .path("ethernets")
            .path("vif0");
    assertThat(vif0.has("gateway4")).isFalse();
  }

  @Test
  void theSeedNetworkConfigIsUnchanged() throws IOException {
    JsonNode config = YAML.readTree(server("10.0.0.5/24", "10.0.0.1").buildNetworkConfigYaml());
    assertThat(config.has("network")).isFalse();
    assertThat(config.path("ethernets").path("vif0").path("gateway4").asText())
        .isEqualTo("10.0.0.1");
  }

  @Test
  void aTemplateWithoutVif0CannotBeRendered(@TempDir Path dir) throws IOException {
    Path tmpl = dir.resolve("network-config.yml");
    Files.writeString(tmpl, "version: 2\nethernets:\n  eth0:\n    dhcp4: true\n");
    Server s = server("10.0.0.5/24", "10.0.0.1");
    s.getTemplates().setNetworkConfigTmpl(tmpl.toString());
    assertThatThrownBy(s::buildNetplanYaml)
        .isInstanceOf(TemplateException.class)
        .hasMessageContaining("vif0");
  }
}
