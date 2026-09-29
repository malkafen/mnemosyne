package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A server's name is the libvirt domain, the guest's host name, the volume file and a path segment
 * of the seed URL all at once. The inventory is the only place where one rule can cover all four.
 */
@DisplayName("Server name and id validation")
public class ServerNameValidationTest {

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

  private static Server server(String id, String name) {
    Server s = new Server();
    s.setId(id);
    s.setName(name);
    s.setCpu(2);
    s.setRam(2048);
    s.setIp("192.0.2.40/24");
    s.setGateway("192.0.2.1");
    s.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    s.setExtraDisks(List.of());
    return s;
  }

  private static List<String> violations(Server s) {
    return validator.validate(s).stream().map(v -> v.getPropertyPath().toString()).toList();
  }

  @ParameterizedTest
  @ValueSource(strings = {"web-01", "Web01", "1", "web-01.example.lan", "a.b.c"})
  void aHostNamePasses(String value) {
    assertThat(violations(server(value, null))).isEmpty();
    assertThat(violations(server("web-01", value))).isEmpty();
  }

  @Test
  void sixtyThreeCharactersPerLabelIsStillAllowed() {
    assertThat(violations(server("a".repeat(63), null))).isEmpty();
  }

  static Stream<Arguments> badNames() {
    return Stream.of(
        Arguments.of("empty", ""),
        Arguments.of("underscore", "web_01"),
        Arguments.of("slash", "web/01"),
        Arguments.of("space", "web 01"),
        Arguments.of("leading dash", "-web"),
        Arguments.of("trailing dash", "web-"),
        Arguments.of("empty label", "web..lan"),
        Arguments.of("trailing dot", "web.lan."),
        Arguments.of("64-character label", "a".repeat(64)),
        Arguments.of("254 characters", ("a".repeat(62) + ".").repeat(4) + "ab"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badNames")
  void aNameThatIsNotAHostNameIsRejected(String label, String value) {
    assertThat(violations(server("web-01", value))).contains("name");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badNames")
  void anIdThatIsNotAHostNameIsRejected(String label, String value) {
    // Without `name` the id is the domain name, so it is held to the same rule.
    assertThat(violations(server(value, null))).contains("id");
  }
}
