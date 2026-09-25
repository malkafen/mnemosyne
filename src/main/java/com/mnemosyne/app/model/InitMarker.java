package com.mnemosyne.app.model;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * The {@code <mnem:init>} element: whether the one-off initialization of a domain is known to have
 * finished.
 *
 * <p>It records an observed fact, not a desired state. A domain is defined with {@code pending}
 * already in its XML, so every way a creation can end early — Ctrl+C, a killed process, a
 * phone_home timeout — leaves {@code pending} behind without anybody having to write it. Only the
 * guest can turn it into {@code finished}, by phoning home with the {@code token} it was given in
 * its seed URL. A domain adopted with {@code --join} is {@code adopted}: its initialization was
 * never Mnemosyne's business.
 *
 * <p>{@code state} is kept as the string the metadata holds, so a value somebody edited in by hand
 * survives the round trip and can be named in the refusal, instead of being folded into "missing".
 * The timestamps are UTC ISO-8601 to the second; {@code finished - created} is how long the guest
 * took to come up.
 */
public record InitMarker(String state, String token, String created, String finished) {

  public static final String PENDING = "pending";
  public static final String FINISHED = "finished";
  public static final String ADOPTED = "adopted";

  /** 128 bits: the token is the only thing that tells a guest's phone_home from anybody else's. */
  private static final int TOKEN_BYTES = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * The marker a snapshot built in code carries when it says nothing about initialization. Never
   * read from a domain: {@code XmlUtil} passes what the metadata holds, absent included.
   */
  static final InitMarker ASSUMED_FINISHED = new InitMarker(FINISHED, null, null, null);

  /** A new domain's marker, with a fresh random token. */
  public static InitMarker pending(Instant now) {
    return new InitMarker(PENDING, newToken(), stamp(now), null);
  }

  public static InitMarker adopted(Instant now) {
    return new InitMarker(ADOPTED, null, stamp(now), null);
  }

  /** The same marker, confirmed by the guest. Already finished stays as it was. */
  public InitMarker finish(Instant now) {
    return isFinished() ? this : new InitMarker(FINISHED, token, created, stamp(now));
  }

  public boolean isPending() {
    return PENDING.equals(state);
  }

  public boolean isFinished() {
    return FINISHED.equals(state);
  }

  /** Whether the state is one Mnemosyne knows how to act on. */
  public boolean isKnown() {
    return isPending() || isFinished() || ADOPTED.equals(state);
  }

  /** The element for {@code setMetadata}, without a namespace prefix. */
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
