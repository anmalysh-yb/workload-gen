package com.amalyshev.workload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the chain step syntax {@code <workload>[:<duration>[:<opt>[,<opt>...]]]}, for example
 * {@code cpu-heavy:20m}, {@code connection-churn:30m:threads=64}, or
 * {@code cpu-heavy:20m:no-load,cleanup}.
 *
 * <p>Each step starts from the CLI-wide defaults and applies only the options it names, so a chain
 * stays short while still allowing per-step overrides such as loading data once and skipping the
 * load on later steps that reuse the same table.
 */
public final class WorkloadStepParser {

  /** Boolean option values accepted in addition to the bare-flag and {@code no-} prefix forms. */
  private static final Set<String> TRUE_VALUES = Set.of("true", "yes", "y", "on", "1");
  private static final Set<String> FALSE_VALUES = Set.of("false", "no", "n", "off", "0");

  private static final String OPTION_KEYS =
      "threads, duration, cpu-amplification (amp), think-time, ddl, cleanup, load";

  private WorkloadStepParser() {
  }

  /** Parses one step spec on top of {@code defaults}, which is left untouched. */
  public static WorkloadStep parse(String spec, WorkloadParams defaults) {
    if (spec == null || spec.isBlank()) {
      throw new IllegalArgumentException("Empty workload step");
    }
    // Limit 3: the option list itself is comma-separated, and values may contain nothing else,
    // so only the first two colons are structural.
    String[] parts = spec.trim().split(":", 3);
    String workload = parts[0].trim();
    if (workload.isEmpty()) {
      throw new IllegalArgumentException("Workload step '" + spec + "' does not name a workload");
    }
    WorkloadParams params = defaults.copy();
    if (parts.length > 1 && !parts[1].isBlank()) {
      params.setDuration(Durations.parse(parts[1]));
    }
    if (parts.length > 2) {
      for (String option : parts[2].split(",")) {
        if (!option.isBlank()) {
          applyOption(params, option.trim(), spec);
        }
      }
    }
    return new WorkloadStep(workload, params);
  }

  /**
   * Splits a {@code --chain} value into step specs. Commas separate steps, but they also separate
   * a step's own options, so the two are told apart structurally: once a step has opened its
   * option list (its second colon), a following fragment continues that list unless it contains a
   * colon of its own, which only a new {@code workload:duration} step does.
   *
   * <p>So {@code a:20m,b:30m}, {@code a,b} and {@code a:1m:threads=4,cleanup,b:2m} all split the
   * way they read.
   */
  public static List<String> splitChain(String chain) {
    List<String> specs = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String fragment : chain.split(",")) {
      String trimmed = fragment.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      boolean hasOptionList = current.chars().filter(c -> c == ':').count() >= 2;
      if (!hasOptionList || trimmed.indexOf(':') >= 0) {
        if (current.length() > 0) {
          specs.add(current.toString());
          current.setLength(0);
        }
        current.append(trimmed);
      } else {
        current.append(',').append(trimmed);
      }
    }
    if (current.length() > 0) {
      specs.add(current.toString());
    }
    if (specs.isEmpty()) {
      throw new IllegalArgumentException("--chain '" + chain + "' contains no workload steps");
    }
    return specs;
  }

  /** Parses each of {@code specs} against the same defaults, preserving order. */
  public static List<WorkloadStep> parseAll(List<String> specs, WorkloadParams defaults) {
    List<WorkloadStep> steps = new ArrayList<>(specs.size());
    for (String spec : specs) {
      steps.add(parse(spec, defaults));
    }
    return steps;
  }

  /**
   * Reads a chain from a file with one step spec per line. Blank lines and {@code #} comments are
   * ignored, so a long chain can be kept in version control next to the run that used it.
   */
  public static List<String> readChainFile(Path file) {
    List<String> specs = new ArrayList<>();
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String stripped = line.indexOf('#') >= 0 ? line.substring(0, line.indexOf('#')) : line;
        if (!stripped.isBlank()) {
          specs.add(stripped.trim());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read chain file " + file, e);
    }
    if (specs.isEmpty()) {
      throw new IllegalArgumentException("Chain file " + file + " contains no workload steps");
    }
    return specs;
  }

  private static void applyOption(WorkloadParams params, String option, String spec) {
    int eq = option.indexOf('=');
    String key = (eq < 0 ? option : option.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
    String value = eq < 0 ? null : option.substring(eq + 1).trim();
    // A key with no value is a boolean flag: "cleanup" enables it, "no-load" disables.
    boolean flagValue = true;
    if (eq < 0 && key.startsWith("no-")) {
      key = key.substring(3);
      flagValue = false;
    }
    switch (key) {
      case "threads", "n", "num-threads" -> params.setNumThreads(positiveInt(key, requireValue(key, value, spec)));
      case "duration", "t" -> params.setDuration(Durations.parse(requireValue(key, value, spec)));
      case "amp", "cpu-amplification", "cpu-amp" ->
          params.setCpuAmplification(positiveInt(key, requireValue(key, value, spec)));
      case "think-time", "think-time-ms", "thinktime" ->
          params.setThinkTimeMillis(nonNegativeLong(key, requireValue(key, value, spec)));
      case "ddl", "run-ddl" -> params.setRunDDl(bool(key, value, flagValue));
      case "cleanup", "do-cleanup" -> params.setDoCleanup(bool(key, value, flagValue));
      case "load", "load-data" -> params.setLoadData(bool(key, value, flagValue));
      default -> throw unknownOption(key, spec);
    }
  }

  private static IllegalArgumentException unknownOption(String key, String spec) {
    String message = "Unknown step option '" + key + "' in '" + spec
        + "'. Supported options: " + OPTION_KEYS;
    if (WorkloadRegistry.discoverNames().contains(key)) {
      message += ". To start a new chain step here, give it a duration, e.g. '" + key + ":10m'";
    }
    return new IllegalArgumentException(message);
  }

  private static String requireValue(String key, String value, String spec) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(
          "Step option '" + key + "' in '" + spec + "' needs a value, e.g. " + key + "=8");
    }
    return value;
  }

  private static boolean bool(String key, String value, boolean flagValue) {
    if (value == null || value.isEmpty()) {
      return flagValue;
    }
    String normalized = value.toLowerCase(Locale.ROOT);
    if (TRUE_VALUES.contains(normalized)) {
      return true;
    }
    if (FALSE_VALUES.contains(normalized)) {
      return false;
    }
    throw new IllegalArgumentException(
        "Step option '" + key + "' expects a boolean, got '" + value + "'");
  }

  private static int positiveInt(String key, String value) {
    int parsed = (int) parseLong(key, value);
    if (parsed <= 0) {
      throw new IllegalArgumentException("Step option '" + key + "' must be positive, got " + value);
    }
    return parsed;
  }

  private static long nonNegativeLong(String key, String value) {
    long parsed = parseLong(key, value);
    if (parsed < 0) {
      throw new IllegalArgumentException(
          "Step option '" + key + "' must not be negative, got " + value);
    }
    return parsed;
  }

  private static long parseLong(String key, String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Step option '" + key + "' expects a number, got '" + value + "'");
    }
  }
}
