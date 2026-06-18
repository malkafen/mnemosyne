package com.mnemosyne.app.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mnemosyne.app.config.*;
import com.mnemosyne.app.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.ParserConfigurationException;
import lombok.Getter;
import lombok.Setter;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.Error;
import org.libvirt.ErrorCallback;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;
import org.libvirt.StorageVol;
import org.libvirt.jna.virError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;
import org.yaml.snakeyaml.LoaderOptions;

@Getter
@Setter
public class Mnemon {
  @NotBlank(message = "Group name must not be blank")
  private String group;

  @NotBlank(message = "User must not be blank")
  private String user;

  private String key;

  @Min(value = 1, message = "Port must be >= 1")
  @Max(value = 65535, message = "Port must be <= 65535")
  private int port;

  @NotBlank(message = "Host must not be blank")
  private String host;

  private Connect connect;

  @NotNull(message = "Server list must not be null")
  @Size(min = 1, message = "Server list must not be empty")
  @Valid
  private Map<String, Server> servers;

  private Plan plan;

  private static final Logger log = LoggerFactory.getLogger(Mnemon.class);

  // A static factory: it builds objects, holds no instance state.

  public static List<Mnemon> loadMnemones(Config config) throws IOException {
    String path = config.getServersPath();
    log.debug("Loading servers from {}", path);

    LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setAllowDuplicateKeys(false);
    ObjectMapper mapper =
        new ObjectMapper(YAMLFactory.builder().loaderOptions(loaderOptions).build());

    File file = new File(path);
    List<Mnemon> mnemones =
        mapper.readValue(
            file, mapper.getTypeFactory().constructCollectionType(List.class, Mnemon.class));
    log.debug("Loaded {} servers", mnemones.size());

    for (Mnemon m : mnemones) {
      m.getServers()
          .forEach(
              (key, s) -> {
                s.setId(key);
                if (s.getName() == null || s.getName().isBlank()) s.setName(key);
              });
    }
    return mnemones;
  }

  // Connection lifecycle

  public void setConnect() throws Exception {
    File keyFile = new File(this.key);
    log.debug("Resolving SSH key for group '{}': '{}'", this.group, this.key);
    if (!keyFile.exists() || !keyFile.isFile()) {
      throw new IOException(
          String.format("SSH key file not found '%s' for mnemon '%s'", this.key, this.group));
    }
    String uri =
        String.format("qemu+ssh://%s@%s:%d/system?keyfile=%s&no_verify=1", user, host, port, key);
    log.debug("Connecting to hypervisor '{}' via '{}'", this.group, uri);

    Connect connect = new Connect(uri);
    // disable native C logging
    connect.setConnectionErrorCallback(
        new ErrorCallback() {
          public void errorCallback(Object userData, virError error) {}
        });

    this.connect = connect;
    log.info("Connection to '{}' was successful.", this.group);
    log.debug("Mnemon '{}' connected to host '{}'", this.group, this.host);
  }

  public void closeConnect() throws LibvirtException {
    if (connect == null) {
      log.debug("Mnemon '{}' has no active connection, nothing to close", this.group);
      return;
    }
    log.debug("Closing connection for mnemon '{}'...", this.group);
    this.connect.close();
    log.debug("Mnemon '{}' go to bed", this.group);
  }

  public void plan(boolean isJoin)
      throws LibvirtException, ParserConfigurationException, SAXException, IOException {

    List<DomainState> actual = new ArrayList<>();

    for (Domain d : connect.listAllDomains(0))
      actual.add(DomainInspector.readState(d.getXMLDesc(0)));

    plan = new Plan(actual, servers);
    plan.print(group, isJoin);
  }

  public void apply() throws Exception {
    update();
    reconcile();
    createStorage();
    setupDomain();
  }

