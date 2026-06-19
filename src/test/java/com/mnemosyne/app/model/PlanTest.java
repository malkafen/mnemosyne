package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mnemosyne.app.config.*;
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

    // Arange
    Map<String, Server> servers = TestData.sampleServers();
    List<DomainState> actual = TestData.sampleDomainStates();

    // Act
    Plan result = new Plan(actual, servers);

    // Assert
    assertThat(result.getToUpdate()).contains("toUpdate");
  }
}
