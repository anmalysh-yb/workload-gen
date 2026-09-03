package com.amalyshev.workload;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and renders the short duration strings the CLI accepts, so a chain can be written as
 * {@code cpu-heavy:20m,connection-churn:30m} instead of in ISO-8601.
 */
public final class Durations {

  /** One {@code <number><unit>} component. {@code ms} must precede {@code m} in the alternation. */
  private static final Pattern COMPONENT = Pattern.compile("(\\d+)(ms|s|m|h|d)?");

  private Durations() {
  }

  /**
   * Accepts compound short forms ({@code 90s}, {@code 20m}, {@code 1h30m}, {@code 500ms}), a bare
   * number meaning seconds, and ISO-8601 ({@code PT20M}).
   */
  public static Duration parse(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("Duration must not be null");
    }
    String text = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
    if (text.isEmpty()) {
      throw invalid(raw);
    }
    if (text.startsWith("p")) {
      return Duration.parse(text.toUpperCase(Locale.ROOT));
    }
    Matcher matcher = COMPONENT.matcher(text);
    Duration total = Duration.ZERO;
    int cursor = 0;
    while (cursor < text.length()) {
      // find() would happily skip over garbage, so require the match to start where the
      // previous component ended. That rejects "20mx" instead of silently reading "20m".
      if (!matcher.find(cursor) || matcher.start() != cursor) {
        throw invalid(raw);
      }
      total = total.plus(component(Long.parseLong(matcher.group(1)), matcher.group(2)));
      cursor = matcher.end();
    }
    if (total.isNegative()) {
      throw invalid(raw);
    }
    return total;
  }

  private static Duration component(long value, String unit) {
    if (unit == null) {
      return Duration.ofSeconds(value);
    }
    return switch (unit) {
      case "ms" -> Duration.ofMillis(value);
      case "s" -> Duration.ofSeconds(value);
      case "m" -> Duration.ofMinutes(value);
      case "h" -> Duration.ofHours(value);
      case "d" -> Duration.ofDays(value);
      default -> throw new IllegalStateException("Unhandled unit " + unit);
    };
  }

  /** Renders a duration the way the CLI accepts it, so log lines can be pasted back as input. */
  public static String format(Duration duration) {
    if (duration == null) {
      return "unset";
    }
    long seconds = duration.getSeconds();
    if (seconds == 0) {
      return duration.toMillis() + "ms";
    }
    StringBuilder sb = new StringBuilder();
    long hours = seconds / 3600;
    long minutes = (seconds % 3600) / 60;
    long rest = seconds % 60;
    if (hours > 0) {
      sb.append(hours).append('h');
    }
    if (minutes > 0) {
      sb.append(minutes).append('m');
    }
    if (rest > 0 || sb.isEmpty()) {
      sb.append(rest).append('s');
    }
    return sb.toString();
  }

  private static IllegalArgumentException invalid(String raw) {
    return new IllegalArgumentException("Invalid duration '" + raw
        + "'. Expected forms like 30s, 20m, 2h, 1h30m, 500ms, a bare number of seconds, or PT20M");
  }
}
