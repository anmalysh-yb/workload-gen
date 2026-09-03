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
public class MultiTableReadWriteWorkload extends AbstractWorkload {

  private static final int NUM_TABLES = 500;
  private static final int ROWS_PER_TABLE = 100_000;
  private static final int BATCH_SIZE = 1000;

  public MultiTableReadWriteWorkload(NamedParameterJdbcTemplate defaultTemplate) {
    super(defaultTemplate);
  }

  private String tableName(int idx) {
    return "multi_rw_table_" + idx;
  }

  @Override
  public void runDdl(boolean clean) {
    for (int t = 1; t <= NUM_TABLES; t++) {
      String table = tableName(t);
      if (clean) {
        defaultTemplate.update("DROP TABLE IF EXISTS " + table, Collections.emptyMap());
      }
      defaultTemplate.update(
          "CREATE TABLE IF NOT EXISTS " + table
              + "(id bigint PRIMARY KEY, category int, payload text)",
          Collections.emptyMap());
      defaultTemplate.update(
          "CREATE INDEX IF NOT EXISTS idx_" + table + "_category ON " + table + "(category)",
          Collections.emptyMap());
      if (t % 5 == 0) {
        log.info("Created {}/{} tables", t, NUM_TABLES);
      }
    }
    log.info("DDL complete: {} tables created", NUM_TABLES);
  }

  @Override
  public void loadData() {
    for (int t = 1; t <= NUM_TABLES; t++) {
      String table = tableName(t);
      int existing = defaultTemplate.queryForObject(
          "SELECT COUNT(*) FROM " + table, Collections.emptyMap(), Integer.class);
      if (existing >= ROWS_PER_TABLE) {
        log.info("Table {} already has {} rows, skipping", table, existing);
        continue;
      }
      for (int batch = existing; batch < ROWS_PER_TABLE; batch += BATCH_SIZE) {
        int end = Math.min(batch + BATCH_SIZE, ROWS_PER_TABLE);
        StringBuilder sb = new StringBuilder(
            "INSERT INTO " + table + "(id, category, payload) VALUES ");
        for (int i = batch; i < end; i++) {
          if (i > batch) {
            sb.append(",");
          }
          sb.append("(").append(i).append(",").append(i % 200)
              .append(",'payload_").append(i).append("')");
        }
        sb.append(" ON CONFLICT DO NOTHING");
        defaultTemplate.update(sb.toString(), Collections.emptyMap());
      }
      log.info("Loaded data into table {} ({} rows)", table, ROWS_PER_TABLE);
    }
    log.info("Data load complete for all {} tables", NUM_TABLES);
  }

  @Override
  void workloadThread(WorkLoadStats stats) {
    Random rnd = new Random();
    Instant lastErrorLog = null;
    while (stats.running.get()) {
      try {
        String table = tableName(rnd.nextInt(NUM_TABLES) + 1);
        boolean doWrite = rnd.nextInt(100) < 30;

        if (doWrite) {
          int id = rnd.nextInt(ROWS_PER_TABLE);
          int category = rnd.nextInt(200);
          defaultTemplate.update(
              "INSERT INTO " + table + "(id, category, payload) VALUES (:id, :cat, :payload)"
                  + " ON CONFLICT (id) DO UPDATE SET category = :cat, payload = :payload",
              ImmutableMap.of("id", id, "cat", category, "payload", "updated_" + id));
        } else {
          if (rnd.nextBoolean()) {
            int id = rnd.nextInt(ROWS_PER_TABLE);
            defaultTemplate.queryForList(
                "SELECT id, category, payload FROM " + table + " WHERE id = :id",
                ImmutableMap.of("id", id));
          } else {
            int category = rnd.nextInt(200);
            defaultTemplate.queryForList(
                "SELECT id, category, payload FROM " + table
                    + " WHERE category = :cat ORDER BY id LIMIT 20",
                ImmutableMap.of("cat", category));
          }
        }
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
}
