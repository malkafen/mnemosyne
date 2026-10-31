package com.mnemosyne.app.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only snapshot of one libvirt network as the host reports it.
 *
 * <p>{@code shape} is the part the inventory describes, read from the persistent config; {@code
 * active} and {@code autostart} come from the network handle.
 */
public record NetState(
    String name, String uuid, boolean managed, boolean active, boolean autostart, Shape shape) {

  /**
   * What a network is made of, in one comparable form for both sides: mode ({@code nat}, {@code
   * route}, {@code isolated}, {@code bridge}, or whatever else libvirt reports), host address as
   * {@code a.b.c.d/n}, bridge name, DHCP range as {@code start-end}.
   */
  public record Shape(String mode, String address, String bridge, String dhcp) {

    /**
     * What differs between the host ({@code this}) and the inventory, as plan text; empty when
     * nothing does. A bridge name the inventory leaves out is libvirt's to choose, so it is never
     * drift.
     */
    public List<String> diff(Shape wanted) {
      List<String> d = new ArrayList<>();
      if (!Objects.equals(mode, wanted.mode)) d.add("mode " + mode + "->" + wanted.mode);
      if (!Objects.equals(address, wanted.address))
        d.add("address " + dash(address) + "->" + dash(wanted.address));
      if (wanted.bridge != null && !Objects.equals(bridge, wanted.bridge))
        d.add("bridge " + dash(bridge) + "->" + wanted.bridge);
      if (!Objects.equals(dhcp, wanted.dhcp))
        d.add("dhcp " + dash(dhcp) + "->" + dash(wanted.dhcp));
      return d;
    }

    private static String dash(String s) {
      return s == null ? "none" : s;
    }
  }
}
