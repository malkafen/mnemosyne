package com.mnemosyne.app.http;

import com.mnemosyne.app.model.Server.Seed;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudInitServer {

  private static final int PORT = 8080;
  private static final String CONTEXT_PATH = "/cloud-init";
  private static final int MAX_ATTEMPTS = 20;
  private static final long POLL_INTERVAL_MS = 5000L;

  private static final Map<String, String> userData = new ConcurrentHashMap<>();
  private static final Map<String, String> metaData = new ConcurrentHashMap<>();
  private static final Map<String, String> networkConfig = new ConcurrentHashMap<>();
  private static final Logger log = LoggerFactory.getLogger(CloudInitServer.class);

  public static void register(Seed seed) {
    networkConfig.put(seed.name(), seed.networkConfig());
    userData.put(seed.name(), seed.userData());
    metaData.put(seed.name(), seed.metaData());

    log.debug("Registered cloud-init configs for '{}'", seed.name());
    log.trace("user-data for '{}':\n{}", seed.name(), seed.userData());
    log.trace("network-config for '{}':\n{}", seed.name(), seed.networkConfig());
    log.trace("meta-data for '{}':\n{}", seed.name(), seed.metaData());
  }

  private static final ExecutorService waiter =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "cloud-init-waiter");
            t.setDaemon(true);
            return t;
          });

  private static HttpServer server;
  private static int stopCounter;

  public static int getUserDataSize() {
    return userData.size();
  }

  public static void setUserData(String name, String response) {
    userData.put(name, response);
  }

  public static void setNetworkConfig(String name, String response) {
    networkConfig.put(name, response);
  }

  public static int getStopCounter() {
    return stopCounter;
  }

  public static void increaseStopCounter() {
    stopCounter++;
  }

  public static Future<Boolean> waitForCloudInit() {
    return waiter.submit(CloudInitServer::pollCloudInit);
  }

  private static boolean pollCloudInit() throws InterruptedException {
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      int done = stopCounter;
      int total = userData.size();
      log.debug("Cloud-init progress: {}/{}, attempt {}/{}", done, total, attempt, MAX_ATTEMPTS);

      if (done == total) {
        log.info("All cloud-init tasks completed ({}/{}).", done, total);
        return true;
      }
      Thread.sleep(POLL_INTERVAL_MS);
    }
    log.warn(
        "Timeout reached ({} attempts). Cloud-init not fully completed ({}/{})",
        MAX_ATTEMPTS,
        stopCounter,
        userData.size());
    return false;
  }

  public static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(PORT), 0);
    server.createContext(CONTEXT_PATH, new CloudInitHandler());
    server.setExecutor(null); // default executor
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
    log.info("Cloud-Init Server has been stopped.");
  }

  // HTTP request handler

  static class CloudInitHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      // Expected path: /cloud-init/<serverName>/<filename>
      String[] parts = exchange.getRequestURI().getPath().split("/");
      if (parts.length < 4) {
        sendResponse(exchange, 200, "Invalid path");
        return;
      }

      String serverName = parts[2];
      String filename = parts[3];

      if (userData.get(serverName) == null) {
        log.debug("Config not found for '{}'", serverName);
        sendResponse(exchange, 404, "VM Config not found");
        return;
      }

      String content = resolveContent(serverName, filename);
      if (content == null) {
        // vendor-data and anything else: acknowledge and count as a completed request
        sendResponse(exchange, 200, "");
        log.debug("There was a request requested '/{}' for '{}'", filename, serverName);
        increaseStopCounter();
        return;
      }

      sendResponse(exchange, 200, content);
    }

    /**
     * @return the response body for a known cloud-init file, or {@code null} for unhandled files
     *     (vendor-data, etc.).
     */
    private String resolveContent(String serverName, String filename) {
      switch (filename) {
        case "user-data":
          log.debug("/user-data requested for '{}'", serverName);
          return userData.get(serverName);
        case "meta-data":
          log.debug("/meta-data requested for '{}'", serverName);
          return metaData.get(serverName);
        case "network-config":
          log.debug("/network-config requested for '{}'", serverName);
          return networkConfig.get(serverName);
        default:
          return null;
      }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response)
        throws IOException {
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(statusCode, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }

    private void waitForCloudInit() throws InterruptedException {
      for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        int done = CloudInitServer.getStopCounter();
        int total = CloudInitServer.getUserDataSize();
        log.debug("Cloud-init progress: {}/{}, attempt {}/{}", done, total, attempt, MAX_ATTEMPTS);

        if (done == total) {
          log.info(
              "All cloud-init tasks completed ({}/{}). Preparing for shutdown...", done, total);
          return;
        }
        Thread.sleep(POLL_INTERVAL_MS);
      }
      log.warn(
          "Timeout reached ({} attempts). Forcing shutdown without full cloud-init completion"
              + " ({}/{})",
          MAX_ATTEMPTS,
          CloudInitServer.getStopCounter(),
          CloudInitServer.getUserDataSize());
    }
  }
}
