package com.mnemosyne.app.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class Report {

  private static final String SKIP_MARKER = "·";
  private static final int RULE_WIDTH = 60;

  private final Map<String, Integer> counts = new LinkedHashMap<>();
  private final List<String> lines = new ArrayList<>();
  private int skipped;

  public static void heading(String phase) {
    String rule = "--- " + phase + " ";
    System.out.printf("%n%s%s%n", rule, "-".repeat(Math.max(3, RULE_WIDTH - rule.length())));
  }

  public void add(String verb, String marker, String name, String detail) {
    counts.merge(verb, 1, Integer::sum);
    lines.add(line(marker, name, detail));
  }

  public void skip(String name, String reason) {
    skipped++;
    lines.add(line(SKIP_MARKER, name, reason));
  }

  public void print(String group) {
    print(group, null);
  }

  public void print(String group, String emptyMessage) {
    if (lines.isEmpty()) {
      if (emptyMessage != null) printBlock(group, emptyMessage);
      return;
    }
    StringJoiner head = new StringJoiner(", ");
    counts.forEach((verb, n) -> head.add(verb + ": " + n));
    if (skipped > 0) head.add("skipped: " + skipped);

    printBlock(group, head.toString());
  }

  private void printBlock(String group, String summary) {
    System.out.printf("[ %s ]  %s%n", group, summary);
    lines.forEach(System.out::println);
    System.out.println();
  }

  private static String line(String marker, String name, String detail) {
    return detail == null || detail.isEmpty()
        ? String.format("  %s %s", marker, name)
        : String.format("  %s %s  (%s)", marker, name, detail);
  }
}
