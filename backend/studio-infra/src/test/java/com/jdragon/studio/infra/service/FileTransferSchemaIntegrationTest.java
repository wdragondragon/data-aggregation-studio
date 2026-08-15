package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTransferSchemaIntegrationTest {

    private static final List<String> TABLES = List.of(
            "file_transfer_task_definition", "file_transfer_run",
            "file_transfer_run_item", "file_transfer_metric_sample",
            "file_transfer_event_outbox", "file_transfer_event_consumer_cursor");
    private static final List<String> INDEXES = List.of(
            "uk_ft_task_project_code", "idx_ft_task_project_status", "idx_ft_task_schedule",
            "uk_ft_run_record", "idx_ft_run_project_created", "idx_ft_run_task_status",
            "idx_ft_run_target_status", "uk_ft_item_run_core", "idx_ft_item_run_status",
            "idx_ft_item_project_updated", "idx_ft_metric_project_time",
            "idx_ft_metric_run_time", "idx_ft_metric_task_time",
            "idx_ft_outbox_scope_id", "idx_ft_outbox_run_id", "idx_ft_outbox_created",
            "idx_ft_outbox_event_type", "uk_ft_event_cursor_scope",
            "idx_ft_event_cursor_seen", "idx_ft_event_cursor_position");

    @Test
    void sqliteUpgradeCreatesFileTransferSchemaIdempotently() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("create table dispatch_task (id integer primary key, status text)");
            statement.execute("create table run_record (id integer primary key)");
            JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            StudioSchemaUpgradeService upgrade = new StudioSchemaUpgradeService(jdbcTemplate);

            invoke(upgrade, "ensureFileTransferTablesSqlite");
            invoke(upgrade, "ensureFileTransferTablesSqlite");

            for (String table : TABLES) {
                assertEquals(1, count(statement,
                        "select count(*) from sqlite_master where type='table' and name='" + table + "'"));
            }
            for (String index : INDEXES) {
                assertEquals(1, count(statement,
                        "select count(*) from sqlite_master where type='index' and name='" + index + "'"), index);
            }
            assertEquals(1, columnCount(statement, "dispatch_task", "file_transfer_task_id"));
            assertEquals(1, columnCount(statement, "dispatch_task", "file_transfer_run_id"));
            assertEquals(1, columnCount(statement, "run_record", "file_transfer_task_id"));
            assertEquals(1, columnCount(statement, "run_record", "file_transfer_run_id"));
            assertEquals(1, columnCount(statement, "file_transfer_run", "queue_visible"));

            statement.executeUpdate("insert into file_transfer_task_definition " +
                    "(id, tenant_id, project_id, code, name, status, source_runtime_cluster_id, " +
                    "source_datasource_id, target_runtime_cluster_id, target_datasource_id) values " +
                    "(1, 'tenant-a', 10, 'daily', 'Daily', 'DRAFT', 11, 21, 12, 22)");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "insert into file_transfer_task_definition " +
                            "(id, tenant_id, project_id, code, name, status, source_runtime_cluster_id, " +
                            "source_datasource_id, target_runtime_cluster_id, target_datasource_id) values " +
                            "(2, 'tenant-a', 10, 'daily', 'Duplicate', 'DRAFT', 11, 21, 12, 22)"));
            statement.executeUpdate("insert into file_transfer_run " +
                    "(id, tenant_id, project_id, status, target_runtime_cluster_id) " +
                    "values (100, 'tenant-a', 10, 'RUNNING', 12)");
            assertEquals(1, count(statement,
                    "select queue_visible from file_transfer_run where id=100"));
            statement.executeUpdate("insert into file_transfer_run_item " +
                    "(id, tenant_id, project_id, run_id, core_item_id, source_runtime_cluster_id, " +
                    "source_datasource_id, source_path, target_runtime_cluster_id, target_datasource_id, " +
                    "target_path, status, checkpoint_json) values " +
                    "(200, 'tenant-a', 10, 100, 'item-a', 11, 21, '/a', 12, 22, '/b', " +
                    "'TRANSFERRING', '{\"confirmedOffset\":4}')");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "insert into file_transfer_run_item " +
                            "(id, tenant_id, project_id, run_id, core_item_id, source_runtime_cluster_id, " +
                            "source_datasource_id, source_path, target_runtime_cluster_id, target_datasource_id, " +
                            "target_path, status) values " +
                            "(201, 'tenant-a', 10, 100, 'item-a', 11, 21, '/a', 12, 22, '/b', 'QUEUED')"));
            assertEquals(1, count(statement, "select count(*) from file_transfer_run_item " +
                    "where json_extract(checkpoint_json, '$.confirmedOffset') = 4"));
        }
    }

    @Test
    void mysqlSqliteMigrationAndStartupUpgradeStayAligned() throws Exception {
        String mysql = backendFile("studio-server/src/main/resources/schema-mysql.sql");
        String sqlite = backendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String baseMigration = backendFile(
                "studio-server/src/main/resources/update/20260807/20260807-file-transfer.sql");
        String outboxMigration = backendFile(
                "studio-server/src/main/resources/update/20260812/20260812-file-transfer-event-outbox.sql");
        String queueVisibilityMigration = backendFile(
                "studio-server/src/main/resources/update/20260816/20260816-file-transfer-queue-visibility.sql");
        String migration = baseMigration + "\n" + outboxMigration + "\n" + queueVisibilityMigration;
        String upgrade = Files.readString(Path.of(
                "src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java"),
                StandardCharsets.UTF_8);

        for (String table : TABLES) {
            assertTrue(mysql.contains("create table if not exists " + table), table);
            assertTrue(sqlite.contains("create table if not exists " + table), table);
            assertTrue(migration.contains("create table " + table)
                    || migration.contains("create table if not exists " + table), table);
            assertTrue(upgrade.contains("create table if not exists " + table), table);
        }
        for (String index : INDEXES) {
            assertTrue(mysql.contains(index), index + " missing from MySQL schema");
            assertTrue(sqlite.contains(index), index + " missing from SQLite schema");
            assertTrue(migration.contains(index), index + " missing from migration");
            assertTrue(upgrade.contains(index), index + " missing from startup upgrade");
        }
        for (String column : List.of("file_transfer_task_id", "file_transfer_run_id")) {
            assertTrue(mysql.contains(column));
            assertTrue(sqlite.contains(column));
            assertTrue(migration.contains(column));
            assertTrue(upgrade.contains(column));
        }
        assertTrue(mysql.contains("queue_visible int not null default 1"));
        assertTrue(sqlite.contains("queue_visible integer not null default 1"));
        assertTrue(queueVisibilityMigration.contains("add column queue_visible int not null default 1"));
        assertTrue(upgrade.contains("add column queue_visible int not null default 1"));
        assertTrue(upgrade.contains("add column queue_visible integer not null default 1"));
        assertTrue(outboxMigration.contains("FILE_TRANSFER_EVENT_OUTBOX_REQUIRES_MANUAL_BACKFILL"));
        assertTrue(outboxMigration.contains("FILE_TRANSFER_EVENT_CURSOR_REQUIRES_MANUAL_BACKFILL"));
        assertTrue(upgrade.contains("Cannot auto-repair non-empty"));
    }

    @Test
    void sqliteUpgradeRepairsPartiallyCreatedOutboxTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("create table dispatch_task (id integer primary key, status text)");
            statement.execute("create table run_record (id integer primary key)");
            statement.execute("create table file_transfer_event_outbox (id integer primary key)");
            statement.execute("create table file_transfer_event_consumer_cursor (id integer primary key)");
            StudioSchemaUpgradeService upgrade = new StudioSchemaUpgradeService(
                    new JdbcTemplate(new SingleConnectionDataSource(connection, true)));

            invoke(upgrade, "ensureFileTransferTablesSqlite");
            invoke(upgrade, "ensureFileTransferTablesSqlite");

            for (String column : List.of("tenant_id", "project_id", "deleted", "created_at", "updated_at",
                    "event_type", "run_id", "item_id", "occurred_at", "payload_version", "payload_json")) {
                assertEquals(1, columnCount(statement, "file_transfer_event_outbox", column), column);
            }
            for (String column : List.of("instance_id", "tenant_id", "project_id", "last_event_id",
                    "last_seen_at", "created_at", "updated_at")) {
                assertEquals(1, columnCount(statement, "file_transfer_event_consumer_cursor", column), column);
            }
            for (String index : List.of("idx_ft_outbox_scope_id", "idx_ft_outbox_run_id",
                    "idx_ft_outbox_created", "idx_ft_outbox_event_type", "uk_ft_event_cursor_scope",
                    "idx_ft_event_cursor_seen", "idx_ft_event_cursor_position")) {
                assertEquals(1, count(statement,
                        "select count(*) from sqlite_master where type='index' and name='" + index + "'"), index);
            }
        }
    }

    @Test
    void sqliteUpgradeRejectsNonemptyOutboxMissingIdentityColumns() throws Exception {
        assertNonemptyPartialEventTableRejected(
                "file_transfer_event_outbox",
                "create table file_transfer_event_outbox (id integer primary key)",
                "insert into file_transfer_event_outbox (id) values (1)");
    }

    @Test
    void sqliteUpgradeRejectsNonemptyCursorMissingIdentityColumns() throws Exception {
        assertNonemptyPartialEventTableRejected(
                "file_transfer_event_consumer_cursor",
                "create table file_transfer_event_consumer_cursor (id integer primary key)",
                "insert into file_transfer_event_consumer_cursor (id) values (1)");
    }

    private void assertNonemptyPartialEventTableRejected(String tableName, String createSql,
                                                         String insertSql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("create table dispatch_task (id integer primary key, status text)");
            statement.execute("create table run_record (id integer primary key)");
            statement.execute(createSql);
            statement.executeUpdate(insertSql);
            StudioSchemaUpgradeService upgrade = new StudioSchemaUpgradeService(
                    new JdbcTemplate(new SingleConnectionDataSource(connection, true)));

            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> invoke(upgrade, "ensureFileTransferTablesSqlite"));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains("Cannot auto-repair non-empty " + tableName));
            assertTrue(failure.getCause().getMessage().contains("Back up and manually backfill or recreate"));
            assertEquals(1, count(statement, "select count(*) from " + tableName));
        }
    }

    private void invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private int count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private int columnCount(Statement statement, String table, String column) throws Exception {
        try (ResultSet result = statement.executeQuery("pragma table_info(" + table + ")")) {
            int count = 0;
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    count++;
                }
            }
            return count;
        }
    }

    private String backendFile(String relativePath) throws Exception {
        return Files.readString(Path.of("..").resolve(relativePath).normalize(), StandardCharsets.UTF_8);
    }
}
