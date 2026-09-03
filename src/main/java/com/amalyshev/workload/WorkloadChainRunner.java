package com.amalyshev.workload;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a list of {@link WorkloadStep}s one after another, so a single invocation can reproduce a
 * multi-phase scenario such as CPU pressure followed by connection churn.
 */
@Component
@Slf4j
public class WorkloadChainRunner {

  private final WorkloadRegistry registry;

  public WorkloadChainRunner(WorkloadRegistry registry) {
    this.registry = registry;
  }

  /** Returns a process exit code: 0 when every step completed, 1 when any step failed. */
  public int run(List<WorkloadStep> steps, boolean continueOnError) {
    List<Workload> workloads = registry.requireAll(steps);
    logPlan(steps);
    Instant chainStart = Instant.now();
    List<String> failed = new ArrayList<>();
    for (int i = 0; i < steps.size(); i++) {
      WorkloadStep step = steps.get(i);
      log.info("=== Step {}/{}: {} ===", i + 1, steps.size(), step);
      Instant stepStart = Instant.now();
      try {
        workloads.get(i).run(step.params());
        log.info("=== Step {}/{} ({}) finished in {} ===", i + 1, steps.size(), step.workload(),
            Durations.format(Duration.between(stepStart, Instant.now())));
      } catch (Exception e) {
        failed.add((i + 1) + ":" + step.workload());
        log.error("=== Step {}/{} ({}) failed after {} ===", i + 1, steps.size(), step.workload(),
            Durations.format(Duration.between(stepStart, Instant.now())), e);
        if (!continueOnError) {
          log.error("Stopping the chain; pass --continue-on-error to run the remaining {} step(s)",
              steps.size() - i - 1);
          break;
        }
      }
    }
    Duration elapsed = Duration.between(chainStart, Instant.now());
    if (failed.isEmpty()) {
      log.info("Chain complete: {} step(s) in {}", steps.size(), Durations.format(elapsed));
      return 0;
    }
    log.error("Chain finished with {} failed step(s) [{}] after {}", failed.size(),
        String.join(", ", failed), Durations.format(elapsed));
    return 1;
  }

  private void logPlan(List<WorkloadStep> steps) {
    StringBuilder plan = new StringBuilder("Workload chain: ")
        .append(steps.size()).append(" step(s), ")
        .append(Durations.format(WorkloadCli.totalDuration(steps)))
        .append(" of workload time (DDL and data loading are additional)");
    for (int i = 0; i < steps.size(); i++) {
      plan.append(System.lineSeparator()).append("  ").append(i + 1).append(". ").append(steps.get(i));
    }
    log.info(plan.toString());
  }
}
