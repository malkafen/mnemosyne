package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Group servers validation")
public class MnemonServersValidationTest {

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

  private static Mnemon group() {
    Mnemon m = new Mnemon();
    m.setGroup("hv01");
    m.setUser("virtops");
    m.setHost("192.0.2.10");
    m.setKey("/home/virtops/.ssh/id_ed25519");
    m.setPort(22);
    m.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    return m;
  }

  @Test
  void aGroupWithNoServersPasses() {
    assertThat(validator.validate(group())).isEmpty();
  }

  @Test
  void anEmptyServersMapPasses() {
    Mnemon m = group();
    m.setServers(Map.of());
    assertThat(validator.validate(m)).isEmpty();
  }

  @Test
  void anEmptyServersBlockIsReadAsNoServers() {
    Mnemon m = group();
    m.setServers(null);
    assertThat(m.getServers()).isEmpty();
    assertThat(validator.validate(m)).isEmpty();
  }
}
