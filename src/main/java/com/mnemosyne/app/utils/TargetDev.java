package com.mnemosyne.app.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Guest-visible disk target names — {@code vda}, {@code vdb}, … — for the disks Mnemosyne adds.
 *
 * <p>Mnemosyne assigns them itself instead of leaving them to libvirt, for one reason: a name
 * already taken by a disk somebody attached by hand must never be handed out a second time. libvirt
 * would reject such a domain outright, which is the good case; the bad case is a run that looks
 * like it worked and a guest whose data disk is not the disk the operator meant.
 *
 * <p>The letter a disk gets still depends on the order of {@code extraDisks} in the inventory, so
 * it is not a stable identity. That is what the {@code <serial>} Mnemosyne writes is for: {@code
 * /dev/disk/by-id/virtio-<name>} names the disk the inventory names, whatever letter the kernel
 * gives it.
 */
public final class TargetDev {

  private TargetDev() {}

  /** The bus prefix used when nothing in the template says otherwise. */
  private static final String DEFAULT_PREFIX = "vd";

  /** Longest first, so {@code xvda} is not read as an {@code sd}/{@code vd} name. */
  private static final List<String> KNOWN_PREFIXES = List.of("xvd", "ubd", "vd", "sd", "hd", "fd");

  /**
   * The bus prefix of a target name: {@code vda} → {@code vd}, {@code xvdb} → {@code xvd}. An
   * unrecognised name is read as "everything but the last letter", which is how every target name
   * libvirt accepts is built.
   */
  public static String prefix(String dev) {
    if (dev == null || dev.isBlank()) return DEFAULT_PREFIX;
    String d = dev.trim();
    for (String p : KNOWN_PREFIXES) {
      if (d.length() > p.length() && d.startsWith(p)) return p;
    }
    return d.length() > 1 ? d.substring(0, d.length() - 1) : DEFAULT_PREFIX;
  }

  /**
   * The next {@code count} free names after {@code prefix}, skipping everything in {@code used}.
   *
   * <p>Returns fewer than {@code count} when the letters run out rather than throwing: the caller
   * reports the disks it could not place and attaches the rest. Nothing is lost — the volume is
   * created only for a disk that got a name.
   */
  public static List<String> allocate(String prefix, Set<String> used, int count) {
    List<String> free = new ArrayList<>(count);
    for (char c = 'a'; c <= 'z' && free.size() < count; c++) {
      String dev = prefix + c;
      if (!used.contains(dev)) free.add(dev);
    }
    return free;
  }
}
