package com.mnemosyne.app.libvirt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.Error;
import org.libvirt.LibvirtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GuestOps {

  private static final Logger log = LoggerFactory.getLogger(GuestOps.class);

  static final String NETPLAN_FILE = "/etc/netplan/50-cloud-init.yaml";

  static final String CLOUD_INIT_GUARD = "/etc/cloud/cloud.cfg.d/99-mnemosyne-network.cfg";

  static final String NETPLAN_SCRIPT =
      String.join(
          "\n",
          "set -eu",
          "command -v netplan >/dev/null 2>&1 || { echo 'netplan is not installed in the guest' >&2;"
              + " exit 3; }",
          "umask 077",
          "t=$(mktemp -d)",
          "trap 'rm -rf \"$t\"' EXIT",
          "mkdir -p \"$t/etc/netplan\"",
          "cat > \"$t/etc/netplan/50-cloud-init.yaml\"",
          "netplan generate --root-dir \"$t\"",
          "cp \"$t/etc/netplan/50-cloud-init.yaml\" \"" + NETPLAN_FILE + ".mnemosyne\"",
          "mv \"" + NETPLAN_FILE + ".mnemosyne\" \"" + NETPLAN_FILE + "\"",
          "if [ -d /etc/cloud/cloud.cfg.d ]; then printf 'network: {config: disabled}\\n' > \""
              + CLOUD_INIT_GUARD
              + "\"; fi",
          "sync",
          "netplan apply");

  private static final int AGENT_CALL_TIMEOUT_S = 10;

  private static final int MESSAGE_LIMIT = 300;

  record Timing(long agentWaitMs, long execWaitMs, long pollMs) {}

  static final Timing DEFAULT_TIMING = new Timing(180_000L, 120_000L, 2_000L);

  private final Connect connect;
  private final Timing timing;
  private final ObjectMapper json = new ObjectMapper();

  GuestOps(Connect connect) {
    this(connect, DEFAULT_TIMING);
  }

  GuestOps(Connect connect, Timing timing) {
    this.connect = connect;
    this.timing = timing;
  }

  void applyNetplan(String name, String netplanYaml) throws LibvirtException {
    Domain d = connect.domainLookupByName(name);
    try {
      awaitAgent(d, name);
      log.debug("Domain '{}': writing {} and running netplan apply", name, NETPLAN_FILE);
      log.trace("Domain '{}': netplan YAML: {}", name, netplanYaml);
      long pid = exec(d, List.of("/bin/sh", "-c", NETPLAN_SCRIPT), netplanYaml);
      JsonNode status = awaitExit(d, name, pid);
      int code = status.path("exitcode").asInt(-1);
      if (code != 0)
        throw new IllegalStateException(
            "netplan in the guest failed (exit " + code + "): " + lastLine(status, "err-data"));
      log.debug("Domain '{}': guest network applied", name);
    } finally {
      try {
        d.free();
      } catch (LibvirtException e) {
        log.debug("Failed to free domain handle; ignoring", e);
      }
    }
  }

  private void awaitAgent(Domain d, String name) throws LibvirtException {
    long deadline = System.nanoTime() + timing.agentWaitMs() * 1_000_000L;
    while (true) {
      try {
        d.qemuAgentCommand("{\"execute\":\"guest-ping\"}", AGENT_CALL_TIMEOUT_S, 0);
        return;
      } catch (LibvirtException e) {
        if (!unresponsive(e)) throw e;
        if (System.nanoTime() >= deadline)
          throw new IllegalStateException(
              "qemu-guest-agent did not answer within "
                  + timing.agentWaitMs() / 1000
                  + "s: "
                  + e.getMessage());
        log.debug("Domain '{}': waiting for qemu-guest-agent ({})", name, e.getMessage());
        sleep();
      }
    }
  }

  private static boolean unresponsive(LibvirtException e) {
    Error error = e.getError();
    if (error == null) return false;
    Error.ErrorNumber code = error.getCode();
    return code == Error.ErrorNumber.VIR_ERR_AGENT_UNRESPONSIVE
        || code == Error.ErrorNumber.VIR_ERR_AGENT_UNSYNCED;
  }

  private long exec(Domain d, List<String> command, String input) throws LibvirtException {
    ObjectNode request = json.createObjectNode().put("execute", "guest-exec");
    ObjectNode args = request.putObject("arguments");
    args.put("path", command.get(0));
    command.subList(1, command.size()).forEach(args.putArray("arg")::add);
    args.put(
        "input-data", Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8)));
    args.put("capture-output", true);
    JsonNode reply = call(d, request.toString());
    JsonNode pid = reply.path("return").path("pid");
    if (!pid.canConvertToLong())
      throw new IllegalStateException("qemu-guest-agent returned no pid for guest-exec: " + reply);
    return pid.asLong();
  }

  private JsonNode awaitExit(Domain d, String name, long pid) throws LibvirtException {
    long deadline = System.nanoTime() + timing.execWaitMs() * 1_000_000L;
    String request = "{\"execute\":\"guest-exec-status\",\"arguments\":{\"pid\":" + pid + "}}";
    while (true) {
      JsonNode status = call(d, request).path("return");
      if (status.path("exited").asBoolean(false)) return status;
      if (System.nanoTime() >= deadline)
        throw new IllegalStateException(
            "netplan in the guest did not finish within " + timing.execWaitMs() / 1000 + "s");
      log.debug("Domain '{}': guest command {} still running", name, pid);
      sleep();
    }
  }

  private JsonNode call(Domain d, String request) throws LibvirtException {
    String reply = d.qemuAgentCommand(request, AGENT_CALL_TIMEOUT_S, 0);
    try {
      return json.readTree(reply == null ? "{}" : reply);
    } catch (IOException e) {
      throw new IllegalStateException("qemu-guest-agent sent an unreadable reply: " + reply, e);
    }
  }

  private static String lastLine(JsonNode status, String field) {
    String data = status.path(field).asText("");
    if (data.isEmpty()) return "no output";
    String text = new String(Base64.getMimeDecoder().decode(data), StandardCharsets.UTF_8);
    String line =
        Arrays.stream(text.split("\n"))
            .map(String::trim)
            .filter(l -> !l.isEmpty())
            .reduce((a, b) -> b)
            .orElse("no output");
    return line.length() > MESSAGE_LIMIT ? line.substring(0, MESSAGE_LIMIT) + "..." : line;
  }

  private void sleep() {
    try {
      Thread.sleep(timing.pollMs());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the guest", e);
    }
  }
}
