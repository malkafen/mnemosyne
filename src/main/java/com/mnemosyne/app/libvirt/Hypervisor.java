package com.mnemosyne.app.libvirt;

import java.io.File;
import java.io.IOException;
import org.libvirt.Connect;
import org.libvirt.ErrorCallback;
import org.libvirt.LibvirtException;
import org.libvirt.jna.virError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Hypervisor {

  private static final Logger log = LoggerFactory.getLogger(Hypervisor.class);

  public static Connect connect(String user, String key, String host, int port)
      throws LibvirtException, IOException {

    File keyFile = new File(key);
    log.debug("Resolving SSH key for host '{}': '{}'", host, key);
    if (!keyFile.exists() || !keyFile.isFile()) {
      throw new IOException(String.format("SSH key file not found '%s' for host '%s'", key, host));
    }
    String uri =
        String.format("qemu+ssh://%s@%s:%d/system?keyfile=%s&no_verify=1", user, host, port, key);
    log.debug("Connecting to hypervisor '{}' via '{}'", host, uri);

    Connect connect = new Connect(uri);
    // disable native C logging
    connect.setConnectionErrorCallback(
        new ErrorCallback() {
          public void errorCallback(Object userData, virError error) {}
        });

    log.info("Connection to '{}' was successful.", host);
    return connect;
  }
}
