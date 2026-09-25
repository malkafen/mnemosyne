package com.mnemosyne.app.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mnemosyne.app.model.Server.Seed;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seed and phone_home are served to the guest that holds the token, and a phone_home is
 * answered 200 only once it has been recorded.
 */
@DisplayName("CloudInitServer, token and phone_home")
public class CloudInitServerTest {

  private static final String NAME = "qa-a.dev.lab";
  private static final String TOKEN = "0123456789abcdef0123456789abcdef";

  private final ByteArrayOutputStream body = new ByteArrayOutputStream();

  @AfterEach
  void unregister() {
    CloudInitServer.unregister(NAME);
  }

  private static Seed seed() {
    return new Seed(NAME, TOKEN, "meta", "user", "net");
  }

  private HttpExchange request(String method, String path) {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getRequestURI()).thenReturn(URI.create(path));
    lenient().when(exchange.getRequestMethod()).thenReturn(method);
    lenient()
        .when(exchange.getRequestBody())
        .thenReturn(new ByteArrayInputStream("instance_id=qa-a".getBytes(StandardCharsets.UTF_8)));
    lenient().when(exchange.getResponseHeaders()).thenReturn(new Headers());
    lenient().when(exchange.getResponseBody()).thenReturn(body);
    return exchange;
  }

  private void handle(HttpExchange exchange) throws Exception {
    new CloudInitServer.CloudInitHandler().handle(exchange);
  }

  @Test
  void theSeedIsServedWithTheRightToken() throws Exception {
    // Arrange
    CloudInitServer.register(seed(), () -> {});
    HttpExchange exchange = request("GET", "/cloud-init/" + NAME + "/" + TOKEN + "/user-data");
    // Act
    handle(exchange);
    // Assert
    verify(exchange).sendResponseHeaders(eq(200), anyLong());
    assertThat(body.toString(StandardCharsets.UTF_8)).isEqualTo("user");
  }

  @Test
  void aWrongTokenIsAnsweredLikeAnUnknownName() throws Exception {
    // Arrange
    CloudInitServer.register(seed(), () -> {});
    HttpExchange wrong =
        request("GET", "/cloud-init/" + NAME + "/" + "f".repeat(32) + "/user-data");
    HttpExchange unknown = request("GET", "/cloud-init/nobody/" + TOKEN + "/user-data");
    // Act
    handle(wrong);
    handle(unknown);
    // Assert
    verify(wrong).sendResponseHeaders(eq(404), anyLong());
    verify(unknown).sendResponseHeaders(eq(404), anyLong());
    assertThat(body.toString(StandardCharsets.UTF_8))
        .isEqualTo("VM Config not found" + "VM Config not found");
  }

  @Test
  void theOldPathWithoutAToken_isRefused() throws Exception {
    // Arrange
    CloudInitServer.register(seed(), () -> {});
    HttpExchange exchange = request("GET", "/cloud-init/" + NAME + "/user-data");
    // Act
    handle(exchange);
    // Assert
    verify(exchange).sendResponseHeaders(eq(404), anyLong());
  }

  @Test
  void phoneHomeIsRecordedFirst_thenAnswered200() throws Exception {
    // Arrange
    AtomicInteger confirmed = new AtomicInteger();
    CloudInitServer.register(seed(), confirmed::incrementAndGet);
    // Act
    handle(request("POST", "/cloud-init/" + NAME + "/" + TOKEN + "/phone-home"));
    // Assert
    assertThat(confirmed).hasValue(1);
    assertThat(CloudInitServer.initialized(NAME)).isTrue();
    assertThat(CloudInitServer.unfinished()).doesNotContain(NAME);
  }

  @Test
  void aPhoneHomeThatCannotBeRecorded_isAnswered500_soCloudInitRetries() throws Exception {
    // Arrange
    CloudInitServer.register(
        seed(),
        () -> {
          throw new IllegalStateException("libvirt connection lost");
        });
    HttpExchange exchange = request("POST", "/cloud-init/" + NAME + "/" + TOKEN + "/phone-home");
    // Act
    handle(exchange);
    // Assert
    verify(exchange).sendResponseHeaders(eq(500), anyLong());
    assertThat(CloudInitServer.initialized(NAME)).isFalse();
  }

  @Test
  void aPhoneHomeWithAWrongToken_isNeverRecorded() throws Exception {
    // Arrange
    AtomicInteger confirmed = new AtomicInteger();
    CloudInitServer.register(seed(), confirmed::incrementAndGet);
    // Act
    handle(request("POST", "/cloud-init/" + NAME + "/" + "0".repeat(32) + "/phone-home"));
    // Assert
    assertThat(confirmed).hasValue(0);
    assertThat(CloudInitServer.initialized(NAME)).isFalse();
  }
}
