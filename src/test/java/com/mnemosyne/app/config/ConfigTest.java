package com.mnemosyne.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/** Command line over MNEM_* over the default, with no option able to undo another. */
@DisplayName("Settings from the command line and the environment")
public class ConfigTest {

  private Config.EnvDefaults env;

  private Config parse(Map<String, String> variables, String... args) {
    Config config = new Config();
    env = new Config.EnvDefaults(variables::get);
    new CommandLine(config).setDefaultValueProvider(env).parseArgs(args);
    return config;
  }

  @Test
  void withNeitherTheDefaultsHold() {
    Config c = parse(Map.of());
    assertThat(c.getServersPath()).isEqualTo("/etc/mnemosyne/servers.yml");
    assertThat(c.getKey()).isNull();
    assertThat(c.getHttpPort()).isEqualTo(8080);
    assertThat(c.getParallel()).isEqualTo(1);
    assertThat(c.isSkipHostKeyCheck()).isFalse();
  }

  @Test
  void theEnvironmentReplacesTheDefaults() {
    Config c =
        parse(
            Map.of(
                "MNEM_SERVERS_FILE", "/cfg/servers.yml",
                "MNEM_KEY", "/keys/id_ed25519",
                "MNEM_HTTP_PORT", "9090",
                "MNEM_PARALLEL", "3",
                "MNEM_NO_VERIFY", "true"));
    assertThat(c.getServersPath()).isEqualTo("/cfg/servers.yml");
    assertThat(c.getKey()).isEqualTo("/keys/id_ed25519");
    assertThat(c.getHttpPort()).isEqualTo(9090);
    assertThat(c.getParallel()).isEqualTo(3);
    assertThat(c.isSkipHostKeyCheck()).isTrue();
  }

  @Test
  void theCommandLineWinsOverTheEnvironment() {
    Config c =
        parse(
            Map.of("MNEM_SERVERS_FILE", "/cfg/servers.yml", "MNEM_PARALLEL", "3"),
            "-f",
            "/other.yml",
            "--parallel",
            "5");
    assertThat(c.getServersPath()).isEqualTo("/other.yml");
    assertThat(c.getParallel()).isEqualTo(5);
  }

  @Test
  void anEmptyVariableCountsAsNotSet() {
    // What a Helm template renders for a value left out.
    Config c = parse(Map.of("MNEM_PARALLEL", "", "MNEM_KEY", "  ", "MNEM_NO_VERIFY", ""));
    assertThat(c.getParallel()).isEqualTo(1);
    assertThat(c.getKey()).isNull();
    assertThat(c.isSkipHostKeyCheck()).isFalse();
  }

  @Test
  void noVerifyOnTheCommandLineNeverTurnsTheCheckBackOn() {
    // picocli flips a boolean's default by itself: with MNEM_NO_VERIFY=true the flag meant
    // "verify".
    assertThat(parse(Map.of("MNEM_NO_VERIFY", "true"), "--no-verify").isSkipHostKeyCheck())
        .isTrue();
    assertThat(parse(Map.of("MNEM_NO_VERIFY", "false"), "--no-verify").isSkipHostKeyCheck())
        .isTrue();
  }

  @Test
  void runFlagsAreNotReadFromTheEnvironment() {
    Config c =
        parse(
            Map.of(
                "MNEM_PLAN", "true",
                "MNEM_JOIN", "true",
                "MNEM_NO_DELETE", "true",
                "MNEM_PURGE_DISKS", "true",
                "MNEM_VERBOSE", "true"));
    assertThat(c.isPlanOnly()).isFalse();
    assertThat(c.isJoin()).isFalse();
    assertThat(c.isDeleteDisable()).isFalse();
    assertThat(c.isPurgeDisks()).isFalse();
    assertThat(c.isVerbose()).isFalse();
  }

  @Test
  void aBadValueIsTracedToItsVariable() {
    assertThatThrownBy(() -> parse(Map.of("MNEM_PARALLEL", "two")))
        .isInstanceOfSatisfying(
            ParameterException.class,
            e -> assertThat(env.source(e.getArgSpec())).isEqualTo("MNEM_PARALLEL"));
  }

  @Test
  void aBadValueTypedOnTheCommandLineIsNotBlamedOnTheVariable() {
    assertThatThrownBy(() -> parse(Map.of("MNEM_PARALLEL", "3"), "--parallel", "two"))
        .isInstanceOfSatisfying(
            ParameterException.class, e -> assertThat(env.source(e.getArgSpec())).isNull());
  }
}
