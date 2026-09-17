package com.mnemosyne.app.model;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Templates {

  @NotBlank(message = "serverTmpl must not be blank")
  private String serverTmpl;

  @NotBlank(message = "volTmpl must not be blank")
  private String volTmpl;

  @NotBlank(message = "metaDataTmpl must not be blank")
  private String metaDataTmpl;

  @NotBlank(message = "userDataTmpl must not be blank")
  private String userDataTmpl;

  @NotBlank(message = "networkConfigTmpl must not be blank")
  private String networkConfigTmpl;

  /** Every template file a server is rendered from, in the order the builders read them. */
  public List<String> paths() {
    return Stream.of(serverTmpl, volTmpl, metaDataTmpl, userDataTmpl, networkConfigTmpl)
        .filter(Objects::nonNull)
        .toList();
  }

  public static Templates defaults() {
    Templates t = new Templates();
    t.serverTmpl = "/app/templates/server.xml";
    t.volTmpl = "/app/templates/volume.xml";
    t.metaDataTmpl = "/app/templates/meta-data.yml";
    t.userDataTmpl = "/app/templates/user-data.yml";
    t.networkConfigTmpl = "/app/templates/network-config.yml";
    return t;
  }

  Templates resolveOver(Templates group) {
    Templates t = new Templates();
    t.serverTmpl = serverTmpl != null ? serverTmpl : group.serverTmpl;
    t.volTmpl = volTmpl != null ? volTmpl : group.volTmpl;
    t.metaDataTmpl = metaDataTmpl != null ? metaDataTmpl : group.metaDataTmpl;
    t.userDataTmpl = userDataTmpl != null ? userDataTmpl : group.userDataTmpl;
    t.networkConfigTmpl = networkConfigTmpl != null ? networkConfigTmpl : group.networkConfigTmpl;
    return t;
  }
}
