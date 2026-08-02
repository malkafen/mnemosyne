package com.mnemosyne.app.config;

import lombok.Getter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Getter
@Command(
    name = "mnemosyne",
    mixinStandardHelpOptions = true, // auto add -h/--help and -V/--version
    version = "mnemosyne 0.1.1",
    description = "Declarative libvirt/KVM VM provisioner.")
public class Config {

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
