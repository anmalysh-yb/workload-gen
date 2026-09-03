package com.amalyshev.workload;

/** One entry in a workload chain: which workload to run, and with what parameters. */
public record WorkloadStep(String workload, WorkloadParams params) {

  @Override
  public String toString() {
    return workload + " for " + Durations.format(params.getDuration())
        + " [threads=" + params.getNumThreads()
        + ", thinkTime=" + params.getThinkTimeMillis() + "ms"
        + ", cpuAmplification=" + params.getCpuAmplification()
        + ", ddl=" + params.isRunDDl()
        + ", cleanup=" + params.isDoCleanup()
        + ", load=" + params.isLoadData() + "]";
  }
}
