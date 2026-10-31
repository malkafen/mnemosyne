package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mnemosyne.app.http.CloudInitServer;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.ExtraDisk;
import com.mnemosyne.app.model.InitMarker;
import com.mnemosyne.app.model.Plan;
import com.mnemosyne.app.model.Preflight;
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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.libvirt.StorageVol;
import org.mockito.ArgumentCaptor;

@DisplayName("Harmonia against libvirt's test driver")
public class HarmoniaLibvirtIT {

  private static final String IMAGES = "/var/lib/libvirt/images/";
  private static final String BASE_IMAGE = "debian-13-genericcloud-amd64.qcow2";

  private Connect connect;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private PrintStream stdout;

  @BeforeAll
  static void libvirtPresent() {
    try {
      new Connect("test:///default").close();
    } catch (UnsatisfiedLinkError | NoClassDefFoundError | LibvirtException e) {
      if ("true".equals(System.getenv("CI"))) throw new IllegalStateException(e);
      Assumptions.abort(
          "libvirt client library not found - install libvirt0 (apt) or libvirt (brew),"
              + " and on macOS run with -Djna.library.path=/opt/homebrew/lib");
    }
  }

  @BeforeEach
  void openHost() throws Exception {
    Path node = Path.of(HarmoniaLibvirtIT.class.getResource("/libvirt/test-node.xml").toURI());
    connect = new Connect("test://" + node.toAbsolutePath());
    stdout = System.out;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void closeHost() throws LibvirtException {
    System.setOut(stdout);
    for (String name : List.of("web-01", "web-02", "db-01", "legacy"))
      CloudInitServer.unregister(name);
    if (connect != null) connect.close();
  }

  private static Templates templates() {
    Templates t = new Templates();
    t.setServerTmpl("templates/server.xml");
    t.setVolTmpl("templates/volume.xml");
    t.setMetaDataTmpl(HarmoniaLibvirtIT.class.getResource("/meta-data.yml").getPath());
    t.setUserDataTmpl(HarmoniaLibvirtIT.class.getResource("/user-data.yml").getPath());
    t.setNetworkConfigTmpl(HarmoniaLibvirtIT.class.getResource("/network-config.yml").getPath());
    return t;
  }

  private static Server server(String id) {
    Server s = new Server();
    s.setId(id);
    s.setCpu(2);
    s.setRam(1024);
    s.setDisk(20);
    s.setPool("default");
    s.setNetwork("default");
    s.setIp("192.168.122.40/24");
    s.setGateway("192.168.122.1");
    s.setVolLookup(BASE_IMAGE);
    s.setMetaUrl("http://192.168.122.1:8080/cloud-init/");
    s.setTemplates(templates());
    return s;
  }

  private static ExtraDisk extraDisk(String name, int sizeGiB) {
    ExtraDisk d = new ExtraDisk();
    d.setName(name);
    d.setSize(sizeGiB);
    d.setPool("default");
    return d;
  }

  private static Map<String, Server> inventory(Server... servers) {
    Map<String, Server> m = new LinkedHashMap<>();
    for (Server s : servers) m.put(s.getId(), s);
    return m;
  }

  private Harmonia apply(Map<String, Server> servers, int parallel) throws LibvirtException {
    Harmonia h = new Harmonia("it", connect);
    h.plan(servers, false);
    assertThat(h.preflight().getProblems()).isEmpty();
    h.reconcile(parallel);
    h.settle(parallel);
    return h;
  }

  private void createAndInitialize(Server... servers) throws Exception {
    Harmonia h = apply(inventory(servers), 1);
    assertThat(h.failures()).isZero();
    for (Server s : servers) assertThat(phoneHome(s.getName(), state(s.getName()))).isEqualTo(200);
  }

  private static int phoneHome(String name, DomainState state) throws Exception {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getRequestURI())
        .thenReturn(URI.create("/cloud-init/" + name + "/" + state.init().token() + "/phone-home"));
    when(exchange.getRequestMethod()).thenReturn("POST");
    when(exchange.getRequestBody())
        .thenReturn(new ByteArrayInputStream("instance_id=x".getBytes(StandardCharsets.UTF_8)));
    when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
    when(exchange.getResponseHeaders()).thenReturn(new Headers());
    new CloudInitServer.CloudInitHandler().handle(exchange);
    ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
    verify(exchange).sendResponseHeaders(status.capture(), anyLong());
    return status.getValue();
  }

