package com.jdragon.studio.server.web.health;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.model.FileTransferEventMode;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.FileTransferEventService;
import com.jdragon.studio.infra.service.FileTransferOutboxCleanupService;
import com.jdragon.studio.infra.service.RunLogStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StudioServerHaHealthIndicatorTest {

    @Test
    void reportsDatabaseCursorLagWhenInMemoryMetricIsStale() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = schema(connection, true);
            Health health = indicator(jdbc).health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("fileTransferEventMode", FileTransferEventMode.OUTBOX)
                    .containsEntry("fileTransferOutbox", "available")
                    .containsEntry("fileTransferOutboxInstanceId", "server-a")
                    .containsEntry("fileTransferOutboxIndexes", 7)
                    .containsEntry("fileTransferOutboxMaximumEventId", "101")
                    .containsEntry("fileTransferOutboxInstanceCursor", "100")
                    .containsEntry("fileTransferOutboxCursorLag", 1L);
        }
    }

    @Test
    void reportsDownWhenAnExpectedIndexIsMissing() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Health health = indicator(schema(connection, false)).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails().get("fileTransferOutbox")).isEqualTo("unavailable");
            assertThat(String.valueOf(health.getDetails().get("fileTransferOutboxError")))
                    .contains("idx_ft_event_cursor_position");
        }
    }

    private static StudioServerHaHealthIndicator indicator(JdbcTemplate jdbc) {
        RunLogStorageService runLogStorage = mock(RunLogStorageService.class);
        when(runLogStorage.objectStorageAvailable()).thenReturn(true);
        when(runLogStorage.storageType()).thenReturn("LOCAL");
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInstanceId("server-a");
        ClusterInstanceIdentity identity = new ClusterInstanceIdentity(properties);
        FileTransferEventService eventService = mock(FileTransferEventService.class);
        when(eventService.status()).thenReturn(new FileTransferEventService.FileTransferOutboxStatus(
                properties.getFileTransfer().getEventMode(), null, null, 0L));
        FileTransferOutboxCleanupService cleanupService = mock(FileTransferOutboxCleanupService.class);
        when(cleanupService.status()).thenReturn(
                new FileTransferOutboxCleanupService.CleanupStatus(null, 0L, null));
        return new StudioServerHaHealthIndicator(runLogStorage, jdbc, properties, identity,
                eventService, cleanupService);
    }

    private static JdbcTemplate schema(Connection connection, boolean complete) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("create table file_transfer_event_outbox (id integer primary key, tenant_id text, "
                + "project_id integer, run_id integer, event_type text, created_at text)");
        jdbc.execute("create index idx_ft_outbox_scope_id on file_transfer_event_outbox(tenant_id, project_id, id)");
        jdbc.execute("create index idx_ft_outbox_run_id on file_transfer_event_outbox(run_id, id)");
        jdbc.execute("create index idx_ft_outbox_created on file_transfer_event_outbox(created_at, id)");
        jdbc.execute("create index idx_ft_outbox_event_type on file_transfer_event_outbox(event_type, created_at, id)");
        jdbc.execute("create table file_transfer_event_consumer_cursor (id integer primary key, instance_id text, "
                + "tenant_id text, project_id integer, last_event_id integer, last_seen_at text)");
        jdbc.execute("create unique index uk_ft_event_cursor_scope on file_transfer_event_consumer_cursor"
                + "(instance_id, tenant_id, project_id)");
        jdbc.execute("create index idx_ft_event_cursor_seen on file_transfer_event_consumer_cursor"
                + "(instance_id, last_seen_at)");
        if (complete) {
            jdbc.execute("create index idx_ft_event_cursor_position on file_transfer_event_consumer_cursor"
                    + "(tenant_id, project_id, last_event_id)");
        }
        jdbc.update("insert into file_transfer_event_outbox "
                + "(id, tenant_id, project_id, run_id, event_type, created_at) values "
                + "(101, 'tenant-a', 10, 100, 'RUN_CHANGED', datetime('now'))");
        jdbc.update("insert into file_transfer_event_consumer_cursor "
                + "(id, instance_id, tenant_id, project_id, last_event_id, last_seen_at) values "
                + "(1, 'server-a', 'tenant-a', 10, 100, datetime('now'))");
        return jdbc;
    }
}
