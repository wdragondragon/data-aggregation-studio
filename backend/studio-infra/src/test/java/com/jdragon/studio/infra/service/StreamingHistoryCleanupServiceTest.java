package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class StreamingHistoryCleanupServiceTest {

    @Test
    void cleanupIsDisabledByDefaultAndLeavesEvidenceUntouched() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = schema(connection);
            jdbc.update("insert into stream_task_attempt (id, run_id, status, ended_at) values (10, 100, 'RUNNING', null)");
            jdbc.update("insert into stream_metric_bucket values (1, 10, datetime('now', '-90 days'))");

            ClusterLockService lock = mock(ClusterLockService.class);
            StreamingHistoryCleanupService service = service(jdbc, lock, new StudioPlatformProperties(), mock(RunLogObjectStore.class));

            assertEquals(0L, service.cleanupBatches());
            assertEquals(1, count(jdbc, "stream_metric_bucket"));
        }
    }

    @Test
    void enabledCleanupDeletesOldInactiveRowsAndProtectsActiveRows() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = schema(connection);
            jdbc.update("insert into stream_task_run (id, status, stopped_at) values "
                    + "(100, 'RUNNING', null), (200, 'STOPPED', datetime('now', '-90 days')), "
                    + "(201, 'STOPPED', datetime('now', '-90 days'))");
            jdbc.update("insert into stream_task_attempt (id, run_id, status, ended_at) values "
                    + "(10, 100, 'RUNNING', null), (20, 200, 'FAILED', datetime('now', '-90 days')), "
                    + "(21, 201, 'FAILED', datetime('now', '-90 days'))");
            jdbc.update("insert into stream_task_deploy (id, desired_state, observed_state, current_run_id, current_attempt_id) "
                    + "values (30, 'RUNNING', 'RUNNING', 100, 10)");
            jdbc.update("insert into stream_metric_bucket values "
                    + "(1, 10, datetime('now', '-90 days')), (2, 20, datetime('now', '-90 days'))");
            jdbc.update("insert into stream_task_event values "
                    + "(3, 10, 30, datetime('now', '-90 days')), (4, 20, null, datetime('now', '-90 days'))");
            jdbc.update("insert into run_log_chunk values "
                    + "(5, 10, datetime('now', '-90 days'), 'bucket-a', 'old.log'), "
                    + "(6, 20, datetime('now', '-90 days'), 'bucket-b', 'failed.log')");

            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getStreamingHistory().setCleanupEnabled(true);
            properties.getStreamingHistory().setRetentionDays(30);
            properties.getStreamingHistory().setCleanupBatchSize(100);
            ClusterLockService lock = mock(ClusterLockService.class);
            RunLogObjectStore objectStore = mock(RunLogObjectStore.class);
            StreamingHistoryCleanupService service = service(jdbc, lock, properties, objectStore);

            assertEquals(7L, service.cleanupBatches());
            assertEquals(1, count(jdbc, "stream_metric_bucket"));
            assertEquals(1, count(jdbc, "stream_task_event"));
            assertEquals(1, count(jdbc, "run_log_chunk"));
            assertEquals(1, count(jdbc, "stream_task_attempt"));
            assertEquals(1, count(jdbc, "stream_task_run"));
            verify(objectStore).delete("bucket-b", "failed.log");
            verify(lock, org.mockito.Mockito.never()).release(anyString());
        }
    }

    @Test
    void failedObjectDeletionRetainsChunkMetadataForTheNextRun() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = schema(connection);
            jdbc.update("insert into stream_task_attempt (id, run_id, status, ended_at) values "
                    + "(20, 200, 'FAILED', datetime('now', '-90 days'))");
            jdbc.update("insert into run_log_chunk values "
                    + "(6, 20, datetime('now', '-90 days'), 'bucket-b', 'retry.log')");
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getStreamingHistory().setCleanupEnabled(true);
            RunLogObjectStore objectStore = mock(RunLogObjectStore.class);
            doThrow(new IllegalStateException("storage unavailable"))
                    .when(objectStore).delete("bucket-b", "retry.log");

            assertEquals(0L, service(jdbc, mock(ClusterLockService.class), properties, objectStore).cleanupBatches());
            assertEquals(1, count(jdbc, "run_log_chunk"));
        }
    }

    private static StreamingHistoryCleanupService service(JdbcTemplate jdbc,
                                                           ClusterLockService lock,
                                                           StudioPlatformProperties properties,
                                                           RunLogObjectStore objectStore) {
        when(lock.tryAcquireNonReentrant(anyString(), anyLong())).thenReturn(true);
        return new StreamingHistoryCleanupService(jdbc, lock, properties, objectStore,
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    }

    private static JdbcTemplate schema(Connection connection) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("create table stream_task_run (id integer primary key, status text, stopped_at text)");
        jdbc.execute("create table stream_task_attempt (id integer primary key, run_id integer, status text, ended_at text)");
        jdbc.execute("create table stream_task_deploy (id integer primary key, desired_state text, observed_state text, current_run_id integer, current_attempt_id integer)");
        jdbc.execute("create table stream_metric_bucket (id integer primary key, attempt_id integer, bucket_start text)");
        jdbc.execute("create table stream_task_event (id integer primary key, attempt_id integer, deployment_id integer, occurred_at text)");
        jdbc.execute("create table run_log_chunk (id integer primary key, stream_attempt_id integer, "
                + "chunk_started_at text, object_bucket text, object_key text)");
        return jdbc;
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
