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

  @Valid private Map<String, Server> servers = new LinkedHashMap<>();

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
    mapper
        .coercionConfigFor(LogicalType.Map)
        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsEmpty);

    File file = new File(path);
    List<Mnemon> mnemones =
        mapper.readValue(
            file, mapper.getTypeFactory().constructCollectionType(List.class, Mnemon.class));

    if (mnemones == null || mnemones.isEmpty()) {
      throw new IllegalArgumentException("Inventory is empty or defines no groups: " + path);
    }

    log.debug("Loaded {} groups", mnemones.size());

    for (Mnemon m : mnemones) {
      if (m.getKey() == null || m.getKey().isBlank()) {
        m.setKey(config.getKey());
        log.debug("Group '{}' uses the default SSH key {}", m.getGroup(), config.getKey());
      }

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

                s.getExtraDisks().stream()
                    .filter(d -> d.getPool() == null || d.getPool().isBlank())
                    .forEach(d -> d.setPool(s.getPool()));
              });
    }
    return mnemones;
  }
}
