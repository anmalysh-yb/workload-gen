package com.amalyshev.workload;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.Collections;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CollectionLimitationWorkload extends AbstractWorkload {

  private static final int NUM_TABLES = 100;
  private static final int ROWS_PER_TABLE = 10000;
  private static final int BATCH_SIZE = 1000;

  /**
   * Sum of weights 1+2+...+NUM_TABLES. Used for weighted random table selection
   * so that tableX receives X times more queries than table1.
   */
  private static final int WEIGHT_SUM = NUM_TABLES * (NUM_TABLES + 1) / 2;

  public CollectionLimitationWorkload(NamedParameterJdbcTemplate defaultTemplate) {
    super(defaultTemplate);
  }

  @Override
  public void runDdl(boolean clean) {
    for (int t = 1; t <= NUM_TABLES; t++) {
      String table = "cl1_table" + t;
      if (clean) {
        defaultTemplate.update("DROP TABLE IF EXISTS " + table, Collections.emptyMap());
      }
      defaultTemplate.update(
          "CREATE TABLE IF NOT EXISTS " + table
              + "(id bigint PRIMARY KEY, val1 text, val2 bigint)",
          Collections.emptyMap());
      defaultTemplate.update(
          "CREATE INDEX IF NOT EXISTS idx_" + table + "_val2 ON " + table + "(val2)",
          Collections.emptyMap());
      if (t % 10 == 0) {
        log.info("Created {}/{} tables with indexes", t, NUM_TABLES);
      }
    }
    log.info("DDL complete: {} tables and {} indexes created", NUM_TABLES, NUM_TABLES);
  }

  @Override
  public void loadData() {
    for (int t = 1; t <= NUM_TABLES; t++) {
      String table = "cl1_table" + t;
      int existing = defaultTemplate.queryForObject(
          "SELECT COUNT(*) FROM " + table, Collections.emptyMap(), Integer.class);
      if (existing >= ROWS_PER_TABLE) {
        log.info("Table {} already has {} rows, skipping load", table, existing);
        continue;
      }
      for (int batch = existing; batch < ROWS_PER_TABLE; batch += BATCH_SIZE) {
        int end = Math.min(batch + BATCH_SIZE, ROWS_PER_TABLE);
        StringBuilder sb = new StringBuilder("INSERT INTO " + table + "(id, val1, val2) VALUES ");
        for (int i = batch; i < end; i++) {
          if (i > batch) {
            sb.append(",");
          }
          sb.append("(").append(i).append(",'row_").append(i).append("',").append(i % 1000).append(")");
        }
        sb.append(" ON CONFLICT DO NOTHING");
        defaultTemplate.update(sb.toString(), Collections.emptyMap());
      }
      if (t % 10 == 0) {
        log.info("Loaded data into {}/{} tables", t, NUM_TABLES);
      }
    }
    log.info("Data load complete: {} rows per table", ROWS_PER_TABLE);
  }

  @Override
  void workloadThread(WorkLoadStats stats) {
    Random rnd = new Random();
    Instant lastErrorLog = null;
    while (stats.running.get()) {
      try {
        int tableNum = pickWeightedTable(rnd);
        String table = "cl1_table" + tableNum;

        int id = rnd.nextInt(ROWS_PER_TABLE);
        String query = "SELECT id, val1, val2 FROM " + table + " WHERE id = :id";
        defaultTemplate.queryForList(query, ImmutableMap.of("id", id));

        int val2 = rnd.nextInt(1000);
        query = "SELECT id, val1, val2 FROM " + table + " WHERE val2 = :val2 LIMIT 10";
        defaultTemplate.queryForList(query, ImmutableMap.of("val2", val2));

        stats.incSucceeded();
      } catch (Exception e) {
        if (lastErrorLog == null || lastErrorLog.plusSeconds(10).isBefore(Instant.now())) {
          log.warn("Failed to execute statement", e);
          lastErrorLog = Instant.now();
        }
        stats.incFailed();
      }
    }
  }

  /**
   * Picks a table number in [1, NUM_TABLES] where table N is N times more likely
   * to be chosen than table 1. Table N owns the range [N*(N-1)/2, N*(N+1)/2).
   */
  private int pickWeightedTable(Random rnd) {
    int r = rnd.nextInt(WEIGHT_SUM);
    // Cumulative weight up to table N is N*(N+1)/2.
    // We need the smallest N such that N*(N+1)/2 > r.
    int n = (int) Math.floor((-1.0 + Math.sqrt(1.0 + 8.0 * r)) / 2.0) + 1;
    return Math.max(1, Math.min(n, NUM_TABLES));
  }
}
