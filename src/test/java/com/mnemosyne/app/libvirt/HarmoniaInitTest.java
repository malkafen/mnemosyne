package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mnemosyne.app.http.CloudInitServer;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.InitMarker;
import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.model.Templates;
import com.mnemosyne.app.utils.XmlUtil;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;
import org.libvirt.StorageVol;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The life of {@code <mnem:init>} through the reconciler: defined pending, turned finished by the
 * guest's phone_home and nothing else, and written adopted by {@code --join}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Harmonia, init marker")
public class HarmoniaInitTest {

  private static final String IMAGES = "/var/lib/libvirt/images/";

  @Mock Connect connect;
  @Mock Domain domain;
  @Mock StoragePool pool;
  @Mock StorageVol volume;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private PrintStream stdout;

  @BeforeEach
  void captureStdout() {
    stdout = System.out;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void restoreStdout() {
    System.setOut(stdout);
    CloudInitServer.unregister("qa-a");
  }

  private static Templates fixtures() {
    Templates t = new Templates();
    t.setServerTmpl(HarmoniaInitTest.class.getResource("/server-template.xml").getPath());
    t.setVolTmpl(HarmoniaInitTest.class.getResource("/volume.xml").getPath());
    t.setMetaDataTmpl(HarmoniaInitTest.class.getResource("/meta-data.yml").getPath());
    t.setUserDataTmpl(HarmoniaInitTest.class.getResource("/user-data.yml").getPath());
    t.setNetworkConfigTmpl(HarmoniaInitTest.class.getResource("/network-config.yml").getPath());
    return t;
  }

  private static Server server() {
    Server s = new Server();
    s.setId("qa-a");
    s.setCpu(2);
    s.setRam(1024);
    s.setDisk(10);
    s.setPool("default");
    s.setNetwork("host-bridge");
    s.setIp("192.168.17.40/24");
    s.setGateway("192.168.17.1");
    s.setVolLookup("debian-13-genericcloud.qcow2");
    s.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    s.setTemplates(fixtures());
    return s;
  }

  /** Creates qa-a on an empty host, its root volume already in the pool; returns the XML. */
  private String create() throws LibvirtException {
    when(connect.listAllDomains(0)).thenReturn(new Domain[0]);
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.storageVolLookupByName("qa-a.qcow2")).thenReturn(volume);
    when(volume.getPath()).thenReturn(IMAGES + "qa-a.qcow2");
    when(connect.domainDefineXML(anyString())).thenReturn(domain);

    Harmonia harmonia = new Harmonia("bm05", connect);
    harmonia.plan(Map.of("qa-a", server()), false);
    harmonia.reconcile(1);

    ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
    verify(connect).domainDefineXML(xml.capture());
    return xml.getValue();
  }

  private static int phoneHome(String token) throws Exception {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getRequestURI())
        .thenReturn(URI.create("/cloud-init/qa-a/" + token + "/phone-home"));
    // A refused token is answered before the method or the body are looked at.
    lenient().when(exchange.getRequestMethod()).thenReturn("POST");
    lenient().when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
    lenient().when(exchange.getResponseHeaders()).thenReturn(new Headers());
    when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
    new CloudInitServer.CloudInitHandler().handle(exchange);
    ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
    verify(exchange).sendResponseHeaders(status.capture(), anyLong());
    return status.getValue();
  }

  @Test
  void aNewDomainIsDefinedPending_andItsPhoneHomeMakesItFinished_keepingTheDiskRecords()
      throws Exception {
    // Arrange
    String defined = create();
    InitMarker pending = XmlUtil.getShortState(defined).init();
    assertThat(pending.isPending()).isTrue();
    assertThat(defined).contains("/cloud-init/qa-a/" + pending.token() + "/");
    when(connect.domainLookupByName("qa-a")).thenReturn(domain);
    when(domain.isActive()).thenReturn(1);
    // Act
    int status = phoneHome(pending.token());
    // Assert
    assertThat(status).isEqualTo(200);
    ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
    verify(domain)
        .setMetadata(
            anyInt(),
            meta.capture(),
            eq("mnem"),
            eq(XmlUtil.MNEM_NS),
            eq(Domain.ModificationImpact.CONFIG | Domain.ModificationImpact.LIVE));
    assertThat(meta.getValue())
        .contains("<serverId>qa-a</serverId>")
        .contains("<disks></disks>")
        .contains("<reused-disks><volume path='" + IMAGES + "qa-a.qcow2'/></reused-disks>")
        .contains(
            "<init state='finished' token='"
                + pending.token()
                + "' created='"
                + pending.created()
                + "' finished='");
    assertThat(CloudInitServer.initialized("qa-a")).isTrue();
  }

  @Test
  void aPhoneHomeWithAnotherToken_leavesTheDomainPending() throws Exception {
    // Arrange
    create();
    // Act
    int status = phoneHome("f".repeat(32));
    // Assert
    assertThat(status).isEqualTo(404);
    verify(domain, never()).setMetadata(anyInt(), anyString(), anyString(), anyString(), anyInt());
    assertThat(CloudInitServer.initialized("qa-a")).isFalse();
  }

  @Test
  void aFailedWrite_isAnswered500_andTheGuestIsNotCountedAsDone() throws Exception {
    // Arrange
    InitMarker pending = XmlUtil.getShortState(create()).init();
    when(connect.domainLookupByName("qa-a")).thenThrow(mock(LibvirtException.class));
    // Act
    int status = phoneHome(pending.token());
    // Assert
    assertThat(status).isEqualTo(500);
    assertThat(CloudInitServer.initialized("qa-a")).isFalse();
  }

  @Test
  void joinWritesAdopted_withoutAToken() throws Exception {
    // Arrange: an unmanaged domain of the inventory's name
    when(connect.listAllDomains(0)).thenReturn(new Domain[] {domain});
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE))
        .thenReturn(
            "<domain><name>qa-a</name><memory>1048576</memory><vcpu>2</vcpu><devices/></domain>");
    when(domain.getAutostart()).thenReturn(false);
    when(connect.domainLookupByName("qa-a")).thenReturn(domain);
    when(domain.isActive()).thenReturn(0);
    Harmonia harmonia = new Harmonia("bm05", connect);
    harmonia.plan(Map.of("qa-a", server()), false);
    // Act
    harmonia.join(1);
    // Assert
    ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
    verify(domain).setMetadata(anyInt(), meta.capture(), eq("mnem"), eq(XmlUtil.MNEM_NS), anyInt());
    DomainState adopted =
        XmlUtil.getShortState(
            "<domain><name>qa-a</name><memory>1048576</memory><vcpu>2</vcpu><metadata>"
                + meta.getValue()
                    .replace("<mnemosyne>", "<mnem:mnemosyne xmlns:mnem='" + XmlUtil.MNEM_NS + "'>")
                    .replace("</mnemosyne>", "</mnem:mnemosyne>")
                    .replaceAll("<(/?)(?!mnem:)(\\w)", "<$1mnem:$2")
                + "</metadata><devices/></domain>");
    assertThat(adopted.init().state()).isEqualTo("adopted");
    assertThat(adopted.init().token()).isNull();
    assertThat(adopted.init().created()).isNotNull();
  }
}
