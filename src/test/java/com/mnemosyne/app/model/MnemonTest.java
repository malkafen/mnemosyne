package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mnemosyne.app.config.*;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class MnemonTest {
  Mnemon mnemon = new Mnemon();

  @Test
  void loadMnemones_returnsCorrectGroupsAndServers() throws IOException {
    // Arrange
    Config config = mock(Config.class);
    String testFile = getClass().getClassLoader().getResource("test-servers.yml").getPath();
    when(config.getServersPath()).thenReturn(testFile);

    // Act
    List<Mnemon> result = Mnemon.loadMnemones(config);

    // Assert
    assertThat(result).hasSize(3);
    assertThat(result.get(0).getServers()).hasSize(2);
    assertThat(result.get(1).getServers()).hasSize(1);
    assertThat(result.get(2).getServers()).hasSize(2);
  }
}
