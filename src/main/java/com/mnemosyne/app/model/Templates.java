package com.mnemosyne.app.model;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Templates {

  @NotBlank(message = "serverTmpl must not be blank")
  private String serverTmpl;

  @NotBlank(message = "volTmpl must not be blank")
  private String volTmpl;

  @NotBlank(message = "userDataTmpl must not be blank")
  private String userDataTmpl;

  @NotBlank(message = "networkConfigTmpl must not be blank")
  private String networkConfigTmpl;

  public static Templates defaults() {
    Templates t = new Templates();
    t.serverTmpl = "/app/templates/server.xml";
    t.volTmpl = "/app/templates/volume.xml";
    t.userDataTmpl = "/app/templates/user-data.yml";
    t.networkConfigTmpl = "/app/templates/network-config.yml";
    return t;
  }

  Templates resolveOver(Templates group) {
    Templates t = new Templates();
    t.serverTmpl = serverTmpl != null ? serverTmpl : group.serverTmpl;
    t.volTmpl = volTmpl != null ? volTmpl : group.volTmpl;
    t.userDataTmpl = userDataTmpl != null ? userDataTmpl : group.userDataTmpl;
    t.networkConfigTmpl = networkConfigTmpl != null ? networkConfigTmpl : group.networkConfigTmpl;
    return t;
  }
}
