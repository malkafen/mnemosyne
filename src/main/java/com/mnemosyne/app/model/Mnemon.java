package com.mnemosyne.app.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mnemosyne.app.config.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;

@Getter
@Setter
public class Mnemon {
  @NotBlank(message = "Group name must not be blank")
  private String group;

  // user and host end up in the libvirt URI as they are: a '?', '&' or '/' in either would add
  // URI parameters of its own (command= runs a program locally), so both are held to their syntax.
  @NotBlank(message = "User must not be blank")
  @Pattern(
      regexp = "^[A-Za-z_][A-Za-z0-9_.-]{0,31}$",
      message =
          "User must be a user name: letters, digits, '_', '.' and '-', not starting with a digit,"
              + " '.' or '-', 32 characters at most")
  private String user;

  @NotBlank(message = "SSH key path must not be blank: set `key` in the group, --key or MNEM_KEY")
  private String key;

  @Min(value = 1, message = "Port must be >= 1")
  @Max(value = 65535, message = "Port must be <= 65535")
  private int port;

  @NotBlank(message = "Host must not be blank")
  @Pattern(regexp = Server.HOSTNAME_PATTERN, message = "Host " + Server.HOSTNAME_MESSAGE)
  private String host;

  @jakarta.validation.Valid @com.fasterxml.jackson.annotation.JsonMerge
  private Templates templates = Templates.defaults();

  @NotBlank(message = "volLookup must not be blank")
  private String volLookup = "noble-server-cloudimg-amd64.img";

  @NotBlank(message = "Cloud-init metadata base URL is required")
  @Pattern(regexp = "^https?://.+", message = "metaUrl must be a valid HTTP/HTTPS URL")
  private String metaUrl = "http://127.0.0.1:80/files/";

  /**
   * The VMs this hypervisor must hold, keyed by server id.
   *
   * <p>An absent, empty or null {@code servers} block is a group of no VMs, not a mistake: it
   * describes the connection and says the host must hold nothing of Mnemosyne's, so an existing
   * group can be emptied down to zero VMs instead of being deleted from the inventory — which would
   * leave its VMs behind, unmanaged and unmentioned. The deletions it implies are in the plan like
   * any other, so nothing is removed that was not printed first.
   */
  @Valid private Map<String, Server> servers = new LinkedHashMap<>();

  /** Kept non-null whatever the YAML says, because every reader iterates it unguarded. */
  public void setServers(Map<String, Server> servers) {
    this.servers = servers == null ? new LinkedHashMap<>() : servers;
  }

  private static final Logger log = LoggerFactory.getLogger(Mnemon.class);

  public static List<Mnemon> loadMnemones(Config config) throws IOException {
    String path = config.getServersPath();
    log.debug("Loading servers from {}", path);

    LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setAllowDuplicateKeys(false);
    ObjectMapper mapper =
        new ObjectMapper(YAMLFactory.builder().loaderOptions(loaderOptions).build());
    // `servers:` with nothing under it is an empty scalar, which Jackson otherwise refuses rather
    // than reads as the empty map it means. A group of no VMs has to be writable that way: the
    // alternative is `servers: {}`, which nobody types by hand.
    mapper
        .coercionConfigFor(LogicalType.Map)
        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsEmpty);

    File file = new File(path);
    List<Mnemon> mnemones =
        mapper.readValue(
            file, mapper.getTypeFactory().constructCollectionType(List.class, Mnemon.class));

    // An empty document deserializes to null, not to an empty list.
    if (mnemones == null || mnemones.isEmpty()) {
      throw new IllegalArgumentException("Inventory is empty or defines no groups: " + path);
    }

    log.debug("Loaded {} groups", mnemones.size());

    for (Mnemon m : mnemones) {
      if (m.getKey() == null || m.getKey().isBlank()) {
        m.setKey(config.getKey());
        log.debug("Group '{}' uses the default SSH key {}", m.getGroup(), config.getKey());
      }

      // A group may legitimately hold no VMs; it still connects, so that whatever Mnemosyne
      // manages on that host is deleted.
      if (m.getServers().isEmpty()) {
        log.info(
            "Group '{}' has no servers: all managed VMs on {} will be deleted",
            m.getGroup(),
            m.getHost());
        continue;
      }

      Templates groupTmpl = m.getTemplates();
      m.getServers()
          .forEach(
              (key, s) -> {
                s.setId(key);
                Templates override = s.getTemplates();
                s.setTemplates(override == null ? groupTmpl : override.resolveOver(groupTmpl));

                if (s.getVolLookup() == null) s.setVolLookup(m.getVolLookup());
                if (s.getMetaUrl() == null) s.setMetaUrl(m.getMetaUrl());

                // An extra disk without a pool of its own lives where the VM's root disk lives.
                // Filled in here rather than read lazily, so validation and the plan see one value.
                s.getExtraDisks().stream()
                    .filter(d -> d.getPool() == null || d.getPool().isBlank())
                    .forEach(d -> d.setPool(s.getPool()));
              });
    }
    return mnemones;
  }
}
