package com.jdragon.studio.worker.idempotency;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.ClusterLockService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RuntimeInvocationIdempotencyCleanupRunnerTest {

    @Test
    void shouldDeleteOnlyExpiredCompletedGuards() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbcTemplate.execute("create table studio_runtime_idempotency " +
                    "(id integer primary key,status text,updated_at text,version integer default 0)");
            LocalDateTime now = LocalDateTime.now();
            jdbcTemplate.update("insert into studio_runtime_idempotency (id,status,updated_at) values (?,?,?)",
                    1L, "COMPLETED", now.minusDays(8));
            jdbcTemplate.update("insert into studio_runtime_idempotency (id,status,updated_at) values (?,?,?)",
                    2L, "COMPLETED", now.minusDays(2));
            jdbcTemplate.update("insert into studio_runtime_idempotency (id,status,updated_at) values (?,?,?)",
                    3L, "UNKNOWN", now.minusDays(30));
            jdbcTemplate.update("insert into studio_runtime_idempotency (id,status,updated_at) values (?,?,?)",
                    4L, "RUNNING", now.minusDays(30));
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getRuntimeInvocationIdempotency().setCompletedRetentionDays(7);
            RuntimeInvocationIdempotencyCleanupRunner runner =
                    new RuntimeInvocationIdempotencyCleanupRunner(
                            jdbcTemplate, mock(ClusterLockService.class), properties);

            assertEquals(1, runner.deleteExpiredCompleted());
            assertEquals(0, count(jdbcTemplate, 1L));
            assertEquals(1, count(jdbcTemplate, 2L));
            assertEquals(1, count(jdbcTemplate, 3L));
            assertEquals(1, count(jdbcTemplate, 4L));
            assertEquals("UNKNOWN", jdbcTemplate.queryForObject(
                    "select status from studio_runtime_idempotency where id=4", String.class));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "select version from studio_runtime_idempotency where id=4", Integer.class));
        }
    }

    private int count(JdbcTemplate jdbcTemplate, Long id) {
        Integer result = jdbcTemplate.queryForObject(
                "select count(*) from studio_runtime_idempotency where id=?", Integer.class, id);
        return result == null ? 0 : result.intValue();
    }
}
