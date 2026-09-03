package com.amalyshev.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkloadStepParserTest {

  private static WorkloadParams defaults() {
    return new WorkloadParams();
  }

  @Test
  void parsesNameOnlyAndInheritsDefaults() {
    WorkloadStep step = WorkloadStepParser.parse("cpu-heavy", defaults());
    assertThat(step.workload()).isEqualTo("cpu-heavy");
    assertThat(step.params().getDuration()).isEqualTo(WorkloadParams.DEFAULT_DURATION);
    assertThat(step.params().getNumThreads()).isEqualTo(WorkloadParams.DEFAULT_NUM_THREADS);
  }

  @Test
  void parsesDurationAndOptions() {
    WorkloadStep step =
        WorkloadStepParser.parse("cpu-heavy:20m:threads=8,amp=80,think-time=0", defaults());
    assertThat(step.params().getDuration()).isEqualTo(Duration.ofMinutes(20));
    assertThat(step.params().getNumThreads()).isEqualTo(8);
    assertThat(step.params().getCpuAmplification()).isEqualTo(80);
    assertThat(step.params().getThinkTimeMillis()).isZero();
  }

  @Test
  void parsesBooleanOptionsInEveryAcceptedForm() {
    WorkloadParams params =
        WorkloadStepParser.parse("cpu-heavy:1m:cleanup,no-load,ddl=false", defaults()).params();
    assertThat(params.isDoCleanup()).isTrue();
    assertThat(params.isLoadData()).isFalse();
    assertThat(params.isRunDDl()).isFalse();
  }

  @Test
  void leavesTheSharedDefaultsUntouched() {
    WorkloadParams shared = defaults();
    WorkloadStepParser.parse("cpu-heavy:1m:threads=64", shared);
    assertThat(shared.getNumThreads()).isEqualTo(WorkloadParams.DEFAULT_NUM_THREADS);
  }

  @Test
  void splitsChainsOnStepBoundariesNotOnOptionCommas() {
    assertThat(WorkloadStepParser.splitChain("cpu-heavy:20m,connection-churn:30m"))
        .containsExactly("cpu-heavy:20m", "connection-churn:30m");
    assertThat(WorkloadStepParser.splitChain("cpu-heavy,connection-churn"))
        .containsExactly("cpu-heavy", "connection-churn");
    assertThat(WorkloadStepParser.splitChain("cpu-heavy:1m:threads=4,cleanup,connection-churn:2m"))
        .containsExactly("cpu-heavy:1m:threads=4,cleanup", "connection-churn:2m");
  }

  @Test
  void reportsBadInputWithAnActionableMessage() {
    assertThatThrownBy(() -> WorkloadStepParser.parse("cpu-heavy:20m:threadz=4", defaults()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown step option 'threadz'");
    assertThatThrownBy(() -> WorkloadStepParser.parse("cpu-heavy:20m:threads=0", defaults()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
    assertThatThrownBy(() -> WorkloadStepParser.parse("cpu-heavy:20m:threads", defaults()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a value");
    // A workload name where an option is expected is the one genuinely ambiguous chain form,
    // so the message has to point at the fix.
    assertThatThrownBy(
        () -> WorkloadStepParser.parse("cpu-heavy:1m:threads=4,connection-churn", defaults()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connection-churn:10m");
  }

  @Test
  void parsesAllSpecsAgainstTheSameDefaults() {
    WorkloadParams shared = defaults();
    shared.setNumThreads(7);
    List<WorkloadStep> steps =
        WorkloadStepParser.parseAll(List.of("cpu-heavy:1m", "connection-churn:2m:threads=3"), shared);
    assertThat(steps).extracting(step -> step.params().getNumThreads()).containsExactly(7, 3);
  }
}
