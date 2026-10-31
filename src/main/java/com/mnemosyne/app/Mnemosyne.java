package com.mnemosyne.app;

import com.mnemosyne.app.config.Config;
import com.mnemosyne.app.http.CloudInitServer;
import com.mnemosyne.app.libvirt.Harmonia;
import com.mnemosyne.app.model.*;
import com.mnemosyne.app.output.Report;
import jakarta.validation.*;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParameterException;

class Mnemosyne {

  private record Iris(Mnemon mnemon, Harmonia harmonia) {}

  private static final Logger log = LoggerFactory.getLogger(Mnemosyne.class);
  private static final long CONFIRM_DELAY_MS = 10000L;

  private static final int EXIT_OK = 0;

  private static final int EXIT_INCOMPLETE = 1;

  private Validator validator;
  private List<Mnemon> mnemones;
  private List<Iris> irides = new ArrayList<>();

  public Mnemosyne() {}

  public static void main(String[] args) {
    System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

    Config config = new Config();
    Config.EnvDefaults env = new Config.EnvDefaults(System::getenv);
    CommandLine cmd = new CommandLine(config).setDefaultValueProvider(env);

    try {
      cmd.parseArgs(args);
    } catch (ParameterException e) {
      String variable = env.source(e.getArgSpec());
      cmd.getErr().println(e.getMessage() + (variable == null ? "" : " (from " + variable + ")"));
      e.getCommandLine().usage(cmd.getErr());
      System.exit(cmd.getCommandSpec().exitCodeOnInvalidInput());
      return;
    }

    if (cmd.isUsageHelpRequested() || cmd.isVersionHelpRequested()) {
      if (cmd.isUsageHelpRequested()) cmd.usage(cmd.getOut());
      else cmd.printVersionHelp(cmd.getOut());
      return;
    }

    if (config.getParallel() < 1) {
      invalid(cmd, env, "--parallel", "must be at least 1");
      return;
    }

    if (config.getHttpPort() < 1 || config.getHttpPort() > 65535) {
      invalid(cmd, env, "--http-port", "must be between 1 and 65535");
      return;
    }

    if (config.isVerbose()) {
      enableDebugLogging();
      logSettings(cmd, env);
    }

    try {
      int exitCode = new Mnemosyne().run(config);
      if (exitCode != EXIT_OK) System.exit(exitCode);
    } catch (Exception e) {
      Throwable root = getRootCause(e);
      if (root == e) log.error("Fatal: {}", e.getMessage());
      else log.error("Fatal: {} (cause: {})", e.getMessage(), root.getMessage());
      log.debug("Fatal error details", e);
      System.exit(1);
    }
  }

  public int run(Config config) throws Exception {
    initValidator();
    mnemones = loadAndValidate(config);
    try {
      for (Mnemon m : mnemones) {
        Harmonia h =
            new Harmonia(
                m.getGroup(),
                m.getUser(),
                m.getKey(),
                m.getHost(),
                m.getPort(),
                config.isSkipHostKeyCheck());
        irides.add(new Iris(m, h));
      }

      Report.heading("Plan");
      Map<String, Preflight> blocked = new LinkedHashMap<>();
      for (Iris i : irides) {
        Plan plan =
            i.harmonia.plan(
                i.mnemon().getServers(), config.isDeleteDisable(), config.isPurgeDisks());
        Preflight preflight = config.isJoin() ? new Preflight() : i.harmonia.preflight();
        plan.print(i.mnemon.getGroup(), config.isJoin(), preflight);
        if (!preflight.ok()) blocked.put(i.mnemon.getGroup(), preflight);
      }
      if (!blocked.isEmpty()) stop(blocked.size());

      if (config.isPlanOnly()) {
        List<String> initPending = pendingInit();
        List<String> unjoined = unjoined(config.isJoin());
        if (initPending.isEmpty() && unjoined.isEmpty()) return EXIT_OK;
        if (!initPending.isEmpty())
          System.out.printf(
              "%d server(s) never finished initialization: %s.%n",
              initPending.size(), String.join(", ", initPending));
        if (!unjoined.isEmpty())
          System.out.printf(
              "%d server(s) have their name taken by an unmanaged domain: %s.%n",
              unjoined.size(), String.join(", ", unjoined));
        return EXIT_INCOMPLETE;
      }
      CloudInitServer.start(config.getHttpPort());
      confirmWindow();

      Report.heading("Applied");
      for (Iris i : irides) {
        if (config.isJoin()) i.harmonia.join(config.getParallel());
        else i.harmonia().reconcile(config.getParallel());
      }
      log.info("All {} mnemones provisioned. Waiting cloud-init is done...", mnemones.size());
      boolean cloudInitOk = CloudInitServer.waitForCloudInit().get();
      if (!cloudInitOk) {
        log.error("cloud-init did not finish on all servers — see warnings above");
      }

      if (irides.stream().anyMatch(i -> i.harmonia().hasPendingStop())) {
        Report.heading("Settled");
        for (Iris i : irides) i.harmonia().settle(config.getParallel());
      }
      return outcome(cloudInitOk, unjoined(config.isJoin()));
    } finally {
      shutdown();
    }
  }

