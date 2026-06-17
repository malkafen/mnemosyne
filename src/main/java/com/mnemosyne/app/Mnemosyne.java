package com.mnemosyne.app;

import com.mnemosyne.app.config.*;
import com.mnemosyne.app.http.*;
import com.mnemosyne.app.model.*;
import jakarta.validation.*;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.libvirt.LibvirtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Mnemosyne {

  private static final Logger log = LoggerFactory.getLogger(Mnemosyne.class);
  private static final long CONFIRM_DELAY_MS = 10000L;

  private Validator validator;
  private List<Mnemon> mnemones;

  public Mnemosyne() {}

  public static void main(String[] args) {
    try {
      Config config = new Config(args);
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
        m.setConnect();
        m.plan(config.isJoin());
      }

      if (config.isPlanOnly()) return;

      for (Mnemon m : mnemones) {
        confirmWindow();
        if (config.isJoin()) m.join();
        else m.apply();
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
    System.out.printf("%nApplying in %ds — Ctrl+C to abort...%n", CONFIRM_DELAY_MS / 1000);
    Thread.sleep(CONFIRM_DELAY_MS);
  }

  private void shutdown() {
    for (Mnemon m : mnemones) {
      m.freeDomains();
      try {
        m.closeConnect();
      } catch (LibvirtException e) {
        log.error(
            "Failed to close connection for mnemon '{}': {}", m.getGroup(), e.getMessage(), e);
      }
    }
    log.debug("Stopping CloudInitServer...");
    CloudInitServer.stop();
  }
  // EndClass
}
