package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.mnemosyne.app.utils.XmlUtil;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ServerTest {

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
    String xml = sampleServer(2048).buildServerXml();
    assertThat(xml).contains("<memory unit=\"KiB\">2097152</memory>");
  }

  @Test
  void buildServerXml_staleMibTemplate_doesNotDefineA1024xDomain() {
    String xml = sampleServer(2048).buildServerXml();
    assertThat(xml).doesNotContain("unit=\"MiB\"");
    assertThat(XmlUtil.getShortState(xml).ram()).isEqualTo(2048);
  }

  @Test
  void buildServerXml_roundTripsThroughGetShortState() {
    Server s = sampleServer(4096);
    DomainState state = XmlUtil.getShortState(s.buildServerXml());
    assertThat(state.ram()).isEqualTo(4096);
    assertThat(state.cpu()).isEqualTo(2);
    assertThat(new Plan.Update(s, state).ramChanged()).isFalse();
  }

  @Test
  void buildServerXml_aReusedRootVolume_isRecordedAsReused_notAsOwned() {
    Server s = sampleServer(2048);
    s.getReusedVolPaths().add("/var/lib/libvirt/images/web-01.qcow2");
    DomainState state = XmlUtil.getShortState(s.buildServerXml());
    assertThat(state.ownedPaths()).isEmpty();
    assertThat(state.reusedPaths()).containsExactly("/var/lib/libvirt/images/web-01.qcow2");
  }

  @Test
  void buildServerXml_aCreatedRootVolume_isOwned_andNothingIsRecordedAsReused() {
    DomainState state = XmlUtil.getShortState(sampleServer(2048).buildServerXml());
    assertThat(state.ownedPaths()).containsExactly("/var/lib/libvirt/images/web-01.qcow2");
    assertThat(state.reusedPaths()).isEmpty();
  }

  @Test
  void buildMnemosyneMetadataXml_keepsReusedVolumesOutOfMnemDisks() {
    String xml =
        sampleServer(2048)
            .buildMnemosyneMetadataXml(
                List.of("/img/a.qcow2"), List.of("/img/b.qcow2"), null, null);
    assertThat(xml)
        .contains("<disks><disk path='/img/a.qcow2'/></disks>")
        .contains("<reused-disks><volume path='/img/b.qcow2'/></reused-disks>")
        .doesNotContain("<disk path='/img/b.qcow2'/>");
  }

  @Test
  void buildServerXml_definesTheDomainPending_withTheTokenInItsSeedUrl() {
    String xml = sampleServer(2048).buildServerXml();
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
    Templates t = new Templates();
    t.setUserDataTmpl(ServerTest.class.getResource("/user-data.yml").getPath());
    Server s = sampleServer(2048);
    s.setTemplates(t);
    assertThat(s.buildUserDataYaml())
        .contains(
            "http://192.0.2.5:8080/cloud-init/web-01.example.lan/"
                + "0123456789abcdef0123456789abcdef/phone-home");
  }

  @Test
  void buildServerXml_withoutAMarker_refusesToBuildADomainThatWouldBeBlocked() {
    Server s = sampleServer(2048);
    s.setInit(null);
    org.assertj.core.api.Assertions.assertThatThrownBy(s::buildServerXml)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void buildMnemosyneMetadataXml_carriesTheInitMarker() {
    String xml =
        sampleServer(2048)
            .buildMnemosyneMetadataXml(
                List.of(),
                List.of(),
                new InitMarker("adopted", null, "2026-09-25T10:00:00Z", null),
                null);
    assertThat(xml).contains("<init state='adopted' created='2026-09-25T10:00:00Z'/></mnemosyne>");
  }
}
