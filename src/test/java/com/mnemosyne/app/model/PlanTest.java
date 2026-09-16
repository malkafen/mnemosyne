package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

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
    Plan result = new Plan(actual, servers, false);
    // Assert
    assertThat(result.getToCreate().keySet()).containsExactly("toCreate");
    assertThat(result.getToUpdate().keySet()).containsExactly("toUpdate");
    assertThat(result.getToAdopt().keySet()).containsExactly("toAdopt");

    assertThat(result.getToDelete().keySet()).containsExactly("toDelete-vm");
    assertThat(result.getToDelete()).containsExactly(entry("toDelete-vm", TestData.DISKPATHS));

    assertThat(result.getToDelete()).doesNotContainKey("neverToDelete");

    assertThat(result.getUnmanaged()).containsExactly("neverToDelete", "toAdopt");
  }

  @Test
  void plan_buildsCorrectly_deleteDisable() {

    // Arrange
    Map<String, Server> servers = TestData.sampleServers();
    List<DomainState> actual = TestData.sampleDomainStates();
    // Act
    Plan result = new Plan(actual, servers, true);
    // Assert
    assertThat(result.getToCreate().keySet()).containsExactly("toCreate");
    assertThat(result.getToUpdate().keySet()).containsExactly("toUpdate");
    assertThat(result.getToAdopt().keySet()).containsExactly("toAdopt");

    assertThat(result.getToDelete()).isEmpty();

    assertThat(result.getUnmanaged()).containsExactly("neverToDelete", "toAdopt");
  }

  private static Server server(int cpu, long ram) {
    Server s = new Server();
    s.setId("web-01");
    s.setCpu(cpu);
    s.setRam(ram);
    return s;
  }

  private static DomainState domain(int cpu, long ram) {
    return new DomainState("web-01", cpu, ram, "web-01", "1", "mnemosyne", List.of(), true, false);
  }

  @Test
  void plan_onlyRamDiffers_landsInToUpdate() {
    // RAM drift used to be filtered out of the plan entirely.
    // Arrange
    Map<String, Server> servers = Map.of("web-01", server(2, 4096));
    // Act
    Plan result = new Plan(List.of(domain(2, 2048)), servers, false);
    // Assert
    assertThat(result.getToUpdate().keySet()).containsExactly("web-01");
    assertThat(result.getToCreate()).isEmpty();
    assertThat(result.getToDelete()).isEmpty();
  }

  @Test
  void plan_nothingDiffers_staysOutOfToUpdate() {
    // Arrange
    Map<String, Server> servers = Map.of("web-01", server(2, 2048));
    // Act
    Plan result = new Plan(List.of(domain(2, 2048)), servers, false);
    // Assert
    assertThat(result.getToUpdate()).isEmpty();
  }

  @Test
  void update_diff_reportsRamAlone() {
    // Act
    String diff = new Plan.Update(server(2, 4096), domain(2, 2048)).diff();
    // Assert
    assertThat(diff).isEqualTo("ram 2048->4096");
  }

  @Test
  void update_diff_reportsCpuAndRamTogether() {
    // One VM, one line: Harmonia reports the pair as a single update.
    // Act
    String diff = new Plan.Update(server(4, 4096), domain(2, 2048)).diff();
    // Assert
    assertThat(diff).isEqualTo("cpu 2->4, ram 2048->4096");
  }
}
