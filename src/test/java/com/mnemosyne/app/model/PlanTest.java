package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.mnemosyne.app.testutil.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class PlanTest {
  @Test
  void plan_buildsCorrectly() {

    // Arrange
    Map<String, Server> servers = TestData.sampleServers();
    List<DomainState> actual = TestData.sampleDomainStates();
    // Act
    Plan result = new Plan(actual, servers, true);
    // Assert
    assertThat(result.getToCreate().keySet()).containsExactly("toCreate");
    assertThat(result.getToUpdate().keySet()).containsExactly("toUpdate");
    assertThat(result.getToAdopt().keySet()).containsExactly("toAdopt");

    assertThat(result.getToDelete()).containsExactly("toDelete-vm");
    assertThat(result.getToDelete()).doesNotContain("neverToDelete");

    assertThat(result.getUnmanaged()).containsExactly("neverToDelete", "toAdopt");
  }

  @Test
  void plan_buildsCorrectly_deleteDisable() {

    // Arrange
    Map<String, Server> servers = TestData.sampleServers();
    List<DomainState> actual = TestData.sampleDomainStates();
    // Act
    Plan result = new Plan(actual, servers, false);
    // Assert
    assertThat(result.getToCreate().keySet()).containsExactly("toCreate");
    assertThat(result.getToUpdate().keySet()).containsExactly("toUpdate");
    assertThat(result.getToAdopt().keySet()).containsExactly("toAdopt");

    assertThat(result.getToDelete()).isEmpty();

    assertThat(result.getUnmanaged()).containsExactly("neverToDelete", "toAdopt");
  }
}
