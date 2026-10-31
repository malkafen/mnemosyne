package com.mnemosyne.app.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class TargetDev {

  private TargetDev() {}

  private static final String DEFAULT_PREFIX = "vd";

  private static final List<String> KNOWN_PREFIXES = List.of("xvd", "ubd", "vd", "sd", "hd", "fd");

  public static String prefix(String dev) {
    if (dev == null || dev.isBlank()) return DEFAULT_PREFIX;
    String d = dev.trim();
    for (String p : KNOWN_PREFIXES) {
      if (d.length() > p.length() && d.startsWith(p)) return p;
    }
    return d.length() > 1 ? d.substring(0, d.length() - 1) : DEFAULT_PREFIX;
  }

  public static List<String> allocate(String prefix, Set<String> used, int count) {
    List<String> free = new ArrayList<>(count);
    for (char c = 'a'; c <= 'z' && free.size() < count; c++) {
      String dev = prefix + c;
      if (!used.contains(dev)) free.add(dev);
    }
    return free;
  }
}
