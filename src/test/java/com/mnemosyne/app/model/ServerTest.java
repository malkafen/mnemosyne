package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.mnemosyne.app.utils.XmlUtil;
import org.junit.jupiter.api.Test;

public class ServerTest {

  /** The fixture template is deliberately still on unit='MiB'. */
  private static Server sampleServer(long ramMiB) {
    Templates t = new Templates();
    t.setServerTmpl(ServerTest.class.getResource("/server-template.xml").getPath());

    Server s = new Server();
    s.setId("web-01.example.lan");
    s.setTemplates(t);
    s.setCpu(2);
    s.setRam(ramMiB);
    s.setVolPath("/var/lib/libvirt/images/web-01.qcow2");
    s.setNetwork("host-bridge");
    s.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    return s;
  }

  @Test
  void buildServerXml_writesRamAsKib() {
    // Act
    String xml = sampleServer(2048).buildServerXml();
    // Assert
    assertThat(xml).contains("<memory unit=\"KiB\">2097152</memory>");
  }

  @Test
  void buildServerXml_staleMibTemplate_doesNotDefineA1024xDomain() {
    // The old template shipped unit='MiB'; a custom copy may still carry it.
    // Act
    String xml = sampleServer(2048).buildServerXml();
    // Assert
    assertThat(xml).doesNotContain("unit=\"MiB\"");
    assertThat(XmlUtil.getShortState(xml).ram()).isEqualTo(2048);
  }

  @Test
  void buildServerXml_roundTripsThroughGetShortState() {
    // A freshly created domain must diff clean against the inventory that created it.
    // Arrange
    Server s = sampleServer(4096);
    // Act
    DomainState state = XmlUtil.getShortState(s.buildServerXml());
    // Assert
    assertThat(state.ram()).isEqualTo(4096);
    assertThat(state.cpu()).isEqualTo(2);
    assertThat(new Plan.Update(s, state).ramChanged()).isFalse();
  }
}
