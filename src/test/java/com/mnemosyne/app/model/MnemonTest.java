package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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

  @Test
  void loadMnemones_extraDiskWithoutAPool_inheritsTheServersOwn() throws IOException {
    // A VM's disks stay together unless one is deliberately moved, and the value is filled in at
    // load time so validation and the plan both see one pool rather than a null to resolve later.
    // Arrange
    Config config = mock(Config.class);
    String testFile = getClass().getClassLoader().getResource("test-servers.yml").getPath();
    when(config.getServersPath()).thenReturn(testFile);
    // Act
    List<Mnemon> result = Mnemon.loadMnemones(config);
    Server s = result.get(0).getServers().get("core-db-test-a.example.lan");
    // Assert
    assertThat(s.getExtraDisks())
        .extracting(ExtraDisk::getName, ExtraDisk::getSize, ExtraDisk::getPool)
        .containsExactly(tuple("data", 40, "default"), tuple("logs", 50, "fast-ssd"));
  }

  @Test
  void loadMnemones_serversWithoutExtraDisks_getAnEmptyList() throws IOException {
    // Absent means none, never null: every caller iterates the list unguarded.
    // Arrange
    Config config = mock(Config.class);
    String testFile = getClass().getClassLoader().getResource("test-servers.yml").getPath();
    when(config.getServersPath()).thenReturn(testFile);
    // Act
    List<Mnemon> result = Mnemon.loadMnemones(config);
    // Assert
    assertThat(result.get(0).getServers().get("core-db-test-b.example.lan").getExtraDisks())
        .isEmpty();
  }
}
