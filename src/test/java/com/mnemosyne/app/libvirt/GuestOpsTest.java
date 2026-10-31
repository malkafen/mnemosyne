package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.Error;
import org.libvirt.LibvirtException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GuestOps.applyNetplan()")
public class GuestOpsTest {

  private static final String PING = "{\"execute\":\"guest-ping\"}";
  private static final String YAML = "network:\n  version: 2\n";
  private static final GuestOps.Timing FAST = new GuestOps.Timing(50, 50, 1);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Mock Connect connect;
  @Mock Domain domain;

  @BeforeEach
  void lookup() throws LibvirtException {
    when(connect.domainLookupByName("web-01")).thenReturn(domain);
  }

  private static LibvirtException agentError(Error.ErrorNumber code) {
    Error error = mock(Error.class);
    when(error.getCode()).thenReturn(code);
    LibvirtException e = mock(LibvirtException.class);
    when(e.getError()).thenReturn(error);
    when(e.getMessage()).thenReturn("Guest agent is not responding");
    return e;
  }

  private static String b64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private void agentAnswers() throws LibvirtException {
    when(domain.qemuAgentCommand(eq(PING), anyInt(), anyInt())).thenReturn("{\"return\":{}}");
  }

  private void execStarts() throws LibvirtException {
    when(domain.qemuAgentCommand(
            argThat(c -> c != null && c.contains("\"guest-exec\"")), anyInt(), anyInt()))
        .thenReturn("{\"return\":{\"pid\":42}}");
  }

  private void execEnds(String... statuses) throws LibvirtException {
    String first = statuses[0];
    String[] rest = Arrays.copyOfRange(statuses, 1, statuses.length);
    when(domain.qemuAgentCommand(
            argThat(c -> c != null && c.contains("guest-exec-status") && c.contains("42")),
            anyInt(),
            anyInt()))
        .thenReturn(first, rest);
  }

  @Test
  void theConfigIsPipedIntoTheScript_andASuccessfulRunReturns() throws Exception {
    agentAnswers();
    execStarts();
    execEnds("{\"return\":{\"exited\":true,\"exitcode\":0}}");

    new GuestOps(connect, FAST).applyNetplan("web-01", YAML);

    ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
    verify(domain, atLeastOnce()).qemuAgentCommand(cmd.capture(), anyInt(), anyInt());
    JsonNode exec =
        JSON.readTree(
            cmd.getAllValues().stream()
                .filter(c -> c.contains("\"guest-exec\""))
                .findFirst()
                .get());
    JsonNode args = exec.path("arguments");
    assertThat(args.path("path").asText()).isEqualTo("/bin/sh");
    assertThat(args.path("arg").get(0).asText()).isEqualTo("-c");
    assertThat(args.path("arg").get(1).asText()).isEqualTo(GuestOps.NETPLAN_SCRIPT);
    assertThat(args.path("input-data").asText()).isEqualTo(b64(YAML));
    assertThat(args.path("capture-output").asBoolean()).isTrue();
    verify(domain).free();
  }

  @Test
  void theScriptValidatesBeforeItReplacesTheFile_andAppliesLast() {
    String script = GuestOps.NETPLAN_SCRIPT;
    int generate = script.indexOf("netplan generate --root-dir");
    int replace = script.indexOf("mv ");
    int apply = script.indexOf("netplan apply");
    assertThat(script).startsWith("set -eu").contains("umask 077");
    assertThat(generate).isPositive().isLessThan(replace);
    assertThat(replace).isLessThan(apply);
    assertThat(script).contains(GuestOps.NETPLAN_FILE);
    assertThat(script.indexOf("sync")).isGreaterThan(replace).isLessThan(apply);
    assertThat(script).contains("network: {config: disabled}").contains(GuestOps.CLOUD_INIT_GUARD);
  }

  @Test
  void aCommandStillRunningIsPolledUntilItExits() throws Exception {
    agentAnswers();
    execStarts();
    execEnds(
        "{\"return\":{\"exited\":false}}",
        "{\"return\":{\"exited\":false}}",
        "{\"return\":{\"exited\":true,\"exitcode\":0}}");

    new GuestOps(connect, FAST).applyNetplan("web-01", YAML);

    verify(domain, times(3))
        .qemuAgentCommand(
            argThat(c -> c != null && c.contains("guest-exec-status")), anyInt(), anyInt());
  }