  private DomainState state(String name) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    try {
      return XmlUtil.getShortState(d.getXMLDesc(Domain.XMLFlags.INACTIVE))
          .withRuntime(d.isActive() == 1, d.getAutostart());
    } finally {
      d.free();
    }
  }

  private boolean volumeExists(String path) {
    try {
      StorageVol v = connect.storageVolLookupByPath(path);
      v.free();
      return true;
    } catch (LibvirtException e) {
      return false;
    }
  }

  @Test
  void aNewVm_isDefinedFromTheShippedTemplate_runningPendingAndWithItsDisksRecorded()
      throws Exception {
    Server web = server("web-01");
    web.setAutostart(true);
    web.setExtraDisks(List.of(extraDisk("data", 5)));
    Harmonia h = apply(inventory(web), 1);
    assertThat(h.failures()).isZero();
    DomainState s = state("web-01");
    assertThat(s.managed()).isTrue();
    assertThat(s.serverId()).isEqualTo("web-01");
    assertThat(s.cpu()).isEqualTo(2);
    assertThat(s.ram()).isEqualTo(1024);
    assertThat(s.active()).isTrue();
    assertThat(s.autostart()).isTrue();
    assertThat(s.init().isPending()).isTrue();
    assertThat(s.diskPaths())
        .containsExactly(IMAGES + "web-01.qcow2", IMAGES + "web-01-data.qcow2");
    assertThat(s.reusedPaths()).containsExactly(IMAGES + "web-01.qcow2");
    assertThat(s.ownedPaths()).containsExactly(IMAGES + "web-01-data.qcow2");
    assertThat(volumeExists(IMAGES + "web-01-data.qcow2")).isTrue();
  }

  @Test
  void severalVmsAppliedInParallel_allEndUpOnTheHost() throws Exception {
    Harmonia h = apply(inventory(server("web-01"), server("web-02")), 2);
    assertThat(h.failures()).isZero();
    assertThat(state("web-01").active()).isTrue();
    assertThat(state("web-02").active()).isTrue();
  }

  @Test
  void aPhoneHome_isWrittenToLibvirt_andTheNextRunHasNothingToDo() throws Exception {
    Server web = server("web-01");
    web.setExtraDisks(List.of(extraDisk("data", 5)));
    createAndInitialize(web);
    InitMarker init = state("web-01").init();
    assertThat(init.isFinished()).isTrue();
    assertThat(init.finished()).isNotNull();
    assertThat(state("web-01").ownedPaths()).containsExactly(IMAGES + "web-01-data.qcow2");

    Harmonia next = new Harmonia("it", connect);
    Plan plan = next.plan(inventory(web), false);
    assertThat(plan.getToCreate()).isEmpty();
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getToDelete()).isEmpty();
    assertThat(plan.getPendingInit()).isEmpty();
    assertThat(next.preflight().ok()).isTrue();
  }

  @Test
  void aVmThatNeverPhonedHome_isPendingOnTheNextRun() throws Exception {
    Server web = server("web-01");
    apply(inventory(web), 1);
    Harmonia next = new Harmonia("it", connect);
    next.plan(inventory(web), false);
    assertThat(next.pendingInit()).containsExactly("web-01");
  }

  @Test
  void launchFalse_bootsTheVmForCloudInit_andSettleShutsItDown() throws Exception {
    Server web = server("web-01");
    web.setLaunch(false);
    Harmonia h = apply(inventory(web), 1);
    assertThat(h.failures()).isZero();
    assertThat(state("web-01").active()).isFalse();
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("cloud-init did not finish");
  }

  @Test
  void anUpdate_changesCpuRamAutostartAndPower_inThePersistentConfig() throws Exception {
    createAndInitialize(server("web-01"));
    Server wanted = server("web-01");
    wanted.setCpu(4);
    wanted.setRam(2048);
    wanted.setAutostart(true);
    wanted.setLaunch(false);
    Harmonia h = apply(inventory(wanted), 1);
    assertThat(h.failures()).isZero();
    DomainState s = state("web-01");
    assertThat(s.cpu()).isEqualTo(4);
    assertThat(s.ram()).isEqualTo(2048);
    assertThat(s.autostart()).isTrue();
    assertThat(s.active()).isFalse();
    assertThat(s.init().isFinished()).isTrue();
    assertThat(s.reusedPaths()).containsExactly(IMAGES + "web-01.qcow2");
  }

  @Test
  void aVmDroppedFromTheInventory_isDeleted_withTheVolumesItCreatedButNotTheOnesItFound()
      throws Exception {
    Server web = server("web-01");
    web.setExtraDisks(List.of(extraDisk("data", 5)));
    createAndInitialize(web, server("web-02"));
    Harmonia h = apply(inventory(server("web-02")), 1);
    assertThat(h.failures()).isZero();
    assertThatThrownBy(() -> connect.domainLookupByName("web-01"))
        .isInstanceOf(LibvirtException.class);
    assertThat(volumeExists(IMAGES + "web-01-data.qcow2")).isFalse();
    assertThat(volumeExists(IMAGES + "web-01.qcow2")).isTrue();
    assertThat(state("web-02").active()).isTrue();
  }

  @Test
  void deleteDisable_leavesAVmDroppedFromTheInventoryAlone() throws Exception {
    createAndInitialize(server("web-01"));
    Harmonia h = new Harmonia("it", connect);
    Plan plan = h.plan(Map.of(), true);
    h.reconcile(1);
    assertThat(plan.getToDelete()).isEmpty();
    assertThat(state("web-01").active()).isTrue();
  }

  @Test
  void anUnmanagedDomain_isNeverTouched_andJoinAdoptsIt() throws Exception {
    connect
        .domainDefineXML(
            "<domain type='test'><name>legacy</name><memory unit='MiB'>1024</memory>"
                + "<vcpu>2</vcpu><os><type arch='x86_64'>hvm</type></os></domain>")
        .free();
    Harmonia untouched = apply(Map.of(), 1);
    assertThat(untouched.failures()).isZero();
    assertThat(state("legacy").managed()).isFalse();
    Harmonia h = new Harmonia("it", connect);
    Plan plan = h.plan(inventory(server("legacy")), false);
    h.join(1);
    assertThat(plan.getToCreate()).isEmpty();
    assertThat(plan.getToAdopt()).containsOnlyKeys("legacy");
    DomainState s = state("legacy");
    assertThat(s.managed()).isTrue();
    assertThat(s.serverId()).isEqualTo("legacy");
    assertThat(s.init().state()).isEqualTo(InitMarker.ADOPTED);
    assertThat(s.ownedPaths()).isEmpty();
  }

  @Test
  void preflight_findsWhatIsMissingOnTheHost_andNothingIsApplied() throws Exception {
    Server noPool = server("web-01");
    noPool.setPool("fast-ssd");
    Server noImage = server("web-02");
    noImage.setVolLookup("rocky-9.qcow2");
    Server noNetwork = server("db-01");
    noNetwork.setNetwork("host-bridge");
    Harmonia h = new Harmonia("it", connect);
    h.plan(inventory(noPool, noImage, noNetwork), false);
    Preflight preflight = h.preflight();
    assertThat(preflight.getProblems().keySet())
        .extracting(Preflight.Problem::resource)
        .contains("pool 'fast-ssd'", "image 'rocky-9.qcow2'", "network 'host-bridge'");
    assertThat(preflight.blockers("web-01")).isNotEmpty();
    assertThat(preflight.blockers("web-02")).isNotEmpty();
    assertThat(preflight.blockers("db-01")).isNotEmpty();
    assertThat(connect.listAllDomains(0)).isEmpty();
  }

  @Test
  void aCloneThatCannotBeBroughtToSize_isTakenBack_andTheVmIsSkipped() throws Exception {
    Server db = server("db-01");
    Harmonia h = apply(inventory(db), 1);
    assertThat(h.failures()).isEqualTo(1);
    assertThat(volumeExists(IMAGES + "db-01.qcow2")).isFalse();
    assertThat(volumeExists(IMAGES + BASE_IMAGE)).isTrue();
    assertThat(connect.listAllDomains(0)).isEmpty();
    assertThat(CloudInitServer.unfinished()).doesNotContain("db-01");
  }
}
