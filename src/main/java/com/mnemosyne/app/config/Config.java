package com.mnemosyne.app.config;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import lombok.Getter;
import picocli.CommandLine.Command;
import picocli.CommandLine.IDefaultValueProvider;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Option;

@Getter
@Command(
    name = "mnemosyne",
    mixinStandardHelpOptions = true, // auto add -h/--help and -V/--version
    versionProvider = Config.PomVersionProvider.class,
    description = "Declarative libvirt/KVM VM provisioner.",
    footer = {
      "",
      "A setting given on the command line wins over its MNEM_* variable, which wins over the"
          + " default. An empty variable counts as not set. A group's own `key` in the inventory"
          + " wins over --key and MNEM_KEY."
    })
public class Config {

  /**
   * The options that can also be set from the environment, and the variable for each: MNEM_ and the
   * option's name. These are the settings of the place Mnemosyne runs in — a container image, a pod
   * — while what a single run does (--plan, --join, --no-delete, --purge-disks, --verbose) is only
   * ever given on the command line, so that it is read in one place.
   */
  public static final Map<String, String> ENV =
      Map.of(
          "--servers-file", "MNEM_SERVERS_FILE",
          "--key", "MNEM_KEY",
          "--http-port", "MNEM_HTTP_PORT",
          "--parallel", "MNEM_PARALLEL",
          "--no-verify", "MNEM_NO_VERIFY");

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

  /**
   * Takes the default of an {@link #ENV} option from its variable.
   *
   * <p>picocli asks only for options absent from the command line, so a value it is given here is
   * one the environment decided; {@link #source} remembers which, for the debug log and for an
   * error about a value nobody typed.
   */
  public static class EnvDefaults implements IDefaultValueProvider {

    private final Function<String, String> env;
    private final Map<String, String> used = new HashMap<>();

    public EnvDefaults(Function<String, String> env) {
      this.env = env;
    }

    @Override
    public String defaultValue(ArgSpec arg) {
      if (!(arg instanceof OptionSpec option)) return null;
      String variable = ENV.get(option.longestName());
      if (variable == null) return null;
      String value = env.apply(variable);
      if (value == null || value.isBlank()) return null;
      used.put(option.longestName(), variable);
      return value.trim();
    }

    /** The variable this option's value came from, or null if it did not come from one. */
    public String source(ArgSpec arg) {
      return arg instanceof OptionSpec option ? used.get(option.longestName()) : null;
    }
  }

  @Option(
      names = {"--servers-file", "-f"},
      paramLabel = "<path>",
      description =
          "Path to the inventory YAML (env: MNEM_SERVERS_FILE, default:"
              + " /etc/mnemosyne/servers.yml).")
  private String serversPath = "/etc/mnemosyne/servers.yml";

  /**
   * The private SSH key of every group that does not name one of its own.
   *
   * <p>A container gets its key mounted at one path, and repeating that path in every group of the
   * inventory ties the inventory to the container. A group's {@code key} still wins.
   */
  @Option(
      names = "--key",
      paramLabel = "<path>",
      description =
          "Private SSH key for the groups without a `key` of their own (env: MNEM_KEY, default:"
              + " none).")
  private String key;

  /**
   * The port of the seed server the guests fetch their cloud-init from; {@code metaUrl} names it.
   */
  @Option(
      names = "--http-port",
      paramLabel = "<port>",
      description =
          "Port of the cloud-init seed server; the inventory's metaUrl must point at it (env:"
              + " MNEM_HTTP_PORT, default: 8080).")
  private int httpPort = 8080;

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
   * Turns off the check of the hypervisors' SSH host keys against known_hosts.
   *
   * <p>Off by default: without the check anyone on the network path can pose as the hypervisor and
   * receive the key-authenticated session. Meant for a throwaway lab whose hosts are rebuilt too
   * often to keep known_hosts current.
   *
   * <p>{@code fallbackValue} makes the flag mean "true" whatever the default: picocli otherwise
   * flips a boolean's default, and {@code MNEM_NO_VERIFY=true} plus {@code --no-verify} would turn
   * the check back on.
   */
  @Option(
      names = "--no-verify",
      arity = "0",
      fallbackValue = "true",
      description =
          "Do not verify the hypervisors' SSH host keys against known_hosts. Unsafe outside a"
              + " trusted lab (env: MNEM_NO_VERIFY=true).")
  private boolean skipHostKeyCheck = false;

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
      description = "How many VMs of a group to apply at once (env: MNEM_PARALLEL, default: 1).")
  private int parallel = 1;

  @Option(
      names = {"--verbose", "-v"},
      description = "Enable debug logging (full stack traces on failure).")
  private boolean verbose = false;
}