  public void update() throws LibvirtException {
    for (String name : plan.getToUpdate()) {
      Domain d = null;
      Server s = null;
      s = servers.get(name);
      try {
        d = connect.domainLookupByName(name);
        if (s.getCpu() != d.getMaxVcpus()) {
          d.setVcpusFlags(
              s.getCpu(),
              Domain.VcpuFlags.CONFIG | Domain.VcpuFlags.MAXIMUM);
        }
      } catch (Exception e) {
        log.error("Domain '{}' will be skipping..", name, e);
        continue;
      } finally {
        if (d != null) freeDomainQuietly(d);
      }
    }
  }

  // Storage provisioning

  public void createStorage() throws Exception {
    for (Server s : plan.getToCreate().values()) {
      provisionVolume(s);
    }
  }

  private void provisionVolume(Server s) {
    StoragePool pool = lookupPool(s);
    if (pool == null) {
      return;
    }
    if (attachExistingVolume(pool, s)) {
      return;
    }
    // reached only when the volume does not exist yet (status == NEW)
    cloneVolume(pool, s);
  }

  private StoragePool lookupPool(Server s) {
    try {
      log.debug("Fetch a storage pool '{}' for server '{}'", s.getPool(), s.getName());
      StoragePool pool = connect.storagePoolLookupByName(s.getPool());
      pool.refresh(0);
      log.debug("Storage pool '{}' ready for server '{}'", s.getPool(), s.getName());
      return pool;
    } catch (LibvirtException e) {
      s.setStatus(Status.ERROR);
      log.error(
          "Storage pool '{}' not found in group '{}' for server '{}', skipping",
          s.getPool(),
          this.group,
          s.getName());
      return null;
    }
  }

  /**
   * @return true if processing for this server is done (attached or errored).
   */
  private boolean attachExistingVolume(StoragePool pool, Server s) {
    try {
      log.debug("Fetch a storage volume '{}' in storage pool '{}'", s.getName(), s.getPool());
      StorageVol vol = pool.storageVolLookupByName(s.getName());
      s.setVolPath(vol.getPath());
      s.setStatus(Status.VOLUME_ATTACHED);
      log.debug(
          "Volume '{}' found and attached in pool '{}' (path: {})",
          s.getName(),
          s.getName(),
          s.getVolPath());
      return true;
    } catch (LibvirtException e) {
      if (e.getError().getCode() == Error.ErrorNumber.VIR_ERR_NO_STORAGE_VOL) {
        s.setStatus(Status.NEW);
        log.debug(
            "Volume '{}' doesn't exist. Will be created a new one in pool '{}'...",
            s.getName(),
            s.getPool());
        return false;
      }
      s.setStatus(Status.ERROR);
      log.error(
          "Failed to lookup volume '{}' in pool '{}': {}",
          s.getName(),
          s.getPool(),
          e.getMessage());
      return true;
    }
  }

  private void cloneVolume(StoragePool pool, Server s) {
    long targetCapacity = (long) s.getDisk() * 1024 * 1024 * 1024;
    try {
      String volDesc = s.buildVolumeXml();
      log.trace("Volume XML for '{}' (pool '{}'):\n{}", s.getName(), s.getPool(), volDesc);

      log.debug("Looking up clone source '{}' in pool '{}'", s.getVolLookup(), s.getPool());
      StorageVol cloneVol = pool.storageVolLookupByName(s.getVolLookup());
      log.debug("Cloning '{}' -> '{}' in pool '{}'", s.getVolLookup(), s.getName(), s.getPool());
      StorageVol newVol = pool.storageVolCreateXMLFrom(volDesc, cloneVol, 0);

      if (!resizeVolume(newVol, s, targetCapacity)) {
        return;
      }

      s.setVolPath(newVol.getPath());
      s.setStatus(Status.VOLUME_ATTACHED);
      log.debug(
          "Volume '{}' created and attached in pool '{}' (path: {})",
          s.getName(),
          s.getPool(),
          s.getVolPath());
    } catch (LibvirtException e) {
      s.setStatus(Status.ERROR);
      log.error(
          "Libvirt operation failed for server '{}' in pool '{}': {}",
          s.getName(),
          s.getPool(),
          e.getMessage(),
          e);
    } catch (Exception e) {
      s.setStatus(Status.ERROR);
      log.error("Failed to build volume XML for server '{}'", s.getName(), e);
    }
  }

