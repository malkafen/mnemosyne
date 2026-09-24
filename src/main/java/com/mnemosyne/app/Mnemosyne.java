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
import picocli.CommandLine.ParameterException;

class Mnemosyne {

  private record Iris(Mnemon mnemon, Harmonia harmonia) {}

  private static final Logger log = LoggerFactory.getLogger(Mnemosyne.class);
  private static final long CONFIRM_DELAY_MS = 10000L;

  /** Everything went as planned. */
  private static final int EXIT_OK = 0;

  /**
   * The run finished, but not everything in it did: a VM was skipped, or a guest never came back
   * over cloud-init. Both leave the host short of the inventory, and both used to end in exit 0 —
   * which made a run where no guest was configured at all indistinguishable, to anything automated,
   * from a clean one. It shares the code of a fatal error on purpose: for a caller there are two
   * answers, "the inventory is now on the host" and "it is not".
   */
  private static final int EXIT_INCOMPLETE = 1;

  private Validator validator;
  private List<Mnemon> mnemones;
  private List<Iris> irides = new ArrayList<>();

  public Mnemosyne() {}

  public static void main(String[] args) {
    // Keep the user-facing report (stdout) readable regardless of the console's default charset.
    System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

    Config config = new Config();
    CommandLine cmd = new CommandLine(config);

    try {
      cmd.parseArgs(args);
    } catch (ParameterException e) {
      cmd.getErr().println(e.getMessage());
      e.getCommandLine().usage(cmd.getErr()); // print usage
      System.exit(cmd.getCommandSpec().exitCodeOnInvalidInput()); // = 2
      return;
    }

    if (cmd.isUsageHelpRequested() || cmd.isVersionHelpRequested()) {
      if (cmd.isUsageHelpRequested()) cmd.usage(cmd.getOut());
      else cmd.printVersionHelp(cmd.getOut());
      return;
    }

    if (config.getParallel() < 1) {
      cmd.getErr().println("--parallel must be at least 1");
      System.exit(cmd.getCommandSpec().exitCodeOnInvalidInput()); // = 2
      return;
    }

    if (config.isVerbose()) enableDebugLogging();

    try {
      int exitCode = new Mnemosyne().run(config);
      if (exitCode != EXIT_OK) System.exit(exitCode);
    } catch (Exception e) {
      Throwable root = getRootCause(e);
      // A failure raised by Mnemosyne itself is its own root cause; repeating it reads as noise.
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
        Harmonia h = new Harmonia(m.getGroup(), m.getUser(), m.getKey(), m.getHost(), m.getPort());
        irides.add(new Iris(m, h));
      }

      Report.heading("Plan");
      Map<String, Preflight> blocked = new LinkedHashMap<>();
      for (Iris i : irides) {
        Plan plan =
            i.harmonia.plan(
                i.mnemon().getServers(), config.isDeleteDisable(), config.isPurgeDisks());
        // Adoption creates nothing, so there is nothing to check and nothing to audit for it.
        Preflight preflight = config.isJoin() ? new Preflight() : i.harmonia.preflight();
        plan.print(i.mnemon.getGroup(), config.isJoin(), preflight);
        if (!preflight.ok()) blocked.put(i.mnemon.getGroup(), preflight);
      }
      if (!blocked.isEmpty()) stop(blocked.size());

      if (config.isPlanOnly()) return EXIT_OK;
      CloudInitServer.start();
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

      // Settling happens whatever cloud-init did - a launch:false VM must not be left running
      // against the inventory - and its own failures count towards the exit code below.
      if (irides.stream().anyMatch(i -> i.harmonia().hasPendingStop())) {
        Report.heading("Settled");
        for (Iris i : irides) i.harmonia().settle(config.getParallel());
      }
      return outcome(cloudInitOk);
    } finally {
      shutdown();
    }
  }

  /**
   * The run's verdict, printed and returned as the exit code.
   *
   * <p>Per-VM failures do not stop a run — that is the contract, and it is the right one — but the
   * operator's terminal is not the only thing reading the result. A scheduler, a CI job or the next
   * step of a pipeline has nothing but the exit code, and "three of five VMs were created and none
   * of them was ever configured" has to reach it. So the skipped entries of every group and the
   * guests that never phoned home are summed up here, once, after everything else has had its say.
   *
   * <p>The line names both numbers, because they are different failures with different fixes: a
   * skipped entry was refused by the host and is in the report above with its reason, while a guest
   * that did not phone home exists, runs, and is simply not configured.
   */
  private int outcome(boolean cloudInitOk) {
    int skipped = irides.stream().mapToInt(i -> i.harmonia().failures()).sum();
    List<String> pending = CloudInitServer.unfinished();
    if (skipped == 0 && cloudInitOk && pending.isEmpty()) return EXIT_OK;

    StringJoiner why = new StringJoiner(", ");
    if (skipped > 0) why.add(skipped + " entr" + (skipped == 1 ? "y" : "ies") + " skipped");
    if (!pending.isEmpty())
      why.add(
          String.format(
              "cloud-init did not finish on %d server(s): %s",
              pending.size(), String.join(", ", pending)));
    else if (!cloudInitOk) why.add("the wait for cloud-init did not complete");

    System.out.printf("%nRun finished incomplete: %s.%n", why);
    return EXIT_INCOMPLETE;
  }

  /**
   * Nothing is applied while any VM in the plan is blocked. Creation needs a storage pool, a base
   * image, a network and the templates; without one of them the batch would fail somewhere in the
   * middle, leaving half the inventory provisioned and the rest reported as skipped. A disk the
   * inventory wants smaller than it is blocks for a different reason — it is not a missing
   * prerequisite but an instruction that must not be carried out — and stops the run the same way.
   * The reason is already printed against the VM, so what is left to say is that none of the plan
   * was carried out.
   */
  private void stop(int blockedGroups) {
    System.out.printf(
        "Nothing was applied: %d of %d group(s) cannot be applied as planned.%n",
        blockedGroups, irides.size());
    throw new IllegalStateException("preflight failed");
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
    // Deletion is scoped to the host, not the group: two groups on one host:port would each delete
    // the other's VMs as absent from the inventory.
    Map<String, String> targets = new LinkedHashMap<>();
    for (Mnemon m : loaded) {
      validateMnemone(m);
      String target = m.getHost() + ":" + m.getPort();
      String other = targets.putIfAbsent(target, m.getGroup());
      if (other != null)
        throw new IllegalArgumentException(
            "Groups '" + other + "' and '" + m.getGroup() + "' both target " + target
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
  // EndClass
}
