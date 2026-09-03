package com.amalyshev.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationsTest {

  @Test
  void parsesShortForms() {
    assertThat(Durations.parse("30s")).isEqualTo(Duration.ofSeconds(30));
    assertThat(Durations.parse("20m")).isEqualTo(Duration.ofMinutes(20));
    assertThat(Durations.parse("2h")).isEqualTo(Duration.ofHours(2));
    assertThat(Durations.parse("500ms")).isEqualTo(Duration.ofMillis(500));
    assertThat(Durations.parse("1d")).isEqualTo(Duration.ofDays(1));
  }

  @Test
  void parsesCompoundAndBareForms() {
    assertThat(Durations.parse("1h30m")).isEqualTo(Duration.ofMinutes(90));
    assertThat(Durations.parse("1h 30m 15s")).isEqualTo(Duration.ofSeconds(5415));
    assertThat(Durations.parse("90")).isEqualTo(Duration.ofSeconds(90));
    assertThat(Durations.parse("PT20M")).isEqualTo(Duration.ofMinutes(20));
  }

  @Test
  void rejectsTrailingGarbageInsteadOfSilentlyIgnoringIt() {
    assertThatThrownBy(() -> Durations.parse("20mx"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid duration '20mx'");
    assertThatThrownBy(() -> Durations.parse("x20m")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Durations.parse("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void formatsBackIntoAcceptedInput() {
    assertThat(Durations.format(Duration.ofMinutes(20))).isEqualTo("20m");
    assertThat(Durations.format(Duration.ofMinutes(90))).isEqualTo("1h30m");
    assertThat(Durations.format(Duration.ofSeconds(45))).isEqualTo("45s");
    assertThat(Durations.parse(Durations.format(Duration.ofSeconds(5415))))
        .isEqualTo(Duration.ofSeconds(5415));
  }
}
