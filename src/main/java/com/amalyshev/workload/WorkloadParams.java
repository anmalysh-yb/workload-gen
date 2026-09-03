package com.amalyshev.workload;

import java.time.Duration;
import lombok.Data;

/**
 * Tunables for a single workload run. The defaults here are what the CLI starts from, so a run
 * only has to name the workload and the values it actually wants to change.
 */
@Data
public class WorkloadParams {

  public static final boolean DEFAULT_DO_CLEANUP = false;
  public static final boolean DEFAULT_RUN_DDL = true;
  public static final boolean DEFAULT_LOAD_DATA = true;
  public static final int DEFAULT_NUM_THREADS = 15;
  public static final Duration DEFAULT_DURATION = Duration.ofMinutes(10);
  public static final int DEFAULT_CPU_AMPLIFICATION = 40;
  public static final long DEFAULT_THINK_TIME_MILLIS = 50;

  /** Drops the workload's tables before recreating them. Off by default because it destroys data. */
  public boolean doCleanup = DEFAULT_DO_CLEANUP;

  public boolean runDDl = DEFAULT_RUN_DDL;
  public boolean loadData = DEFAULT_LOAD_DATA;
  public int numThreads = DEFAULT_NUM_THREADS;
  public Duration duration = DEFAULT_DURATION;

  /**
   * Multiplier on the amount of computation a CPU-heavy query performs per row it reads.
   * Raise it to burn more CPU per query, lower it if per-query latency starts approaching
   * the statement timeout.
   */
  public int cpuAmplification = DEFAULT_CPU_AMPLIFICATION;

  /** Pause between statements in a workload thread. Zero keeps every thread continuously busy. */
  public long thinkTimeMillis = DEFAULT_THINK_TIME_MILLIS;

  /**
   * Returns an independent copy. A chain derives every step from shared CLI defaults, so each step
   * needs its own instance before applying its per-step overrides.
   */
  public WorkloadParams copy() {
    WorkloadParams copy = new WorkloadParams();
    copy.doCleanup = doCleanup;
    copy.runDDl = runDDl;
    copy.loadData = loadData;
    copy.numThreads = numThreads;
    copy.duration = duration;
    copy.cpuAmplification = cpuAmplification;
    copy.thinkTimeMillis = thinkTimeMillis;
    return copy;
  }
}
