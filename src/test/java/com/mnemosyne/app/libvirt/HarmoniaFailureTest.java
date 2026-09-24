package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.model.Templates;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;
import org.libvirt.StorageVol;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What one VM's failure is allowed to do to the rest of the run.
 *
 * <p>A template that cannot be filled in is raised as an unchecked exception from inside the
 * builders, several frames below the reconciler. Nothing about it is exotic — a copied template
 * with an element dropped is the ordinary way to get one — and the contract is that it costs the
 * operator that one VM: the entry is reported as skipped, every other entry is still applied, and
 * the report is printed either way.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Harmonia, a VM that cannot be created")
public class HarmoniaFailureTest {

  private static final String IMAGES = "/var/lib/libvirt/images/";

  @Mock Connect connect;
  @Mock Domain domain;
  @Mock StoragePool pool;
  @Mock StorageVol volume;

  @TempDir Path tmp;

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
  }

  private String report() {
    return out.toString(StandardCharsets.UTF_8);
  }

  /**
   * Test fixtures from {@code src/test/resources}, independent of the user-editable {@code
   * templates/}.
   */
  private static Templates fixtures() {
    Templates t = new Templates();
    t.setServerTmpl(HarmoniaFailureTest.class.getResource("/server-template.xml").getPath());
    t.setVolTmpl(HarmoniaFailureTest.class.getResource("/volume.xml").getPath());
    t.setMetaDataTmpl(HarmoniaFailureTest.class.getResource("/meta-data.yml").getPath());
    t.setUserDataTmpl(HarmoniaFailureTest.class.getResource("/user-data.yml").getPath());
    t.setNetworkConfigTmpl(HarmoniaFailureTest.class.getResource("/network-config.yml").getPath());
    return t;
  }

  /** The same templates, with the domain's {@code <interface>} dropped from the copy. */
  private Templates withoutInterface() throws IOException {
    String xml = Files.readString(Path.of(fixtures().getServerTmpl()), StandardCharsets.UTF_8);
    // Comments go first: the template documents the <interface> it fills in, and a comment
    // half-eaten by the next replacement would make the file unparseable for the wrong reason.
    String stripped =
        xml.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)<interface.*?</interface>", "");
    assertThat(stripped).doesNotContain("<interface");

    Path copy = tmp.resolve("server-no-interface.xml");
    Files.writeString(copy, stripped, StandardCharsets.UTF_8);

    Templates t = fixtures();
    t.setServerTmpl(copy.toString());
    return t;
  }

  private static Server server(String id, Templates templates) {
    Server s = new Server();
    s.setId(id);
    s.setCpu(2);
    s.setRam(1024);
    s.setDisk(10);
    s.setPool("default");
    s.setNetwork("host-bridge");
    s.setIp("192.168.17.40/24");
    s.setGateway("192.168.17.1");
    s.setVolLookup("debian-13-genericcloud.qcow2");
    s.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    s.setTemplates(templates);
    return s;
  }

  /** An empty host, with a pool that hands back a volume for every name asked of it. */
  private void host() throws LibvirtException {
    when(connect.listAllDomains(0)).thenReturn(new Domain[0]);
    lenient().when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    lenient().when(pool.storageVolLookupByName(anyString())).thenReturn(volume);
    lenient().when(volume.getPath()).thenReturn(IMAGES + "vol.qcow2");
    lenient().when(connect.domainDefineXML(anyString())).thenReturn(domain);
  }

  @Test
  void aTemplateThatCannotBeRenderedCostsItsOwnVmAndNothingElse() throws Exception {
    // The broken entry sits between two valid ones, because the two halves of the bug were on
    // opposite sides of it: the VMs created before it lost their report, and the ones after it
    // were never created at all.
    // Arrange
    host();
    Map<String, Server> servers = new LinkedHashMap<>();
    servers.put("qa-a", server("qa-a", fixtures()));
    servers.put("qa-b", server("qa-b", withoutInterface()));
    servers.put("qa-c", server("qa-c", fixtures()));

    Harmonia harmonia = new Harmonia("bm05", connect);
    // Act
    harmonia.plan(servers, false);
    harmonia.reconcile(1);
    // Assert
    verify(connect).domainDefineXML(contains("<name>qa-a</name>"));
    verify(connect).domainDefineXML(contains("<name>qa-c</name>"));
    verify(connect, never()).domainDefineXML(contains("<name>qa-b</name>"));

    assertThat(report())
        .contains("create: 2")
        .contains("skipped: 1")
        .contains("+ qa-a")
        .contains("+ qa-c")
        .contains("· qa-b")
        .contains("has no <interface> to fill in for server 'qa-b'");
  }

  @Test
  void theReportIsPrintedEvenWhenEveryVmFails() throws Exception {
    // Arrange
    host();
    Harmonia harmonia = new Harmonia("bm05", connect);
    // Act
    harmonia.plan(Map.of("qa-b", server("qa-b", withoutInterface())), false);
    harmonia.reconcile(1);
    // Assert
    assertThat(report()).contains("[ bm05 ]").contains("skipped: 1").contains("· qa-b");
  }
}
