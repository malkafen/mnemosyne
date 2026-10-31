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
    Config config = mock(Config.class);
    String testFile = getClass().getClassLoader().getResource("test-servers.yml").getPath();
    when(config.getServersPath()).thenReturn(testFile);

    List<Mnemon> result = Mnemon.loadMnemones(config);

    assertThat(result).hasSize(3);
    assertThat(result.get(0).getServers()).hasSize(2);
    assertThat(result.get(1).getServers()).hasSize(1);
    assertThat(result.get(2).getServers()).hasSize(2);
  }

  @Test
  void loadMnemones_extraDiskWithoutAPool_inheritsTheServersOwn() throws IOException {
    Config config = mock(Config.class);
    String testFile = getClass().getClassLoader().getResource("test-servers.yml").getPath();
    when(config.getServersPath()).thenReturn(testFile);
    List<Mnemon> result = Mnemon.loadMnemones(config);
    Server s = result.get(0).getServers().get("core-db-test-a.example.lan");
    assertThat(s.getExtraDisks())
        .extracting(ExtraDisk::getName, ExtraDisk::getSize, ExtraDisk::getPool)
        .containsExactly(tuple("data", 40, "default"), tuple("logs", 50, "fast-ssd"));
  }

  @Test
  void loadMnemones_serversWithoutExtraDisks_getAnEmptyList() throws IOException {
    Config config = mock(Config.class);
    String testFile = getClass().getClassLoader().getResource("test-servers.yml").getPath();
    when(config.getServersPath()).thenReturn(testFile);
    List<Mnemon> result = Mnemon.loadMnemones(config);
    assertThat(result.get(0).getServers().get("core-db-test-b.example.lan").getExtraDisks())
        .isEmpty();
  }

  @Test
  void loadMnemones_groupWithoutServers_getsAnEmptyMap() throws IOException {
    Config config = mock(Config.class);
    String testFile =
        getClass().getClassLoader().getResource("test-servers-no-servers.yml").getPath();
    when(config.getServersPath()).thenReturn(testFile);
    List<Mnemon> result = Mnemon.loadMnemones(config);
    assertThat(result).hasSize(2).allSatisfy(m -> assertThat(m.getServers()).isEmpty());
  }

  @Test
  void loadMnemones_groupWithoutAKey_takesTheDefaultOne() throws IOException {
    Config config = mock(Config.class);
    String testFile =
        getClass().getClassLoader().getResource("test-servers-default-key.yml").getPath();
    when(config.getServersPath()).thenReturn(testFile);
    when(config.getKey()).thenReturn("/keys/default");
    List<Mnemon> result = Mnemon.loadMnemones(config);
    assertThat(result)
        .extracting(Mnemon::getKey)
        .containsExactly("/own/id_ed25519", "/keys/default");
  }
}
