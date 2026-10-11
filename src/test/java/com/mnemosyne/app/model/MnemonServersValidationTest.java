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

/**
 * A group of no VMs is a valid group. It is how a hypervisor is brought down to zero: the
 * connection stays described, and everything Mnemosyne manages on that host is deleted. Removing
 * the group from the inventory instead would leave those VMs on the host, unmanaged and unnamed by
 * any plan.
 */
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
    // `servers:` with nothing under it deserializes to null, and a null would reach the plan.
    Mnemon m = group();
    m.setServers(null);
    assertThat(m.getServers()).isEmpty();
    assertThat(validator.validate(m)).isEmpty();
  }
}