  /**
   * @return true on success; on failure sets ERROR, rolls back, and returns false.
   */
  private boolean resizeVolume(StorageVol vol, Server s, long targetCapacity) {
    try {
      log.debug("Resizing volume '{}' to {} bytes", s.getName(), targetCapacity);
      vol.resize(targetCapacity, 0);
      return true;
    } catch (LibvirtException e) {
      s.setStatus(Status.ERROR);
      log.error(
          "Failed to resize volume '{}' to {} bytes, rolling back", s.getName(), targetCapacity, e);
      rollbackVolume(vol, s);
      return false;
    }
  }

  private void rollbackVolume(StorageVol vol, Server s) {
    try {
      log.debug("Rolling back: deleting volume '{}'", s.getName());
      vol.delete(0);
      log.debug("Rollback successful: volume '{}' deleted", s.getName());
    } catch (LibvirtException e) {
      log.error(
          "Rollback failed: could not delete volume '{}': {}", s.getName(), e.getMessage(), e);
    }
  }

  // Domain definition

  public void setupDomain() throws Exception {

    for (Server s : plan.getToCreate().values()) {
      if (s.getStatus() == Status.ERROR) {
        log.debug("Skipping setup server '{}': status is ERROR", s.getName());
        continue;
      }
      enrichServerStatus(s);
      if (s.getStatus() == Status.NEW) {
        try {
          log.debug("Trying to generate cloud-configs for server '{}'", s.getName());
          setupCloudInit(s);
          log.debug("Cloud-configs successfully generated for server '{}'", s.getName());

          log.debug("Trying to define domain for server '{}'", s.getName());
          defineDomain(s);
          log.debug("Domain successfully defined for server '{}'", s.getName());

          log.debug("Trying to create domain for server '{}'", s.getName());
          createDomain(s);
          log.debug("Domain successfully created for server '{}'", s.getName());
        } catch (Exception e) {
          log.error("Failed to setup server '{}': {}", s.getName(), e.getMessage());
          continue;
        }
      }
    }
  }

  private void enrichServerStatus(Server s) {
    Domain domain = null;
    try {
      domain = connect.domainLookupByName(s.getName());
      s.setDomain(domain);
      s.setStatus(Status.DOMAIN_ASSIGNED);
      log.debug("Domain '{}' already exists, assigning to server", s.getName());
    } catch (LibvirtException e) {
      Error virError = e.getError();
      if (virError.getCode() == Error.ErrorNumber.VIR_ERR_NO_DOMAIN) {
        s.setStatus(Status.NEW);
        log.debug("Domain '{}' not found, will be created a new one", s.getName());
      } else {
        s.setStatus(Status.ERROR);
        log.error(
            "Libvirt operation failed for server '{}', status set to ERROR (code: {})",
            s.getName(),
            virError.getCode(),
            e);
      }
    }
  }

  private void setupCloudInit(Server s) throws Exception {
    try {
      CloudInitServer.setNetworkConfig(s.getName(), s.buildNetworkConfigYaml());
      log.trace("NetworkConfig for server '{}'\n: {}", s.getName(), s.buildNetworkConfigYaml());
      CloudInitServer.setUserData(s.getName(), s.buildUserDataYaml());
      log.trace("UserData for server '{}'\n: {}", s.getName(), s.buildUserDataYaml());
    } catch (Exception e) {
      s.setStatus(Status.ERROR);
      log.error("Failed to generate cloud-configs for server '{}'", s.getName(), e);
      throw e;
    }
  }

