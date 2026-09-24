package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mnemosyne.app.model.Server;
import com.mnemosyne.app.model.Templates;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What applying several VMs at once is allowed to change, and what it is not.
 *
 * <p>Two things are worth a test here; the rest is the reconciler's own business, already covered
 * where it belongs. The first is that the VMs really do overlap — a phase that quietly went back to
 * one VM at a time would satisfy every assertion about the result — so the host is made to hold
 * each domain until all of them have arrived, and the run only finishes if the workers meet there.
 * The second is that nothing the operator reads depends on which VM got there first: the same
 * inventory prints the same report whatever {@code --parallel} was set to.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Harmonia, several VMs at once")
public class HarmoniaParallelTest {

  private static final String IMAGES = "/var/lib/libvirt/images/";

  /** Long enough that a busy CI box is not mistaken for a run that lost its parallelism. */
  private static final int RENDEZVOUS_S = 10;

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
  }

  private String report() {
    return out.toString(StandardCharsets.UTF_8);
  }

  private static Templates fixtures() {
    Templates t = new Templates();
    t.setServerTmpl(HarmoniaParallelTest.class.getResource("/server-template.xml").getPath());
    t.setVolTmpl(HarmoniaParallelTest.class.getResource("/volume.xml").getPath());
    t.setMetaDataTmpl(HarmoniaParallelTest.class.getResource("/meta-data.yml").getPath());
    t.setUserDataTmpl(HarmoniaParallelTest.class.getResource("/user-data.yml").getPath());
    t.setNetworkConfigTmpl(HarmoniaParallelTest.class.getResource("/network-config.yml").getPath());
    return t;
  }

  private static Server server(String id) {
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
    s.setTemplates(fixtures());
    return s;
  }

  /** {@code qa-01}..{@code qa-0n}, each a VM the host has never heard of. */
  private static Map<String, Server> inventory(int count) {
    Map<String, Server> servers = new LinkedHashMap<>();
    for (int i = 1; i <= count; i++) {
      String id = String.format("qa-%02d", i);
      servers.put(id, server(id));
    }
    return servers;
  }

  /** An empty host, with a pool that hands back a volume for every name asked of it. */
  private void host() throws LibvirtException {
    when(connect.listAllDomains(0)).thenReturn(new Domain[0]);
    lenient().when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    lenient().when(pool.storageVolLookupByName(anyString())).thenReturn(volume);
    lenient().when(volume.getPath()).thenReturn(IMAGES + "vol.qcow2");
  }

  /**
   * A host that defines no domain until {@code parties} of them have been asked for at once. A
   * worker that waits alone gives up after {@code timeoutS} and fails its own VM, which is how a
   * phase that stopped overlapping shows up as skipped entries rather than as a hung build.
   */
  private void hostThatWaitsForEveryone(int parties, int timeoutS) throws LibvirtException {
    CyclicBarrier rendezvous = new CyclicBarrier(parties);
    when(connect.domainDefineXML(anyString()))
        .thenAnswer(
            invocation -> {
              try {
                rendezvous.await(timeoutS, TimeUnit.SECONDS);
              } catch (Exception e) {
                throw new IllegalStateException("the VMs never met at the hypervisor", e);
              }
              return domain;
            });
  }

  @Test
  void theVmsOfAPhaseReachTheHypervisorTogether() throws Exception {
    // Arrange
    host();
    hostThatWaitsForEveryone(4, RENDEZVOUS_S);

    Harmonia harmonia = new Harmonia("bm05", connect);
    // Act
    harmonia.plan(inventory(4), false);
    harmonia.reconcile(4);
    // Assert
    assertThat(report()).contains("create: 4").doesNotContain("skipped");
  }

  @Test
  void oneVmAtATimeCannotReachIt() throws Exception {
    // The other half of the test above: it only means something if a sequential run fails it.
    // Arrange
    host();
    hostThatWaitsForEveryone(2, 1);

    Harmonia harmonia = new Harmonia("bm05", connect);
    // Act
    harmonia.plan(inventory(2), false);
    harmonia.reconcile(1);
    // Assert
    assertThat(report()).contains("skipped: 2").contains("the VMs never met at the hypervisor");
  }

  @Test
  void theReportReadsTheSameHoweverManyVmsRanAtOnce() throws Exception {
    // Arrange
    host();
    when(connect.domainDefineXML(anyString())).thenReturn(domain);

    // Act
    Harmonia oneByOne = new Harmonia("bm05", connect);
    oneByOne.plan(inventory(6), false);
    oneByOne.reconcile(1);
    String sequential = report();

    out.reset();
    Harmonia allAtOnce = new Harmonia("bm05", connect);
    allAtOnce.plan(inventory(6), false);
    allAtOnce.reconcile(6);
    String parallel = report();
    // Assert
    assertThat(parallel).isEqualTo(sequential);
    assertThat(parallel).contains("create: 6").contains("+ qa-01").contains("+ qa-06");
  }

  @Test
  void aVmThatFailsAmongOthersStillCostsOnlyItself() throws Exception {
    // Arrange
    host();
    when(connect.domainDefineXML(contains("<name>qa-01</name>"))).thenReturn(domain);
    when(connect.domainDefineXML(contains("<name>qa-03</name>"))).thenReturn(domain);
    when(connect.domainDefineXML(contains("<name>qa-02</name>")))
        .thenThrow(new IllegalStateException("hugetlbfs is not mounted"));

    Harmonia harmonia = new Harmonia("bm05", connect);
    // Act
    harmonia.plan(inventory(3), false);
    harmonia.reconcile(3);
    // Assert
    verify(connect).domainDefineXML(contains("<name>qa-01</name>"));
    verify(connect).domainDefineXML(contains("<name>qa-03</name>"));
    assertThat(report())
        .contains("create: 2")
        .contains("skipped: 1")
        .contains("· qa-02")
        .contains("hugetlbfs is not mounted");
  }
}