  @Test
  void aFailedScriptIsReportedWithTheLastLineOfItsErrors() throws Exception {
    agentAnswers();
    execStarts();
    execEnds(
        "{\"return\":{\"exited\":true,\"exitcode\":1,\"err-data\":\""
            + b64("WARNING: gateway4 deprecated\n\nError in network definition: bad\n\n")
            + "\"}}");

    assertThatThrownBy(() -> new GuestOps(connect, FAST).applyNetplan("web-01", YAML))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("netplan in the guest failed (exit 1): Error in network definition: bad");
    verify(domain).free();
  }

  @Test
  void aFailureWithoutOutputSaysSo() throws Exception {
    agentAnswers();
    execStarts();
    execEnds("{\"return\":{\"exited\":true,\"exitcode\":3}}");

    assertThatThrownBy(() -> new GuestOps(connect, FAST).applyNetplan("web-01", YAML))
        .hasMessage("netplan in the guest failed (exit 3): no output");
  }

  @Test
  void aVeryLongErrorIsCut() throws Exception {
    agentAnswers();
    execStarts();
    execEnds(
        "{\"return\":{\"exited\":true,\"exitcode\":1,\"err-data\":\""
            + b64("x".repeat(1000))
            + "\"}}");

    assertThatThrownBy(() -> new GuestOps(connect, FAST).applyNetplan("web-01", YAML))
        .message()
        .endsWith("...")
        .hasSizeLessThan(400);
  }

  @Test
  void aCommandThatNeverExitsTimesOut() throws Exception {
    agentAnswers();
    execStarts();
    when(domain.qemuAgentCommand(
            argThat(c -> c != null && c.contains("guest-exec-status")), anyInt(), anyInt()))
        .thenReturn("{\"return\":{\"exited\":false}}");

    assertThatThrownBy(() -> new GuestOps(connect, FAST).applyNetplan("web-01", YAML))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not finish");
  }

  @Test
  void anAgentThatIsNotUpYetIsWaitedFor() throws Exception {
    LibvirtException notYet = agentError(Error.ErrorNumber.VIR_ERR_AGENT_UNRESPONSIVE);
    when(domain.qemuAgentCommand(eq(PING), anyInt(), anyInt()))
        .thenThrow(notYet)
        .thenThrow(notYet)
        .thenReturn("{\"return\":{}}");
    execStarts();
    execEnds("{\"return\":{\"exited\":true,\"exitcode\":0}}");

    new GuestOps(connect, new GuestOps.Timing(10_000, 50, 1)).applyNetplan("web-01", YAML);

    verify(domain, times(3)).qemuAgentCommand(eq(PING), anyInt(), anyInt());
  }

  @Test
  void anAgentThatNeverAnswersTimesOut_andNothingIsRun() throws Exception {
    LibvirtException notYet = agentError(Error.ErrorNumber.VIR_ERR_AGENT_UNRESPONSIVE);
    when(domain.qemuAgentCommand(eq(PING), anyInt(), anyInt())).thenThrow(notYet);

    assertThatThrownBy(() -> new GuestOps(connect, FAST).applyNetplan("web-01", YAML))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("qemu-guest-agent did not answer");
    verify(domain, never())
        .qemuAgentCommand(argThat(c -> c != null && c.contains("guest-exec")), anyInt(), anyInt());
    verify(domain).free();
  }

  @Test
  void aDomainWithoutAnAgentChannelFailsAtOnce() throws Exception {
    LibvirtException notConfigured = agentError(Error.ErrorNumber.VIR_ERR_ARGUMENT_UNSUPPORTED);
    when(domain.qemuAgentCommand(eq(PING), anyInt(), anyInt())).thenThrow(notConfigured);

    assertThatThrownBy(
            () ->
                new GuestOps(connect, new GuestOps.Timing(60_000, 50, 1))
                    .applyNetplan("web-01", YAML))
        .isSameAs(notConfigured);
    verify(domain, times(1)).qemuAgentCommand(anyString(), anyInt(), anyInt());
  }

  @Test
  void anExecReplyWithoutPidIsAnError() throws Exception {
    agentAnswers();
    when(domain.qemuAgentCommand(
            argThat(c -> c != null && c.contains("\"guest-exec\"")), anyInt(), anyInt()))
        .thenReturn("{\"error\":{\"class\":\"GenericError\",\"desc\":\"disabled\"}}");

    assertThatThrownBy(() -> new GuestOps(connect, FAST).applyNetplan("web-01", YAML))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no pid");
  }

  @Test
  void anUnreadableReplyIsAnError() throws Exception {
    agentAnswers();
    when(domain.qemuAgentCommand(
            argThat(c -> c != null && c.contains("\"guest-exec\"")), anyInt(), anyInt()))
        .thenReturn("not json");

    assertThatThrownBy(() -> new GuestOps(connect, FAST).applyNetplan("web-01", YAML))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unreadable reply");
  }
}
