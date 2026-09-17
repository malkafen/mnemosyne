package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.mnemosyne.app.model.Preflight.Problem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PreflightTest {

  private static Templates templatesIn(Path dir) {
    Templates t = new Templates();
    t.setServerTmpl(dir.resolve("server.xml").toString());
    t.setVolTmpl(dir.resolve("volume.xml").toString());
    t.setMetaDataTmpl(dir.resolve("meta-data.yml").toString());
    t.setUserDataTmpl(dir.resolve("user-data.yml").toString());
    t.setNetworkConfigTmpl(dir.resolve("network-config.yml").toString());
    return t;
  }

  private static Server server(String id, Templates templates) {
    Server s = new Server();
    s.setId(id);
    s.setTemplates(templates);
    return s;
  }

  @Test
  void ok_isTrueUntilSomethingIsAdded() {
    // Arrange
    Preflight preflight = new Preflight();
    // Assert
    assertThat(preflight.ok()).isTrue();
    // Act
    preflight.add(new Problem("pool 'fast'", "not found on the host"), "web-01");
    // Assert
    assertThat(preflight.ok()).isFalse();
  }

  @Test
  void add_reportsAResourceOnceWithEveryServerThatNeedsIt() {
    // Arrange
    Preflight preflight = new Preflight();
    Problem problem = new Problem("pool 'fast'", "not found on the host");
    // Act
    preflight.add(problem, List.of("web-01", "web-02"));
    preflight.add(problem, "cache-01");
    // Assert
    assertThat(preflight.getProblems()).hasSize(1);
    assertThat(preflight.getProblems().get(problem))
        .containsExactly("web-01", "web-02", "cache-01");
  }

  @Test
  void blockers_nameEveryReasonForThatServerOnly() {
    // Arrange
    Preflight preflight = new Preflight();
    preflight.add(new Problem("pool 'fast'", "not found on the host"), List.of("web-01", "web-02"));
    preflight.add(new Problem("network 'br0'", "not running"), "web-01");
    // Act & Assert
    assertThat(preflight.blockers("web-01"))
        .containsExactly("pool 'fast' not found on the host", "network 'br0' not running");
    assertThat(preflight.blockers("web-02")).containsExactly("pool 'fast' not found on the host");
    assertThat(preflight.blockers("cache-01")).isEmpty();
  }

  @Test
  void checkTemplates_allFilesPresent_findsNothing(@TempDir Path dir) throws Exception {
    // Arrange
    Templates templates = templatesIn(dir);
    for (String path : templates.paths()) Files.writeString(Path.of(path), "x");
    // Act
    Preflight preflight = new Preflight();
    preflight.checkTemplates(server("web-01", templates));
    // Assert
    assertThat(preflight.ok()).isTrue();
  }

  @Test
  void checkTemplates_missingFile_isReportedByPath(@TempDir Path dir) throws Exception {
    // Arrange
    Templates templates = templatesIn(dir);
    for (String path : templates.paths()) Files.writeString(Path.of(path), "x");
    Files.delete(Path.of(templates.getUserDataTmpl()));
    // Act
    Preflight preflight = new Preflight();
    preflight.checkTemplates(server("web-01", templates));
    // Assert
    assertThat(preflight.getProblems())
        .containsExactly(
            entry(
                new Problem(
                    "template '" + templates.getUserDataTmpl() + "'",
                    "not readable on this machine"),
                Set.of("web-01")));
  }

  @Test
  void checkTemplates_noTemplatesAtAll_isReportedAgainstTheServer() {
    // Act
    Preflight preflight = new Preflight();
    preflight.checkTemplates(server("web-01", null));
    // Assert
    assertThat(preflight.getProblems())
        .containsExactly(entry(new Problem("templates", "not configured"), Set.of("web-01")));
  }
}
