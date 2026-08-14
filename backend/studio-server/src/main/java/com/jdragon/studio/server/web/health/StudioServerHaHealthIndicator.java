package com.jdragon.studio.server.web.health;

import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.model.FileTransferEventMode;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.FileTransferEventService;
import com.jdragon.studio.infra.service.FileTransferOutboxCleanupService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class StudioServerHaHealthIndicator implements HealthIndicator {

    private static final Set<String> FILE_TRANSFER_OUTBOX_INDEXES = Set.of(
            "idx_ft_outbox_scope_id",
            "idx_ft_outbox_run_id",
            "idx_ft_outbox_created",
            "idx_ft_outbox_event_type",
            "uk_ft_event_cursor_scope",
            "idx_ft_event_cursor_seen",
            "idx_ft_event_cursor_position");

    private final RunLogStorageService runLogStorageService;
    private final JdbcTemplate jdbcTemplate;
    private final StudioPlatformProperties properties;
    private final ClusterInstanceIdentity identity;
    private final FileTransferEventService eventService;
    private final FileTransferOutboxCleanupService cleanupService;

    public StudioServerHaHealthIndicator(RunLogStorageService runLogStorageService,
                                         JdbcTemplate jdbcTemplate,
                                         StudioPlatformProperties properties,
                                         ClusterInstanceIdentity identity,
                                         FileTransferEventService eventService,
                                         FileTransferOutboxCleanupService cleanupService) {
        this.runLogStorageService = runLogStorageService;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.identity = identity;
        this.eventService = eventService;
        this.cleanupService = cleanupService;
    }

    @Override
    public Health health() {
        if (!runLogStorageService.objectStorageAvailable()) {
            return Health.down()
                    .withDetail("runLogStorage", runLogStorageService.storageType())
                    .withDetail("reason", "object storage is enabled but not fully configured")
                    .build();
        }
        Health.Builder health = Health.up()
                .withDetail("runLogStorage", runLogStorageService.storageType())
                .withDetail("fileTransferEventMode", properties.getFileTransfer().getEventMode());
        if (properties.getFileTransfer().getEventMode() != FileTransferEventMode.OUTBOX) {
            return health.withDetail("fileTransferOutbox", "legacy-scan-disabled").build();
        }
        try {
            Long maximumId = jdbcTemplate.queryForObject(
                    "select coalesce(max(id), 0) from file_transfer_event_outbox", Long.class);
            Long cursorId = jdbcTemplate.queryForObject(
                    "select coalesce(max(last_event_id), 0) from file_transfer_event_consumer_cursor where instance_id=?",
                    Long.class, identity.instanceId());
            Long cursorLag = jdbcTemplate.queryForObject(
                    "select count(*) from file_transfer_event_outbox outbox_row "
                            + "inner join file_transfer_event_consumer_cursor cursor_row "
                            + "on cursor_row.instance_id=? and cursor_row.tenant_id=outbox_row.tenant_id "
                            + "and cursor_row.project_id=outbox_row.project_id "
                            + "where outbox_row.id > cursor_row.last_event_id",
                    Long.class, identity.instanceId());
            Set<String> indexes = fileTransferOutboxIndexes();
            Set<String> missingIndexes = new HashSet<String>(FILE_TRANSFER_OUTBOX_INDEXES);
            missingIndexes.removeAll(indexes);
            if (!missingIndexes.isEmpty()) {
                throw new IllegalStateException("Missing file transfer Outbox indexes: " + missingIndexes);
            }
            FileTransferEventService.FileTransferOutboxStatus eventStatus = eventService.status();
            FileTransferOutboxCleanupService.CleanupStatus cleanupStatus = cleanupService.status();
            health.withDetails(Map.of(
                    "fileTransferOutbox", "available",
                    "fileTransferOutboxInstanceId", identity.instanceId(),
                    "fileTransferOutboxIndexes", indexes.size(),
                    "fileTransferOutboxMaximumEventId", String.valueOf(value(maximumId)),
                    "fileTransferOutboxInstanceCursor", String.valueOf(value(cursorId)),
                    "fileTransferOutboxCursorLag", value(cursorLag),
                    "fileTransferOutboxLastConsumedAt", text(eventStatus.lastConsumedAt()),
                    "fileTransferOutboxLastCleanupAt", text(cleanupStatus.lastCleanupAt()),
                    "fileTransferOutboxLastCleanupDeleted", cleanupStatus.lastDeletedCount()));
            if (eventStatus.lastError() != null || cleanupStatus.lastError() != null) {
                return health.down()
                        .withDetail("fileTransferOutboxConsumeError", text(eventStatus.lastError()))
                        .withDetail("fileTransferOutboxCleanupError", text(cleanupStatus.lastError()))
                        .build();
            }
            return health.build();
        } catch (RuntimeException exception) {
            return health.down()
                    .withDetail("fileTransferOutbox", "unavailable")
                    .withDetail("fileTransferOutboxError", text(exception.getMessage()))
                    .build();
        }
    }

    private long value(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Set<String> fileTransferOutboxIndexes() {
        return jdbcTemplate.execute((ConnectionCallback<Set<String>>) connection -> {
            Set<String> indexes = new HashSet<String>();
            DatabaseMetaData metadata = connection.getMetaData();
            for (String table : Set.of("file_transfer_event_outbox", "file_transfer_event_consumer_cursor")) {
                try (ResultSet result = metadata.getIndexInfo(connection.getCatalog(), connection.getSchema(),
                        table, false, false)) {
                    while (result.next()) {
                        String name = result.getString("INDEX_NAME");
                        if (name != null && FILE_TRANSFER_OUTBOX_INDEXES.contains(name.toLowerCase())) {
                            indexes.add(name.toLowerCase());
                        }
                    }
                }
            }
            return indexes;
        });
    }
}
