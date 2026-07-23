package com.mnemosyne.app;

import com.mnemosyne.app.config.Config;
import com.mnemosyne.app.http.CloudInitServer;
import com.mnemosyne.app.libvirt.Harmonia;
import com.mnemosyne.app.model.*;
import com.mnemosyne.app.output.Report;
import jakarta.validation.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

class Mnemosyne {

  private record Iris(Mnemon mnemon, Harmonia harmonia) {}

  private static final Logger log = LoggerFactory.getLogger(Mnemosyne.class);
  private static final long CONFIRM_DELAY_MS = 10000L;

  private Validator validator;
  private List<Mnemon> mnemones;
  private List<Iris> irides = new ArrayList<>();

  public Mnemosyne() {}

  public static void main(String[] args) {
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

    try {
      new Mnemosyne().run(config);
    } catch (Exception e) {
      log.error("Fatal {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  public void run(Config config) throws Exception {
    initValidator();
    mnemones = loadAndValidate(config);
    CloudInitServer.start();
    try {
      for (Mnemon m : mnemones) {
        Harmonia h = new Harmonia(m.getGroup(), m.getUser(), m.getKey(), m.getHost(), m.getPort());
        irides.add(new Iris(m, h));
      }

      Report.heading("Plan");
      for (Iris i : irides) {
        i.harmonia
            .plan(i.mnemon().getServers(), config.isDeleteDisable())
            .print(i.mnemon.getGroup(), config.isJoin());
      }

      if (config.isPlanOnly()) return;
      confirmWindow();

      Report.heading("Applied");
      for (Iris i : irides) {
        if (config.isJoin()) i.harmonia.join();
        else i.harmonia().reconcile();
      }
      log.info("All {} mnemones provisioned. Waiting cloud-init is done...", mnemones.size());
      CloudInitServer.waitForCloudInit().get();
    } finally {
      shutdown();
    }
  }

  private void initValidator() {
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  private List<Mnemon> loadAndValidate(Config config) throws Exception {
    List<Mnemon> loaded = Mnemon.loadMnemones(config);
    for (Mnemon m : loaded) {
      validateMnemone(m);
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
  // EndClass
}