  private void defineDomain(Server s) throws Exception {
    Domain domain = null;
    try {
      String serverDesc = s.buildServerXml();
      log.trace("Domain XML for server '{}': {}", s.getName(), serverDesc);
      domain = connect.domainDefineXML(serverDesc);
      s.setDomain(domain);
      s.setStatus(Status.DOMAIN_ASSIGNED);
    } catch (LibvirtException e) {
      log.error(
          "Libvirt operation failed when define for server '{}' (code: {})",
          s.getName(),
          e.getError() != null ? e.getError().getCode() : "unknown",
          e);
      throw e;
    } catch (Exception e) {
      log.error("Failed to generate domain XML for server '{}'\n", s.getName(), e);
      throw e;
    }
  }

  private void createDomain(Server s) throws Exception {
    try {
      s.getDomain().create();
      log.debug("Creating domain '{}'...", s.getName());

      int attempts = 0;
      while (s.getDomain().isActive() != 1) {
        log.debug(
            "Domain '{}' is not active yet, waiting... (attempt #{})", s.getName(), ++attempts);
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          log.warn(
              "Thread interrupted while waiting for domain '{}' to become active", s.getName());
          Thread.currentThread().interrupt();
          throw e;
        }
      }

      log.info("Domain '{}' has been started successfully.", s.getName());
      s.setStatus(Status.VM_CREATED);
      log.debug("Domain '{}' status set to {}", s.getName(), Status.VM_CREATED);
    } catch (LibvirtException e) {
      s.setStatus(Status.ERROR);
      log.error("Failed to create domain '{}': {}", s.getName(), e.getMessage(), e);
      throw e;
    }
  }

  public void reconcile() throws LibvirtException {
    for (String name : plan.getToDelete()) {
      Domain d = null;
      try {
        d = connect.domainLookupByName(name);
        List<StorageVol> volumes = resolveVolumes(d, name);
        destroyDomain(d, name);
        undefineDomain(d, name);
        // deleteVolumes(volumes, name);
      } catch (Exception e) {
        log.error("Domain '{}' will be skipping..", name, e);
        continue;
      } finally {
        if (d != null) freeDomainQuietly(d);
      }
    }
  }

  private List<StorageVol> resolveVolumes(Domain domain, String domainName) throws Exception {
    if (domain == null) {
      log.error("Cannot resolve volumes: domain handle is null for '{}'", domainName);
      return List.of();
    }
    final List<String> diskPaths;
    try {
      String domainXml = domain.getXMLDesc(0);
      diskPaths = DomainInspector.diskPaths(domainXml);
    } catch (LibvirtException e) {
      log.error("Failed to read XML of domain '{}', skipping volume cleanup", domainName, e);
      throw e;
    } catch (Exception e) {
      log.error("Failed to parse XML of domain '{}', skipping volume cleanup", domainName, e);
      throw e;
    }

    if (diskPaths.isEmpty()) {
      log.debug("Domain '{}' has no file-backed disk paths", domainName);
      return List.of();
    }

    List<StorageVol> volumes = new ArrayList<>(diskPaths.size());
    for (String path : diskPaths) {
      try {
        volumes.add(connect.storageVolLookupByPath(path));
      } catch (LibvirtException e) {
        log.error(
            "Could not resolve volume for path '{}' (domain '{}'); skipping", path, domainName, e);
        throw e;
      }
    }
    return volumes;
  }

  private void destroyDomain(Domain d, String name) throws LibvirtException {
    try {
      if (d.isActive() == 1) {
        log.debug("Domain '{}' is active, destroying...", name);
        d.destroy();
        log.debug("Domain '{}' destroyed successfully", name);
      }
    } catch (LibvirtException e) {
      log.error("Failed to destroy domain '{}'", name, e);
      throw e;
    }
  }

  private void undefineDomain(Domain d, String name) throws LibvirtException {
    log.debug("Undefining domain '{}'...", name);
    try {
      d.undefine();
      log.debug("Domain '{}' undefined successfully", name);
    } catch (LibvirtException e) {
      log.error("Failed to undefine domain '{}'", name, e);
      throw e;
    }
  }

  private void deleteVolumes(List<StorageVol> volumes, String name) {
    if (volumes == null || volumes.isEmpty()) {
      log.debug("No volumes to delete for domain '{}'", name);
      return;
    }

    int deleted = 0;
    int failed = 0;
    for (StorageVol vol : volumes) {
      if (vol == null) {
        continue;
      }
      try {
        vol.delete(0);
        deleted++;
        log.debug("Deleted a volume of domain '{}'", name);
      } catch (LibvirtException e) {
        failed++;
        log.error("Failed to delete a volume of domain '{}'; continuing with the rest", name, e);
      } finally {
        freeVolumeQuietly(vol);
      }
    }

    if (failed > 0) {
      log.warn("Volume cleanup for domain '{}': {} deleted, {} failed", name, deleted, failed);
    } else {
      log.debug("Volume cleanup for domain '{}': {} deleted", name, deleted);
    }
  }

  private void freeVolumeQuietly(StorageVol vol) {
    try {
      vol.free();
    } catch (LibvirtException e) {
      log.debug("Failed to free StorageVol handle (domain cleanup); ignoring", e);
    }
  }

  private void freeDomainQuietly(Domain d) {
    try {
      d.free();
    } catch (LibvirtException e) {
      log.debug("Failed to free domain handle (domain cleanup); ignoring", e);
    }
  }

  public void freeDomains() {
    for (Server s : servers.values()) {
      if (s.getDomain() == null) continue;
      log.debug("Attempting to free domain '{}'...", s.getName());
      try {
        log.debug("Freeing domain '{}'...", s.getName());
        s.getDomain().free();
      } catch (LibvirtException e) {
        s.setStatus(Status.ERROR);
        log.error("Failed to free domain '{}': {}", s.getName(), e.getMessage(), e);
        continue;
      }
    }
  }

  // refactor
  // private Server serverByName(String name) {
  //   for (Server s : servers) {
  //     if (s.getName().equals(name)) return s;
  //   }
  //   return null;
  // }

  public void join() {
    if (plan == null) {
      System.out.printf("[ %s ]  nothing to join (no plan)%n", group);
      return;
    }

    List<String> joined = new ArrayList<>();
    List<String> skipped = new ArrayList<>();

    for (String name : plan.getUnmanaged()) {
      Server match =
          servers.values().stream()
              .filter(s -> name.equals(s.getName()))
              .findFirst()
              .orElse(null); // serverByName(name);

      if (match == null) {
        skipped.add(name); // нет matching server spec
        continue;
      }
      if (joinDomain(name, match)) joined.add(name);
      else skipped.add(name);
    }

    if (joined.isEmpty()) {
      System.out.printf("[ %s ]  nothing joined (skipped: %d)%n", group, skipped.size());
    } else {
      System.out.printf("[ %s ]  joined: %d (skipped: %d)%n", group, joined.size(), skipped.size());
    }
    joined.forEach(n -> System.out.println("  + " + n));
    skipped.forEach(n -> System.out.println("  · " + n));
  }

  private boolean joinDomain(String name, Server s) {
    Domain d = null;
    try {
      d = connect.domainLookupByName(name);

      int flags =
          (d.isActive() == 1)
              ? Domain.ModificationImpact.CONFIG | Domain.ModificationImpact.LIVE
              : Domain.ModificationImpact.CONFIG;

      d.setMetadata(
          Domain.MetadataType.ELEMENT,
          s.buildMnemosyneMetadataXml(),
          "mnem",
          DomainInspector.MNEM_NS,
          flags);

      return true;
    } catch (LibvirtException e) {
      log.error("Failed to join domain '{}' in group '{}': {}", name, group, e.getMessage(), e);
      return false;
    } finally {
      if (d != null) freeDomainQuietly(d);
    }
  }

  // End class
}
