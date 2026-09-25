package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.mnemosyne.app.utils.XmlUtil;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ServerTest {

  /** The fixture template is deliberately still on unit='MiB'. */
  private static Server sampleServer(long ramMiB) {
    Templates t = new Templates();
    t.setServerTmpl(ServerTest.class.getResource("/server-template.xml").getPath());

    Server s = new Server();
    s.setInit(
        new InitMarker(
            "pending", "0123456789abcdef0123456789abcdef", "2026-09-25T10:00:00Z", null));
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

  @Test
  void buildServerXml_aReusedRootVolume_isRecordedAsReused_notAsOwned() {
    // The volume was in the pool before this VM: deleting the VM must leave it where it was.
    // Arrange
    Server s = sampleServer(2048);
    s.getReusedVolPaths().add("/var/lib/libvirt/images/web-01.qcow2");
    // Act
    DomainState state = XmlUtil.getShortState(s.buildServerXml());
    // Assert
    assertThat(state.ownedPaths()).isEmpty();
    assertThat(state.reusedPaths()).containsExactly("/var/lib/libvirt/images/web-01.qcow2");
  }

  @Test
  void buildServerXml_aCreatedRootVolume_isOwned_andNothingIsRecordedAsReused() {
    // Act
    DomainState state = XmlUtil.getShortState(sampleServer(2048).buildServerXml());
    // Assert
    assertThat(state.ownedPaths()).containsExactly("/var/lib/libvirt/images/web-01.qcow2");
    assertThat(state.reusedPaths()).isEmpty();
  }

  @Test
  void buildMnemosyneMetadataXml_keepsReusedVolumesOutOfMnemDisks() {
    // A build that predates <mnem:reused-disks> reads every <mnem:disk> as its own, so a reused
    // volume must not be one.
    // Act
    String xml =
        sampleServer(2048)
            .buildMnemosyneMetadataXml(List.of("/img/a.qcow2"), List.of("/img/b.qcow2"), null);
    // Assert
    assertThat(xml)
        .contains("<disks><disk path='/img/a.qcow2'/></disks>")
        .contains("<reused-disks><volume path='/img/b.qcow2'/></reused-disks>")
        .doesNotContain("<disk path='/img/b.qcow2'/>");
  }

  @Test
  void buildServerXml_definesTheDomainPending_withTheTokenInItsSeedUrl() {
    // The domain never exists without a marker, and only the guest it was built for knows the
    // token.
    // Act
    String xml = sampleServer(2048).buildServerXml();
    // Assert
    assertThat(XmlUtil.getShortState(xml).init())
        .isEqualTo(
            new InitMarker(
                "pending", "0123456789abcdef0123456789abcdef", "2026-09-25T10:00:00Z", null));
    assertThat(xml)
        .contains(
            "ds=nocloud;s=http://192.0.2.5:8080/cloud-init/web-01.example.lan/"
                + "0123456789abcdef0123456789abcdef/");
  }

  @Test
  void buildUserDataYaml_phonesHomeWithTheToken() {
    // Arrange
    Templates t = new Templates();
    t.setUserDataTmpl(ServerTest.class.getResource("/user-data.yml").getPath());
    Server s = sampleServer(2048);
    s.setTemplates(t);
    // Act & Assert
    assertThat(s.buildUserDataYaml())
        .contains(
            "http://192.0.2.5:8080/cloud-init/web-01.example.lan/"
                + "0123456789abcdef0123456789abcdef/phone-home");
  }

  @Test
  void buildServerXml_withoutAMarker_refusesToBuildADomainThatWouldBeBlocked() {
    // Arrange
    Server s = sampleServer(2048);
    s.setInit(null);
    // Act & Assert
    org.assertj.core.api.Assertions.assertThatThrownBy(s::buildServerXml)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void buildMnemosyneMetadataXml_carriesTheInitMarker() {
    // Act
    String xml =
        sampleServer(2048)
            .buildMnemosyneMetadataXml(
                List.of(),
                List.of(),
                new InitMarker("adopted", null, "2026-09-25T10:00:00Z", null));
    // Assert
    assertThat(xml).contains("<init state='adopted' created='2026-09-25T10:00:00Z'/></mnemosyne>");
  }
}
