package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code user} and {@code host} go into the libvirt URI as they are. Anything that could end the
 * authority part and start URI parameters of its own (libvirt runs {@code command=} locally) has to
 * be refused before a connection is attempted, {@code --plan} included.
 */
@DisplayName("Group user and host validation")
public class MnemonConnectionValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static Mnemon group(String user, String host) {
    Mnemon m = new Mnemon();
    m.setGroup("hv01");
    m.setUser(user);
    m.setHost(host);
    m.setKey("/home/virtops/.ssh/id_ed25519");
    m.setPort(22);
    m.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    m.setServers(Map.of());
    return m;
  }

  private static List<String> violations(Mnemon m) {
    return validator.validate(m).stream().map(v -> v.getPropertyPath().toString()).toList();
  }

  @ParameterizedTest
  @ValueSource(strings = {"virtops", "_svc", "deploy.bot", "ci-runner_2"})
  void aUserNamePasses(String user) {
    assertThat(violations(group(user, "192.0.2.10"))).doesNotContain("user");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "virtops@127.0.0.1:2222/system?command=/tmp/pwn.sh&x=",
        "virtops?command=/tmp/pwn.sh",
        "vir&tops",
        "vir tops",
        "-oProxyCommand=x",
        "1virtops"
      })
  void aUserWithUriSyntaxIsRefused(String user) {
    assertThat(violations(group(user, "192.0.2.10"))).contains("user");
  }

  @ParameterizedTest
  @ValueSource(strings = {"192.0.2.10", "hv01", "hv01.example.lan"})
  void aHostNamePasses(String host) {
    assertThat(violations(group("virtops", host))).doesNotContain("host");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "192.0.2.10/system?command=/tmp/pwn.sh",
        "192.0.2.10:2222",
        "evil@192.0.2.10",
        "hv01#x",
        "hv01&command=x",
        "-oProxyCommand=x"
      })
  void aHostWithUriSyntaxIsRefused(String host) {
    assertThat(violations(group("virtops", host))).contains("host");
  }
}
