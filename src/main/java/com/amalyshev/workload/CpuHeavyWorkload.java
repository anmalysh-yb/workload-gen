package com.amalyshev.workload;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.Collections;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Generates high server-side CPU load with a small number of queries.
 *
 * <p>Every query reads a small, bounded slice of the table and then amplifies the amount
 * of computation performed per row — chained hashing, arbitrary-precision numeric math,
 * regex rewriting, top-N sorting, a bounded self cross-join. The cost therefore lands on
 * backend CPU instead of on distributed reads, and it stays proportional to the slice
 * rather than to the table size, so every query completes in a predictable amount of time.
 *
 * <p>Per-query cost is tuned with {@link WorkloadParams#getCpuAmplification()}, which
 * scales the work without changing the shape of any query.
 */
@Component
@Slf4j
public class CpuHeavyWorkload extends AbstractWorkload {

  private static final int TABLE_SIZE = 500_000;

  /**
   * Rows inserted per statement. Rows are generated server-side, so this no longer trades
   * off against wire volume — it only bounds transaction size and per-statement runtime,
   * and a larger batch means fewer round trips over a high-latency link.
   */
  private static final int BATCH_SIZE = 20_000;

  /** Rows read per amplified query. Kept small so reads never dominate the cost. */
  private static final int SLICE = 400;

  /** Rows read per side of the self cross-join, which costs JOIN_SLICE^2 pairs to aggregate. */
  private static final int JOIN_SLICE = 700;

  private static final int PAYLOAD_LENGTH = 200;

  public CpuHeavyWorkload(NamedParameterJdbcTemplate defaultTemplate) {
    super(defaultTemplate);
  }

  @Override
  public void runDdl(boolean clean) {
    if (clean) {
      defaultTemplate.update("DROP TABLE IF EXISTS cpu_heavy", Collections.emptyMap());
      log.info("Dropped table cpu_heavy");
    }
    // The primary key must be range-partitioned: a HASH key cannot serve the bounded
    // "id >= :lo AND id < :hi" predicate every query below relies on, and each slice would
    // silently degrade into a full sequential scan with a storage filter.
    defaultTemplate.update(
        "CREATE TABLE IF NOT EXISTS cpu_heavy("
            + "id bigint, val bigint, label text, payload text, "
            + "PRIMARY KEY (id ASC)) "
            + "SPLIT AT VALUES ((100000), (200000), (300000), (400000))",
        Collections.emptyMap());
    log.info("Created table cpu_heavy");
    warnIfHashPartitioned();
  }

  /**
   * Guards against reusing a pre-existing hash-partitioned cpu_heavy table, which
   * {@code CREATE TABLE IF NOT EXISTS} would silently keep and which would turn every
   * bounded slice into a full table scan.
   */
  private void warnIfHashPartitioned() {
    String indexDef = defaultTemplate.queryForObject(
        "SELECT indexdef FROM pg_indexes WHERE tablename = 'cpu_heavy' "
            + "AND indexname = 'cpu_heavy_pkey'",
        Collections.emptyMap(), String.class);
    if (indexDef != null && indexDef.contains("HASH")) {
      log.warn("cpu_heavy primary key is hash-partitioned ({}). Bounded slices will degrade "
          + "into full table scans. Re-run the DDL with cleanup enabled to recreate it.", indexDef);
    }
  }

  @Override
  public void loadData() {
    int existing = defaultTemplate.queryForObject(
        "SELECT COUNT(*) FROM cpu_heavy", Collections.emptyMap(), Integer.class);
    if (existing >= TABLE_SIZE) {
      log.info("cpu_heavy already has {} rows, skipping load", existing);
      return;
    }
    // Rows are generated server-side rather than shipped as a literal VALUES list. A
    // literal list costs ~228 bytes per row on the wire, which entirely dominates the load
    // over a high-latency, low-bandwidth link; generating them here sends a few hundred
    // bytes per batch instead.
    for (int batch = existing; batch < TABLE_SIZE; batch += BATCH_SIZE) {
      int end = Math.min(batch + BATCH_SIZE, TABLE_SIZE);
      defaultTemplate.update(
          "INSERT INTO cpu_heavy(id, val, label, payload) "
              + "SELECT g, g % 10000, 'label_' || (g % 500), "
              + "substr(repeat(md5(random()::text), 7), 1, :payloadLength) "
              + "FROM generate_series(:lo, :hi) g "
              + "ON CONFLICT DO NOTHING",
          ImmutableMap.of("lo", batch, "hi", end - 1, "payloadLength", PAYLOAD_LENGTH));
      log.info("Loaded {}/{} rows into cpu_heavy", end, TABLE_SIZE);
    }
    log.info("Data load complete: {} rows in cpu_heavy", TABLE_SIZE);
  }

  @Override
  void workloadThread(WorkLoadStats stats) {
    Random rnd = new Random();
    int amp = params.getCpuAmplification();
    long thinkTimeMillis = params.getThinkTimeMillis();
    Instant lastErrorLog = null;
    while (stats.running.get()) {
      try {
        switch (rnd.nextInt(5)) {
          case 0 -> timed(stats, "hashChain", () -> hashChain(rnd, amp));
          case 1 -> timed(stats, "topNSort", () -> topNSort(rnd, amp));
          case 2 -> timed(stats, "numericMath", () -> numericMath(rnd, amp));
          case 3 -> timed(stats, "regexRewrite", () -> regexRewrite(rnd, amp));
          case 4 -> timed(stats, "crossJoinAggregate", () -> crossJoinAggregate(rnd));
        }
        stats.incSucceeded();
        if (thinkTimeMillis > 0) {
          Thread.sleep(thinkTimeMillis);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        if (lastErrorLog == null || lastErrorLog.plusSeconds(10).isBefore(Instant.now())) {
          log.warn("Failed to execute statement", e);
          lastErrorLog = Instant.now();
        }
        stats.incFailed();
      }
    }
  }

  /** Runs one statement and records its latency, so per-kind cost is visible in the logs. */
  private void timed(WorkLoadStats stats, String kind, Runnable query) {
    long startNanos = System.nanoTime();
    query.run();
    stats.recordLatency(kind, (System.nanoTime() - startNanos) / 1_000_000);
  }

  /**
   * Five chained md5 calls over an amplified slice: SLICE * amp * 5 hashes of a 200-byte
   * string, from only SLICE rows read.
   */
  private void hashChain(Random rnd, int amp) {
    int lo = rnd.nextInt(TABLE_SIZE - SLICE);
    defaultTemplate.queryForList(
        "SELECT count(*) AS rows_hashed, max(h) AS max_hash FROM ("
            + "SELECT md5(md5(md5(md5(md5(c.payload || g::text))))) AS h "
            + "FROM cpu_heavy c CROSS JOIN generate_series(1, :amp) g "
            + "WHERE c.id >= :lo AND c.id < :hi) t",
        ImmutableMap.of("lo", lo, "hi", lo + SLICE, "amp", amp));
  }

  /**
   * Sorts SLICE * amp hashed rows. The LIMIT keeps this a top-N heapsort, so it burns
   * comparison CPU without spilling to disk.
   */
  private void topNSort(Random rnd, int amp) {
    int lo = rnd.nextInt(TABLE_SIZE - SLICE);
    defaultTemplate.queryForList(
        "SELECT h FROM ("
            + "SELECT md5(c.payload || g::text) AS h "
            + "FROM cpu_heavy c CROSS JOIN generate_series(1, :amp) g "
            + "WHERE c.id >= :lo AND c.id < :hi) t "
            + "ORDER BY h DESC LIMIT 20",
        ImmutableMap.of("lo", lo, "hi", lo + SLICE, "amp", amp));
  }

  /**
   * Arbitrary-precision numeric transcendentals. sqrt, ln and exp on numeric are
   * software-implemented and among the most CPU-expensive expressions available.
   */
  private void numericMath(Random rnd, int amp) {
    int lo = rnd.nextInt(TABLE_SIZE - SLICE);
    defaultTemplate.queryForList(
        "SELECT sum(sqrt(v) * ln(v) + exp(round(v / 100000, 6))) AS total FROM ("
            + "SELECT (c.val + g + 1)::numeric AS v "
            + "FROM cpu_heavy c CROSS JOIN generate_series(1, :amp) g "
            + "WHERE c.id >= :lo AND c.id < :hi) t",
        ImmutableMap.of("lo", lo, "hi", lo + SLICE, "amp", amp));
  }

  /**
   * Rewrites every adjacent character pair in the payload. Cost is linear in the string
   * length, so it is heavy but has none of the backtracking blow-up of a nested-quantifier
   * pattern.
   */
  private void regexRewrite(Random rnd, int amp) {
    int lo = rnd.nextInt(TABLE_SIZE - SLICE);
    defaultTemplate.queryForList(
        "SELECT count(*) AS rows_rewritten, max(length(r)) AS max_len FROM ("
            + "SELECT regexp_replace(c.payload || g::text, '(.)(.)', '\\2\\1', 'g') AS r "
            + "FROM cpu_heavy c CROSS JOIN generate_series(1, :amp) g "
            + "WHERE c.id >= :lo AND c.id < :hi) t",
        ImmutableMap.of("lo", lo, "hi", lo + SLICE, "amp", amp));
  }

  /**
   * Self cross-join over two explicitly small slices: JOIN_SLICE^2 pairs aggregated from
   * only 2 * JOIN_SLICE rows read.
   */
  private void crossJoinAggregate(Random rnd) {
    int aLo = rnd.nextInt(TABLE_SIZE - JOIN_SLICE);
    int bLo = rnd.nextInt(TABLE_SIZE - JOIN_SLICE);
    defaultTemplate.queryForList(
        "SELECT count(*) AS pairs, sum(a.val * b.val) AS weighted FROM "
            + "(SELECT val FROM cpu_heavy WHERE id >= :aLo AND id < :aHi) a "
            + "CROSS JOIN "
            + "(SELECT val FROM cpu_heavy WHERE id >= :bLo AND id < :bHi) b",
        ImmutableMap.of("aLo", aLo, "aHi", aLo + JOIN_SLICE,
            "bLo", bLo, "bHi", bLo + JOIN_SLICE));
  }
}
