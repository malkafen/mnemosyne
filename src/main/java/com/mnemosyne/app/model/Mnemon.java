package com.mnemosyne.app.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mnemosyne.app.config.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.File;
import java.io.IOException;
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
  private String user;

  private String key;

  @Min(value = 1, message = "Port must be >= 1")
  @Max(value = 65535, message = "Port must be <= 65535")
  private int port;

  @NotBlank(message = "Host must not be blank")
  private String host;

  @jakarta.validation.Valid @com.fasterxml.jackson.annotation.JsonMerge
  private Templates templates = Templates.defaults();

  @NotBlank(message = "volLookup must not be blank")
  private String volLookup = "noble-server-cloudimg-amd64.img";

  @NotBlank(message = "Cloud-init metadata base URL is required")
  @Pattern(regexp = "^https?://.+", message = "metaUrl must be a valid HTTP/HTTPS URL")
  private String metaUrl = "http://127.0.0.1:80/files/";

  @NotNull(message = "Server list must not be null")
  @Size(min = 1, message = "Server list must not be empty")
  @Valid
  private Map<String, Server> servers;

  private static final Logger log = LoggerFactory.getLogger(Mnemon.class);

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
      Templates groupTmpl = m.getTemplates();
      m.getServers()
          .forEach(
              (key, s) -> {
                s.setId(key);
                Templates override = s.getTemplates();
                s.setTemplates(override == null ? groupTmpl : override.resolveOver(groupTmpl));

                if (s.getVolLookup() == null) s.setVolLookup(m.getVolLookup());
                if (s.getMetaUrl() == null) s.setMetaUrl(m.getMetaUrl());
              });
    }
    return mnemones;
  }
}
