package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileTransferOutboxCleanupTest {

    @Test
    void keepsActiveCursorEventsAndDeletesOnlyOldTerminalEvents() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("create table file_transfer_run (id integer primary key, tenant_id text, "
                    + "project_id integer, deleted integer, status text)");
            jdbc.execute("create table file_transfer_event_outbox (id integer primary key, tenant_id text, "
                    + "project_id integer, run_id integer, created_at text)");
            jdbc.execute("create table file_transfer_event_consumer_cursor (id integer primary key, instance_id text, "
                    + "tenant_id text, project_id integer, last_event_id integer, last_seen_at text)");
            jdbc.update("insert into file_transfer_run values "
                    + "(100, 'tenant-a', 10, 0, 'SUCCESS'), (200, 'tenant-a', 10, 0, 'RUNNING')");
            jdbc.update("insert into file_transfer_event_outbox values "
                    + "(1, 'tenant-a', 10, 100, datetime('now', '-8 days')), "
                    + "(2, 'tenant-a', 10, 100, datetime('now', '-8 days')), "
                    + "(3, 'tenant-a', 10, 200, datetime('now', '-8 days'))");
            jdbc.update("insert into file_transfer_event_consumer_cursor values "
                    + "(10, 'server-a', 'tenant-a', 10, 2, datetime('now'))");
            ClusterLockService lock = mock(ClusterLockService.class);
            when(lock.tryAcquireNonReentrant(anyString(), anyLong())).thenReturn(true);
            StudioPlatformProperties properties = new StudioPlatformProperties();
            FileTransferOutboxCleanupService service = new FileTransferOutboxCleanupService(jdbc, lock, properties,
                    new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));

            assertEquals(2L, service.cleanupBatches());
            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from file_transfer_event_outbox where id=1", Integer.class));
            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from file_transfer_event_outbox where id=2", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from file_transfer_event_outbox where id=3", Integer.class));
        }
    }

    @Test
    void staleCursorDoesNotBlockCleanup() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("create table file_transfer_run (id integer primary key, tenant_id text, "
                    + "project_id integer, deleted integer, status text)");
            jdbc.execute("create table file_transfer_event_outbox (id integer primary key, tenant_id text, "
                    + "project_id integer, run_id integer, created_at text)");
            jdbc.execute("create table file_transfer_event_consumer_cursor (id integer primary key, instance_id text, "
                    + "tenant_id text, project_id integer, last_event_id integer, last_seen_at text)");
            jdbc.update("insert into file_transfer_run values (100, 'tenant-a', 10, 0, 'SUCCESS')");
            jdbc.update("insert into file_transfer_event_outbox values "
                    + "(1, 'tenant-a', 10, 100, datetime('now', '-8 days'))");
            jdbc.update("insert into file_transfer_event_consumer_cursor values "
                    + "(10, 'stale-server', 'tenant-a', 10, 0, datetime('now', '-72 hours'))");
            FileTransferOutboxCleanupService service = new FileTransferOutboxCleanupService(jdbc,
                    mock(ClusterLockService.class), new StudioPlatformProperties(),
                    new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
            assertEquals(1L, service.cleanupBatches());
        }
    }

    @Test
    void activeCursorBlocksOnlyItsOwnTenantAndProject() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = schema(connection);
            jdbc.update("insert into file_transfer_run values "
                    + "(100, 'tenant-a', 10, 0, 'SUCCESS'), "
                    + "(200, 'tenant-a', 20, 0, 'SUCCESS'), "
                    + "(300, 'tenant-b', 10, 0, 'SUCCESS')");
            jdbc.update("insert into file_transfer_event_outbox values "
                    + "(1, 'tenant-a', 10, 100, datetime('now', '-8 days')), "
                    + "(2, 'tenant-a', 20, 200, datetime('now', '-8 days')), "
                    + "(3, 'tenant-b', 10, 300, datetime('now', '-8 days'))");
            jdbc.update("insert into file_transfer_event_consumer_cursor values "
                    + "(10, 'server-a', 'tenant-a', 10, 0, datetime('now'))");

            assertEquals(2L, service(jdbc).cleanupBatches());
            assertEquals("1", jdbc.queryForObject(
                    "select group_concat(id, ',') from file_transfer_event_outbox order by id", String.class));
        }
    }

    @Test
    void missingOrLogicallyDeletedRunCanBeCleanedButForeignScopeRunCannotMaskIt() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = schema(connection);
            jdbc.update("insert into file_transfer_run values "
                    + "(100, 'tenant-b', 20, 0, 'RUNNING'), "
                    + "(200, 'tenant-a', 10, 1, 'RUNNING')");
            jdbc.update("insert into file_transfer_event_outbox values "
                    + "(1, 'tenant-a', 10, 100, datetime('now', '-8 days')), "
                    + "(2, 'tenant-a', 10, 200, datetime('now', '-8 days'))");

            assertEquals(2L, service(jdbc).cleanupBatches());
        }
    }

    private static JdbcTemplate schema(Connection connection) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("create table file_transfer_run (id integer primary key, tenant_id text, "
                + "project_id integer, deleted integer, status text)");
        jdbc.execute("create table file_transfer_event_outbox (id integer primary key, tenant_id text, "
                + "project_id integer, run_id integer, created_at text)");
        jdbc.execute("create table file_transfer_event_consumer_cursor (id integer primary key, instance_id text, "
                + "tenant_id text, project_id integer, last_event_id integer, last_seen_at text)");
        return jdbc;
    }

    private static FileTransferOutboxCleanupService service(JdbcTemplate jdbc) {
        return new FileTransferOutboxCleanupService(jdbc, mock(ClusterLockService.class),
                new StudioPlatformProperties(),
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    }
}
