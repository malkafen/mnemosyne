package com.mnemosyne.app.libvirt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.libvirt.Connect;
import org.libvirt.ErrorCallback;
import org.libvirt.LibvirtException;
import org.libvirt.jna.virError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Hypervisor {

  private static final Logger log = LoggerFactory.getLogger(Hypervisor.class);

  public static Connect connect(
      String user, String key, String host, int port, boolean skipHostKeyCheck)
      throws LibvirtException, IOException {

    File keyFile = new File(key);
    log.debug("Resolving SSH key for host '{}': '{}'", host, key);
    if (!keyFile.exists() || !keyFile.isFile()) {
      throw new IOException(String.format("SSH key file not found '%s' for host '%s'", key, host));
    }
    String uri = uri(user, key, host, port, skipHostKeyCheck);
    log.debug("Connecting to hypervisor '{}' via '{}'", host, uri);

    Connect connect = new Connect(uri);
    connect.setConnectionErrorCallback(
        new ErrorCallback() {
          public void errorCallback(Object userData, virError error) {}
        });

    log.debug("Connected to '{}'", host);
    return connect;
  }

  static String uri(String user, String key, String host, int port, boolean skipHostKeyCheck) {
    return String.format(
        "qemu+ssh://%s@%s:%d/system?keyfile=%s%s&no_tty=1",
        user, host, port, encode(key), skipHostKeyCheck ? "&no_verify=1" : "");
  }

  private static String encode(String value) {
    StringBuilder sb = new StringBuilder();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xff);
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || "-._~/".indexOf(c) >= 0) {
        sb.append(c);
      } else {
        sb.append(String.format("%%%02X", (int) c));
      }
    }
    return sb.toString();
  }
}
