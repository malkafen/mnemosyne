package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

public class InitMarkerTest {

  private static final Instant CREATED = Instant.parse("2026-09-25T10:00:03.456Z");
  private static final Instant FINISHED = Instant.parse("2026-09-25T10:04:41.9Z");

  @Test
  void pending_carriesA128BitHexToken_differentEveryTime() {
    // Act
    InitMarker a = InitMarker.pending(CREATED);
    InitMarker b = InitMarker.pending(CREATED);
    // Assert
    assertThat(a.token()).matches("[0-9a-f]{32}");
    assertThat(a.token()).isNotEqualTo(b.token());
    assertThat(a.isPending()).isTrue();
    assertThat(a.created()).isEqualTo("2026-09-25T10:00:03Z");
    assertThat(a.finished()).isNull();
  }

  @Test
  void finish_keepsTokenAndCreated_andAddsTheTime() {
    // Arrange
    InitMarker pending = InitMarker.pending(CREATED);
    // Act
    InitMarker finished = pending.finish(FINISHED);
    // Assert
    assertThat(finished)
        .isEqualTo(
            new InitMarker(
                "finished", pending.token(), "2026-09-25T10:00:03Z", "2026-09-25T10:04:41Z"));
  }

  @Test
  void finish_isIdempotent_aRepeatedPhoneHomeDoesNotMoveTheTime() {
    // Arrange
    InitMarker finished = InitMarker.pending(CREATED).finish(FINISHED);
    // Act & Assert
    assertThat(finished.finish(FINISHED.plusSeconds(60))).isSameAs(finished);
  }

  @Test
  void toXml_writesOnlyTheAttributesThatAreSet() {
    // Act & Assert
    assertThat(InitMarker.adopted(CREATED).toXml())
        .isEqualTo("<init state='adopted' created='2026-09-25T10:00:03Z'/>");
    assertThat(new InitMarker("finished", "ab", "c", "d").toXml())
        .isEqualTo("<init state='finished' token='ab' created='c' finished='d'/>");
  }

  @Test
  void isKnown_onlyForTheThreeStates() {
    // Act & Assert
    assertThat(new InitMarker("pending", null, null, null).isKnown()).isTrue();
    assertThat(new InitMarker("finished", null, null, null).isKnown()).isTrue();
    assertThat(new InitMarker("adopted", null, null, null).isKnown()).isTrue();
    assertThat(new InitMarker("done", null, null, null).isKnown()).isFalse();
    assertThat(new InitMarker(null, null, null, null).isKnown()).isFalse();
  }
}
