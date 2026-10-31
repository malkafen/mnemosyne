package com.mnemosyne.app.model;

import java.util.Objects;

public record GuestNetwork(String ip, String gateway) {

  static final GuestNetwork ASSUMED_CURRENT = new GuestNetwork(null, null);

  public boolean assumed() {
    return this == ASSUMED_CURRENT;
  }

  public boolean differsFrom(Server server) {
    if (assumed()) return false;
    return !Objects.equals(ip, server.getIp()) || !Objects.equals(gateway, server.getGateway());
  }

  public String toXml() {
    if (assumed() || ip == null) return "";
    StringBuilder sb = new StringBuilder("<network");
    sb.append(" ip='").append(ip).append('\'');
    if (gateway != null) sb.append(" gateway='").append(gateway).append('\'');
    return sb.append("/>").toString();
  }
}
