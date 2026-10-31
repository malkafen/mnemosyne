package com.mnemosyne.app.utils;

/**
 * Plain IPv4 arithmetic for the network and firewall sections of the inventory: parsing an address
 * or a CIDR block, and the containment and overlap questions validation and preflight ask.
 *
 * <p>Nothing here resolves names or touches the network; every input is a literal.
 */
public final class Ipv4 {

  public static final String ADDRESS_PATTERN =
      "((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)";

  /** An address with an optional {@code /prefix}: what a firewall rule's {@code remote} accepts. */
  public static final String ADDRESS_OR_CIDR_PATTERN =
      "^" + ADDRESS_PATTERN + "(/(3[0-2]|[12]?\\d))?$";

  /** An address with a mandatory {@code /prefix}. */
  public static final String CIDR_PATTERN = "^" + ADDRESS_PATTERN + "/(3[0-2]|[12]?\\d)$";

  private Ipv4() {}

  /** One CIDR block; {@code address} is kept as written, so {@code 10.0.0.1/24} keeps its host. */
  public record Cidr(long address, int prefix) {

    public long mask() {
      return prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    public long network() {
      return address & mask();
    }

    public long broadcast() {
      return network() | (~mask() & 0xFFFFFFFFL);
    }

    public boolean contains(long ip) {
      return (ip & mask()) == network();
    }

    public boolean overlaps(Cidr other) {
      return network() <= other.broadcast() && other.network() <= broadcast();
    }

    /** The address alone, dotted. */
    public String host() {
      return format(address);
    }

    /** The block's network address, dotted. */
    public String networkAddress() {
      return format(network());
    }

    @Override
    public String toString() {
      return format(address) + "/" + prefix;
    }
  }

  /** {@code a.b.c.d} or {@code a.b.c.d/n}; a bare address is a /32. */
  public static Cidr cidr(String value) {
    if (value == null) throw new IllegalArgumentException("no address");
    int slash = value.indexOf('/');
    String addr = slash < 0 ? value : value.substring(0, slash);
    int prefix = slash < 0 ? 32 : Integer.parseInt(value.substring(slash + 1).trim());
    if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("bad prefix: " + value);
    return new Cidr(parse(addr.trim()), prefix);
  }

  public static long parse(String address) {
    String[] parts = address.split("\\.");
    if (parts.length != 4) throw new IllegalArgumentException("not an IPv4 address: " + address);
    long v = 0;
    for (String p : parts) {
      int octet = Integer.parseInt(p);
      if (octet < 0 || octet > 255)
        throw new IllegalArgumentException("not an IPv4 address: " + address);
      v = (v << 8) | octet;
    }
    return v;
  }

  public static boolean isAddress(String value) {
    return value != null && value.matches("^" + ADDRESS_PATTERN + "$");
  }

  public static String format(long ip) {
    return ((ip >> 24) & 0xFF)
        + "."
        + ((ip >> 16) & 0xFF)
        + "."
        + ((ip >> 8) & 0xFF)
        + "."
        + (ip & 0xFF);
  }

  /** The prefix length of a dotted netmask such as {@code 255.255.255.0}; -1 if it is not one. */
  public static int prefixOf(String netmask) {
    long m = parse(netmask);
    int prefix = Long.bitCount(m);
    long expected = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    return m == expected ? prefix : -1;
  }
}
