package com.mnemosyne.app.config;

import java.io.InputStream;
import java.util.Properties;
import lombok.Getter;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;

@Getter
@Command(
    name = "mnemosyne",
    mixinStandardHelpOptions = true, // auto add -h/--help and -V/--version
    versionProvider = Config.PomVersionProvider.class,
    description = "Declarative libvirt/KVM VM provisioner.")
public class Config {

  /** Reads the version from mnemosyne.properties, filled in by Maven at build time. */
  static class PomVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws Exception {
      Properties props = new Properties();
      try (InputStream in = Config.class.getResourceAsStream("/mnemosyne.properties")) {
        if (in != null) {
          props.load(in);
        }
      }
      return new String[] {"mnemosyne " + props.getProperty("version", "unknown")};
    }
  }

  @Option(
      names = {"--servers-file", "-f"},
      paramLabel = "<path>",
      description = "Path to the inventory YAML (default: ${DEFAULT-VALUE}).")
  private String serversPath = "/etc/mnemosyne/servers.yml";

  @Option(
      names = {"--plan", "-p"},
      description = "Show the plan and exit without applying.")
  private boolean planOnly = false;

  @Option(
      names = {"--join", "-j"},
      description = "Adopt existing unmanaged domains.")
  private boolean join = false;

  @Option(
      names = "--no-delete",
      description = "Skip deletion of managed domains absent from the inventory.")
  private boolean deleteDisable = false;

  @Option(
      names = {"--verbose", "-v"},
      description = "Enable debug logging (full stack traces on failure).")
  private boolean verbose = false;
}
