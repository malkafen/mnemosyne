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
      names = "--purge-disks",
      description =
          "When deleting a VM, also delete volumes Mnemosyne did not create (adopted VMs, disks"
              + " attached by hand), unless another domain uses them.")
  private boolean purgeDisks = false;

  /**
   * How many VMs of one group are applied at once.
   *
   * <p>One by default, which is what every version before this one did. Applying several VMs at a
   * time spends somebody else's hypervisor — it is their disk that copies the base images — so it
   * is asked for rather than assumed, and an existing invocation keeps behaving exactly as it did.
   *
   * <p>Worth knowing when raising it: libvirtd answers at most five requests per client connection
   * at a time out of the box ({@code max_client_requests}), and the work behind these calls is an
   * image being cloned, so the pool's throughput is the ceiling long before the host's CPUs are.
   */
  @Option(
      names = "--parallel",
      paramLabel = "<n>",
      description = "How many VMs of a group to apply at once (default: ${DEFAULT-VALUE}).")
  private int parallel = 1;

  @Option(
      names = {"--verbose", "-v"},
      description = "Enable debug logging (full stack traces on failure).")
  private boolean verbose = false;
}
