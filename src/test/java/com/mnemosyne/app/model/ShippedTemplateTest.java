package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.mnemosyne.app.utils.XmlUtil;
import java.util.List;
import java.util.Map;
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
  void shippedServerTemplate_extraDisksInheritTheRootDisksDiscardSetting() {
    // The extra disk is a clone of the root <disk>, so the sparse-image pairing between
    // discard='unmap' here and allocation 0 in volume.xml holds for data disks too. Without it
    // fstrim inside the guest would never return freed space to the host.
    // Arrange
    Server s = sampleServer();
    ExtraDisk d = new ExtraDisk();
    d.setName("data");
    d.setSize(40);
    d.setPool("default");
    s.setExtraDisks(List.of(d));
    s.setExtraVolPaths(Map.of("data", "/var/lib/libvirt/images/web-01.example.lan-data.qcow2"));
    // Act
    String xml = s.buildServerXml();
    // Assert
    assertThat(XmlUtil.disks(parse(xml)))
        .containsExactly(
            new DomainState.Disk("vda", "/var/lib/libvirt/images/web-01.example.lan", null),
            new DomainState.Disk(
                "vdb", "/var/lib/libvirt/images/web-01.example.lan-data.qcow2", "data"));
    assertThat(XmlUtil.usedDiskTargets(xml)).containsExactly("vda", "vdb");

    // Two disks, both with discard='unmap' from the one place the template states it.
    assertThat(xml.split("discard=\"unmap\"", -1)).hasSize(3);
  }

  @Test
  void shippedVolumeTemplate_alsoDescribesABlankDataVolume() {
    // The same template makes the extra disks; only the way the volume is created differs.
    // Arrange
    Server s = sampleServer();
    ExtraDisk d = new ExtraDisk();
    d.setName("data");
    d.setSize(40);
    d.setPool("nvme");
    s.setExtraDisks(List.of(d));
    // Act
    String xml = s.buildExtraVolumeXml(d);
    // Assert
    assertThat(xml)
        .contains("<name>web-01.example.lan-data.qcow2</name>")
        .contains("<capacity unit=\"GiB\">40</capacity>")
        .contains("<allocation unit=\"bytes\">0</allocation>")
        .contains("<format type=\"qcow2\"/>");
  }

  private static org.w3c.dom.Document parse(String xml) {
    try {
      javax.xml.parsers.DocumentBuilderFactory f =
          javax.xml.parsers.DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      return f.newDocumentBuilder()
          .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
