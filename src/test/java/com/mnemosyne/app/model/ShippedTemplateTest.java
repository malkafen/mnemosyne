package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.mnemosyne.app.utils.XmlUtil;
import org.junit.jupiter.api.Test;

/**
 * Guards the reference templates in {@code templates/} — the ones baked into the Docker image and
 * copied by users. {@link ServerTest} runs against a stripped-down fixture instead, so without this
 * an edit to the shipped XML could break provisioning without failing the build.
 */
public class ShippedTemplateTest {

  private static Server sampleServer() {
    Templates t = new Templates();
    t.setServerTmpl("templates/server.xml");
    t.setVolTmpl("templates/volume.xml");

    Server s = new Server();
    s.setId("web-01.example.lan");
    s.setTemplates(t);
    s.setCpu(2);
    s.setRam(2048);
    s.setDisk(30);
    s.setVolPath("/var/lib/libvirt/images/web-01.example.lan");
    s.setNetwork("host-bridge");
    s.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    return s;
  }

  @Test
  void shippedServerTemplate_fillsEveryValueMnemosyneOwns() {
    // Act
    String xml = sampleServer().buildServerXml();
    // Assert
    assertThat(xml)
        .contains("<name>web-01.example.lan</name>")
        .contains("<memory unit=\"KiB\">2097152</memory>")
        .contains("ds=nocloud;s=http://192.0.2.5:8080/cloud-init/web-01.example.lan/")
        .contains("file=\"/var/lib/libvirt/images/web-01.example.lan\"")
        .contains("network=\"host-bridge\"");

    DomainState state = XmlUtil.getShortState(xml);
    assertThat(state.cpu()).isEqualTo(2);
    assertThat(state.ram()).isEqualTo(2048);
    assertThat(state.serverId()).isEqualTo("web-01.example.lan");
    assertThat(state.managed()).isTrue();
  }

  @Test
  void shippedServerTemplate_keepsAcpiSoShutdownIsGraceful() {
    // DomainOps stops a VM with a shutdown request, which is an ACPI power-button press.
    // Drop <acpi/> and every stop degrades into the 60s timeout plus a hard destroy.
    assertThat(sampleServer().buildServerXml()).contains("<acpi/>");
  }

  @Test
  void shippedVolumeTemplate_staysSparseAndInGib() {
    // The capacity unit is NOT rewritten by Server#buildVolumeXml, so `disk: 30` only means
    // 30 GiB as long as the template says GiB.
    // Act
    String xml = sampleServer().buildVolumeXml();
    // Assert
    assertThat(xml)
        .contains("<capacity unit=\"GiB\">30</capacity>")
        .contains("<allocation unit=\"bytes\">0</allocation>");
  }
}
