package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The inventory is the last place a bad disk name can be caught cheaply. Past this point it becomes
 * a volume file name and a {@code <serial>}, and a name udev mangles leaves the administrator
 * without the {@code /dev/disk/by-id} path the whole design rests on.
 */
@DisplayName("ExtraDisk validation")
public class ExtraDiskValidationTest {

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

  private static ExtraDisk disk(String name, int size) {
    ExtraDisk d = new ExtraDisk();
    d.setName(name);
    d.setSize(size);
    d.setPool("default");
    return d;
  }

  private static Server server(ExtraDisk... extras) {
    Server s = new Server();
    s.setId("web-01");
    s.setCpu(2);
    s.setRam(2048);
    s.setIp("192.0.2.40/24");
    s.setGateway("192.0.2.1");
    s.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    s.setExtraDisks(List.of(extras));
    return s;
  }

  private static List<String> violations(Server s) {
    return validator.validate(s).stream().map(v -> v.getPropertyPath().toString()).toList();
  }

  @Test
  void aWellFormedDiskPasses() {
    assertThat(violations(server(disk("data", 40)))).isEmpty();
  }

  static Stream<Arguments> badNames() {
    return Stream.of(
        Arguments.of("empty", ""),
        Arguments.of("blank", "   "),
        Arguments.of("uppercase", "Data"),
        Arguments.of("underscore", "my_data"),
        Arguments.of("dot", "data.1"),
        Arguments.of("slash", "a/b"),
        Arguments.of("space", "my data"),
        Arguments.of("leading dash", "-data"),
        Arguments.of("21 characters", "abcdefghijklmnopqrstu"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badNames")
  void aNameThatWouldNotSurviveUdevIsRejected(String label, String name) {
    assertThat(violations(server(disk(name, 40)))).contains("extraDisks[0].name");
  }

  @Test
  void twentyCharactersIsStillAllowed() {
    // 20 is exactly where /dev/disk/by-id truncates for virtio, so it is the last usable length.
    assertThat(violations(server(disk("abcdefghijklmnopqrst", 40)))).isEmpty();
  }

  @Test
  void aZeroOrNegativeSizeIsRejected() {
    assertThat(violations(server(disk("data", 0)))).contains("extraDisks[0].size");
    assertThat(violations(server(disk("data", -10)))).contains("extraDisks[0].size");
  }

  @Test
  void aDiskWithNoPoolIsRejected() {
    // The pool is filled in from the server when the inventory is loaded, so a null here means
    // something built a Server without going through that path.
    // Arrange
    ExtraDisk d = disk("data", 40);
    d.setPool(null);
    // Assert
    assertThat(violations(server(d))).contains("extraDisks[0].pool");
  }

  @Test
  void twoDisksWithOneNameAreRejected() {
    // They would resolve to one volume file and one serial, and the second would silently win.
    assertThat(violations(server(disk("data", 40), disk("data", 50))))
        .contains("extraDiskNamesUnique");
  }

  @Test
  void moreDisksThanTargetNamesAreRejected() {
    // Arrange: 25 disks, one past the cap
    List<ExtraDisk> many = new ArrayList<>();
    for (int i = 0; i < 25; i++) many.add(disk("data-" + i, 10));
    // Assert
    assertThat(violations(server(many.toArray(new ExtraDisk[0])))).contains("extraDisks");
  }
}