  private int outcome(boolean cloudInitOk, List<String> unjoined) {
    int skipped = irides.stream().mapToInt(i -> i.harmonia().failures()).sum();
    List<String> pending = CloudInitServer.unfinished();
    List<String> initPending = pendingInit();
    if (skipped == 0
        && cloudInitOk
        && pending.isEmpty()
        && initPending.isEmpty()
        && unjoined.isEmpty()) return EXIT_OK;

    StringJoiner why = new StringJoiner(", ");
    if (skipped > 0) why.add(skipped + " entr" + (skipped == 1 ? "y" : "ies") + " skipped");
    if (!pending.isEmpty())
      why.add(
          String.format(
              "cloud-init did not finish on %d server(s): %s",
              pending.size(), String.join(", ", pending)));
    else if (!cloudInitOk) why.add("the wait for cloud-init did not complete");
    if (!initPending.isEmpty())
      why.add(
          String.format(
              "%d existing server(s) never finished initialization: %s",
              initPending.size(), String.join(", ", initPending)));
    if (!unjoined.isEmpty())
      why.add(
          String.format(
              "%d server(s) have their name taken by an unmanaged domain: %s",
              unjoined.size(), String.join(", ", unjoined)));

    System.out.printf("%nRun finished incomplete: %s.%n", why);
    return EXIT_INCOMPLETE;
  }

  private List<String> pendingInit() {
    return irides.stream().flatMap(i -> i.harmonia().pendingInit().stream()).sorted().toList();
  }

  private List<String> unjoined(boolean isJoin) {
    if (isJoin) return List.of();
    return irides.stream().flatMap(i -> i.harmonia().unjoined().stream()).sorted().toList();
  }

  private void stop(int blockedGroups) {
    System.out.printf(
        "Nothing was applied: %d of %d group(s) cannot be applied as planned.%n",
        blockedGroups, irides.size());
    throw new IllegalStateException("preflight failed");
  }

  private static void invalid(CommandLine cmd, Config.EnvDefaults env, String option, String rule) {
    String variable = env.source(cmd.getCommandSpec().findOption(option));
    cmd.getErr()
        .println(option + " " + rule + (variable == null ? "" : " (from " + variable + ")"));
    System.exit(cmd.getCommandSpec().exitCodeOnInvalidInput());
  }

  private static void logSettings(CommandLine cmd, Config.EnvDefaults env) {
    for (OptionSpec o : cmd.getCommandSpec().options()) {
      if (o.usageHelp() || o.versionHelp()) continue;
      String variable = env.source(o);
      String source =
          cmd.getParseResult().hasMatchedOption(o)
              ? "command line"
              : variable != null ? variable : "default";
      log.debug("Setting {} = {} ({})", o.longestName(), o.getValue(), source);
    }
  }

  private static void enableDebugLogging() {
    org.slf4j.Logger root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    if (root instanceof ch.qos.logback.classic.Logger logbackRoot) {
      logbackRoot.setLevel(ch.qos.logback.classic.Level.DEBUG);
    }
  }

  private void initValidator() {
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  private List<Mnemon> loadAndValidate(Config config) throws Exception {
    List<Mnemon> loaded = Mnemon.loadMnemones(config);
    Map<String, String> targets = new LinkedHashMap<>();
    for (Mnemon m : loaded) {
      validateMnemone(m);
      String target = m.getHost() + ":" + m.getPort();
      String other = targets.putIfAbsent(target, m.getGroup());
      if (other != null)
        throw new IllegalArgumentException(
            "Groups '"
                + other
                + "' and '"
                + m.getGroup()
                + "' both target "
                + target
                + "; each would delete the other's VMs. Merge them into one group.");
    }
    return loaded;
  }

  private void validateMnemone(Mnemon m) {
    Set<ConstraintViolation<Mnemon>> violations = validator.validate(m);
    if (violations.isEmpty()) return;
    String details =
        violations.stream()
            .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
            .map(v -> "  [" + v.getPropertyPath() + "] " + v.getMessage())
            .collect(Collectors.joining("\n"));
    throw new IllegalArgumentException(
        "Invalid configuration for group '" + m.getGroup() + "':\n" + details);
  }

  private void confirmWindow() throws InterruptedException {
    System.out.printf("Applying in %ds — Ctrl+C to abort...%n", CONFIRM_DELAY_MS / 1000);
    Thread.sleep(CONFIRM_DELAY_MS);
  }

  private void shutdown() {
    log.debug("Shutting down {} mnemones...", mnemones.size());
    for (Iris i : irides) {
      try {
        log.debug("Closing connection for mnemon '{}'...", i.mnemon.getGroup());
        i.harmonia.close();
        log.debug("Mnemon '{}' closed successfully", i.mnemon.getGroup());
      } catch (Exception e) {
        log.error(
            "Failed to close connection for mnemon '{}': {}",
            i.mnemon.getGroup(),
            e.getMessage(),
            e);
      }
    }
    log.debug("Stopping CloudInitServer...");
    CloudInitServer.stop();
    log.debug("CloudInitServer stopped");
  }

  private static Throwable getRootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }
}
