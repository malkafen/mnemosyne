package com.mnemosyne.app.model;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

public record InitMarker(String state, String token, String created, String finished) {

  public static final String PENDING = "pending";
  public static final String FINISHED = "finished";
  public static final String ADOPTED = "adopted";

  private static final int TOKEN_BYTES = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  static final InitMarker ASSUMED_FINISHED = new InitMarker(FINISHED, null, null, null);

  public static InitMarker pending(Instant now) {
    return new InitMarker(PENDING, newToken(), stamp(now), null);
  }

  public static InitMarker adopted(Instant now) {
    return new InitMarker(ADOPTED, null, stamp(now), null);
  }

  public InitMarker finish(Instant now) {
    return isFinished() ? this : new InitMarker(FINISHED, token, created, stamp(now));
  }

  public boolean isPending() {
    return PENDING.equals(state);
  }

  public boolean isFinished() {
    return FINISHED.equals(state);
  }

  public boolean isKnown() {
    return isPending() || isFinished() || ADOPTED.equals(state);
  }

  public String toXml() {
    StringBuilder sb = new StringBuilder("<init");
    attr(sb, "state", state);
    attr(sb, "token", token);
    attr(sb, "created", created);
    attr(sb, "finished", finished);
    return sb.append("/>").toString();
  }

  private static void attr(StringBuilder sb, String name, String value) {
    if (value != null) sb.append(' ').append(name).append("='").append(value).append('\'');
  }

  static String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static String stamp(Instant now) {
    return now.truncatedTo(ChronoUnit.SECONDS).toString();
  }
}
