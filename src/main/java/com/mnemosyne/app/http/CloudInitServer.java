package com.mnemosyne.app.http;

import com.mnemosyne.app.model.Server.Seed;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudInitServer {

  private static HttpServer server;
  private static final int PORT = 8080;
  private static final String CONTEXT_PATH = "/cloud-init";
  private static final int HTTP_WORKERS = 4;
  private static final long POLL_INTERVAL_MS = 5000L;
  private static final long WAIT_TIMEOUT_MS = Duration.ofMinutes(5).toMillis();

  private static final Map<String, Seed> seeds = new ConcurrentHashMap<>();
  private static final Set<String> fetched = ConcurrentHashMap.newKeySet();
  private static final Set<String> done = ConcurrentHashMap.newKeySet();

  private static final Logger log = LoggerFactory.getLogger(CloudInitServer.class);

  public static void register(Seed seed) {
    seeds.put(seed.name(), seed);
    fetched.remove(seed.name());
    done.remove(seed.name());
    log.debug("Registered cloud-init configs for '{}'", seed.name());
    log.trace("user-data for '{}':\n{}", seed.name(), seed.userData());
    log.trace("network-config for '{}':\n{}", seed.name(), seed.networkConfig());
    log.trace("meta-data for '{}':\n{}", seed.name(), seed.metaData());
  }

  public static void unregister(String name) {
    seeds.remove(name);
    fetched.remove(name);
    done.remove(name);
    log.debug("Unregistered cloud-init configs for '{}'", name);
  }

  private static ExecutorService httpWorkers;

  private static final ExecutorService waiter =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "cloud-init-waiter");
            t.setDaemon(true);
            return t;
          });

  public static Future<Boolean> waitForCloudInit() {
    return waiter.submit(CloudInitServer::pollCloudInit);
  }

  private static boolean pollCloudInit() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS);

    while (!done.containsAll(seeds.keySet())) {
      if (System.nanoTime() >= deadline) {
        reportTimeout();
        return false;
      }
      log.debug("cloud-init finished on {}/{} servers", done.size(), seeds.size());
      Thread.sleep(POLL_INTERVAL_MS);
    }
    log.info("cloud-init finished on all {} servers", seeds.size());
    return true;
  }

  private static void reportTimeout() {
    log.warn(
        "Timeout after {} min — cloud-init finished on {}/{} servers",
        WAIT_TIMEOUT_MS / 60_000,
        done.size(),
        seeds.size());
    for (String name : seeds.keySet()) {
      if (done.contains(name)) continue;
      log.warn(
          "  '{}' — {}",
          name,
          fetched.contains(name) ? "seed fetched, no phone_home" : "seed was never fetched");
    }
  }

  public static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(PORT), 0);
    server.createContext(CONTEXT_PATH, new CloudInitHandler());
    httpWorkers =
        Executors.newFixedThreadPool(
            HTTP_WORKERS,
            r -> {
              Thread t = new Thread(r, "cloud-init-http");
              t.setDaemon(true);
              return t;
            });
    server.setExecutor(httpWorkers);
    server.start();
    log.info("Cloud-Init server is running on port '{}'", PORT);
  }

  public static void stop() {
    waiter.shutdownNow();
    if (server == null) {
      log.debug("Cloud-Init Server was not running, nothing to stop.");
      return;
    }
    server.stop(0);
    if (httpWorkers != null) httpWorkers.shutdownNow();
    log.info("Cloud-Init Server has been stopped.");
  }

  // HTTP request handler

  static class CloudInitHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      // Expected path: /cloud-init/<serverName>/<filename>
      String[] parts = exchange.getRequestURI().getPath().split("/");
      if (parts.length < 4) {
        sendResponse(exchange, 404, "Invalid path");
        return;
      }

      String serverName = parts[2];
      String filename = parts[3];

      Seed seed = seeds.get(serverName);
      if (seed == null) {
        log.info("Seed not found for '{}'", serverName);
        sendResponse(exchange, 404, "VM Config not found");
        return;
      }

      if ("phone-home".equals(filename)) {
        handlePhoneHome(exchange, serverName);
        return;
      }

      log.debug("Metadata request: server={}, filename={}", serverName, filename);
      String content =
          switch (filename) {
            case "meta-data" -> seed.metaData();
            case "user-data" -> seed.userData();
            case "network-config" -> seed.networkConfig();
            case "vendor-data" -> "";
            default -> null;
          };

      if (content == null) {
        sendResponse(exchange, 404, "");
        log.error("Unknown filename '{}' requested for server '{}'", filename, serverName);
        return;
      }
      fetched.add(serverName);
      sendResponse(exchange, 200, content);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response)
        throws IOException {
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(statusCode, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }

    private void handlePhoneHome(HttpExchange exchange, String serverName) throws IOException {
      if (!"POST".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Allow", "POST");
        sendResponse(exchange, 405, "phone_home must be POSTed");
        return;
      }
      // The form body (instance_id, hostname, fqdn) must be read before responding: an
      // unread request body breaks the connection, and cloud-init will count the attempt as failed.
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

      if (done.add(serverName)) log.debug("cloud-init finished on '{}'", serverName);
      else log.debug("Repeated phone_home from '{}'", serverName);
      log.trace("phone_home body from '{}': {}", serverName, body);

      sendResponse(exchange, 200, "");
    }
  }
}
