package com.amalyshev.workload;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class BasicReadWriteWorkload extends AbstractWorkload {

  private static final int TABLE_SIZE = 1000000;

  public BasicReadWriteWorkload(NamedParameterJdbcTemplate defaultTemplate) {
    super(defaultTemplate);
  }

  @Override
  public void runDdl(boolean clean) {
    if (clean) {
      defaultTemplate.update("DROP TABLE IF EXISTS test_table_basic_rw", Collections.emptyMap());
      log.info("Dropped table test_table_basic_rw");
    }
    defaultTemplate.update("CREATE TABLE IF NOT EXISTS test_table_basic_rw(id bigint PRIMARY KEY, id2 bigint, data text)", Collections.emptyMap());
    log.info("Created table test_table_basic_rw");
  }

  @Override
  public void loadData() {
  }

  @Override
  void workloadThread(WorkLoadStats stats) {
    Random rnd = new Random();
    Instant lastErrorLog = null;
    while (stats.running.get()) {
      try {
        int id = rnd.nextInt(TABLE_SIZE);
        int id2 = rnd.nextInt(TABLE_SIZE);
        String query = "INSERT INTO test_table_basic_rw(id, id2, data) values (:id, :id2, :data) ON CONFLICT DO NOTHING";
        defaultTemplate.update(query, ImmutableMap.of("id", id, "id2", id2, "data", "data" + id));

        query = "SELECT data FROM test_table_basic_rw where id2 = :id2";
        defaultTemplate.queryForList(query, ImmutableMap.of("id2", id2), String.class);
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
