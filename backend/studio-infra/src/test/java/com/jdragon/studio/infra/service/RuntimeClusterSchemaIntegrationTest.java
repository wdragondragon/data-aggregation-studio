package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RuntimeClusterSchemaIntegrationTest {

    private static final List<String> RUNTIME_TABLES = Arrays.asList(
            "studio_runtime_cluster",
            "studio_runtime_endpoint",
            "studio_project_runtime_cluster",
            "datasource_cluster_binding",
            "studio_runtime_validation");

    private static final List<String> RUNTIME_INDEXES = Arrays.asList(
            "uk_runtime_cluster_tenant_code",
            "idx_runtime_cluster_tenant_enabled",
            "idx_runtime_endpoint_cluster",
            "uk_project_runtime_cluster",
            "idx_project_runtime_cluster_options",
            "uk_datasource_cluster_binding",
            "idx_datasource_cluster_options",
            "uk_runtime_validation_resource",
            "idx_runtime_validation_invalid",
            "uk_ds_conn_health_fp",
            "uk_ds_conn_health_legacy_fp",
            "idx_ds_conn_record_cluster_lookup",
            "idx_model_sync_task_project_cluster_status",
            "idx_dispatch_task_cluster_status_created",
            "idx_run_record_project_cluster_created",
            "idx_worker_lease_cluster_status",
            "idx_alert_incident_cluster");

    @Test
    void shouldCreateDesktopRuntimeClusterAndDatasourceApplicabilitySchema() throws Exception {
        String schema = Files.readString(Path.of("..").resolve(
                "studio-desktop-runtime/src/main/resources/schema-sqlite.sql").normalize(), StandardCharsets.UTF_8);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            executeSection(statement, schema, "create table if not exists studio_runtime_cluster",
                    "create table if not exists studio_tenant_member");
            executeSection(statement, schema, "create table if not exists datasource_definition",
                    "create table if not exists data_model");

            assertEquals(1, count(statement, "select count(*) from sqlite_master where type='table' and name='studio_runtime_cluster'"));
            assertEquals(1, count(statement, "select count(*) from sqlite_master where type='table' and name='studio_runtime_endpoint'"));
            assertEquals(1, count(statement, "select count(*) from sqlite_master where type='table' and name='studio_project_runtime_cluster'"));
            assertEquals(1, count(statement, "select count(*) from sqlite_master where type='table' and name='datasource_cluster_binding'"));
            assertEquals(1, count(statement, "select count(*) from sqlite_master where type='table' and name='studio_runtime_validation'"));

            statement.executeUpdate("insert into datasource_connection_health "
                    + "(id, tenant_id, runtime_cluster_id, connection_fingerprint) values (1, 'tenant-a', 10, 'fp')");
            statement.executeUpdate("insert into datasource_connection_health "
                    + "(id, tenant_id, runtime_cluster_id, connection_fingerprint) values (2, 'tenant-a', 20, 'fp')");
            assertThrows(SQLException.class, () -> statement.executeUpdate("insert into datasource_connection_health "
                    + "(id, tenant_id, runtime_cluster_id, connection_fingerprint) values (3, 'tenant-a', 10, 'fp')"));

            statement.executeUpdate("insert into datasource_cluster_binding "
                    + "(id, tenant_id, datasource_id, runtime_cluster_id) values (1, 'tenant-a', 101, 10)");
            assertThrows(SQLException.class, () -> statement.executeUpdate("insert into datasource_cluster_binding "
                    + "(id, tenant_id, datasource_id, runtime_cluster_id) values (2, 'tenant-a', 101, 10)"));
        }
    }

    @Test
    void shouldKeepRuntimeClusterDefinitionsAlignedAcrossSchemasMigrationAndUpgrade() throws Exception {
        String mysql = readBackendFile("studio-server/src/main/resources/schema-mysql.sql");
        String sqlite = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String migration = readBackendFile(
                "studio-server/src/main/resources/update/20260720/20260720-runtime-cluster.sql");
        String upgrade = Files.readString(
                Path.of("src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java"),
                StandardCharsets.UTF_8);

        for (String table : RUNTIME_TABLES) {
            assertTrue(mysql.contains("create table if not exists " + table), table + " missing from MySQL schema");
            assertTrue(sqlite.contains("create table if not exists " + table), table + " missing from SQLite schema");
            assertTrue(migration.contains("create table if not exists " + table), table + " missing from migration");
            assertTrue(upgrade.contains("create table " + table + " (")
                            || upgrade.contains("create table if not exists " + table + " ("),
                    table + " missing from startup upgrader");
        }
        for (String index : RUNTIME_INDEXES) {
            assertTrue(mysql.contains(index), index + " missing from MySQL schema");
            assertTrue(sqlite.contains(index), index + " missing from SQLite schema");
            assertTrue(migration.contains(index), index + " missing from migration");
            assertTrue(upgrade.contains(index), index + " missing from startup upgrader");
        }

        assertTableColumns(mysql, "dispatch_task",
                "target_cluster_id", "resource_revision", "claim_token", "worker_boot_id");
        assertTableColumns(sqlite, "dispatch_task",
                "target_cluster_id", "resource_revision", "claim_token", "worker_boot_id");
        assertTableColumns(mysql, "run_record",
                "requested_cluster_id", "actual_cluster_id", "actual_cluster_code", "worker_boot_id");
        assertTableColumns(sqlite, "run_record",
                "requested_cluster_id", "actual_cluster_id", "actual_cluster_code", "worker_boot_id");
        assertTrue(migration.contains("run_record add column worker_boot_id varchar(128)"));
        assertTrue(upgrade.contains("run_record add column worker_boot_id"));
        assertTableColumns(mysql, "studio_alert_incident", "requested_cluster_id", "actual_cluster_id");
        assertTableColumns(sqlite, "studio_alert_incident", "requested_cluster_id", "actual_cluster_id");
        assertTrue(migration.contains("studio_alert_incident add column requested_cluster_id bigint"));
        assertTrue(migration.contains("studio_alert_incident add column actual_cluster_id bigint"));
        assertTrue(upgrade.contains("studio_alert_incident add column requested_cluster_id"));
        assertTrue(upgrade.contains("studio_alert_incident add column actual_cluster_id"));
        assertTableColumns(mysql, "worker_lease",
                "runtime_cluster_id", "runtime_cluster_code", "boot_id", "runtime_version", "plugin_fingerprint");
        assertTableColumns(sqlite, "worker_lease",
                "runtime_cluster_id", "runtime_cluster_code", "boot_id", "runtime_version", "plugin_fingerprint");
        assertTableColumns(mysql, "datasource_connection_health", "runtime_cluster_id");
        assertTableColumns(sqlite, "datasource_connection_health", "runtime_cluster_id");
        assertTableColumns(mysql, "datasource_connection_test_record", "runtime_cluster_id");
        assertTableColumns(sqlite, "datasource_connection_test_record", "runtime_cluster_id");
        for (String table : Arrays.asList(
                "data_service_access_log", "data_ingestion_access_log", "protocol_conversion_access_log")) {
            assertTableColumns(mysql, table, "requested_cluster_id", "actual_cluster_id");
            assertTableColumns(sqlite, table, "requested_cluster_id", "actual_cluster_id");
            assertNullableColumn(mysql, table, "requested_cluster_id");
            assertNullableColumn(mysql, table, "actual_cluster_id");
            assertNullableColumn(sqlite, table, "requested_cluster_id");
            assertNullableColumn(sqlite, table, "actual_cluster_id");
        }

        for (String table : Arrays.asList(
                "collection_task_definition", "quality_task_definition", "workflow_definition",
                "workflow_definition_version", "data_dev_script", "data_service_definition",
                "data_ingestion_service", "protocol_conversion_service", "model_sync_task")) {
            assertNullableColumn(mysql, table, "runtime_cluster_id");
            assertNullableColumn(sqlite, table, "runtime_cluster_id");
        }
        for (String table : Arrays.asList(
                "datasource_connection_health", "datasource_connection_test_record")) {
            assertNullableColumn(mysql, table, "runtime_cluster_id");
            assertNullableColumn(sqlite, table, "runtime_cluster_id");
        }
        for (String column : Arrays.asList(
                "target_cluster_id", "resource_revision", "claim_token", "worker_boot_id")) {
            assertNullableColumn(mysql, "dispatch_task", column);
            assertNullableColumn(sqlite, "dispatch_task", column);
        }
        for (String column : Arrays.asList(
                "requested_cluster_id", "actual_cluster_id", "actual_cluster_code", "worker_boot_id")) {
            assertNullableColumn(mysql, "run_record", column);
            assertNullableColumn(sqlite, "run_record", column);
        }
        assertNullableColumn(mysql, "studio_alert_incident", "requested_cluster_id");
        assertNullableColumn(mysql, "studio_alert_incident", "actual_cluster_id");
        assertNullableColumn(sqlite, "studio_alert_incident", "requested_cluster_id");
        assertNullableColumn(sqlite, "studio_alert_incident", "actual_cluster_id");
        for (String column : Arrays.asList(
                "runtime_cluster_id", "runtime_cluster_code", "boot_id", "runtime_version", "plugin_fingerprint")) {
            assertNullableColumn(mysql, "worker_lease", column);
            assertNullableColumn(sqlite, "worker_lease", column);
        }
        assertFalse(migration.contains("add column runtime_cluster_id bigint not null"));
        assertFalse(migration.contains("add column target_cluster_id bigint not null"));
        assertFalse(migration.contains("add column requested_cluster_id bigint not null"));
        assertFalse(migration.contains("add column actual_cluster_id bigint not null"));
        assertTrue(mysql.contains("legacy_connection_fingerprint varchar(128) generated always as"));
        assertTrue(migration.contains("legacy_connection_fingerprint varchar(128) generated always as"));
        assertTrue(upgrade.contains("legacy_connection_fingerprint varchar(128)"));
        assertTrue(sqlite.contains("where runtime_cluster_id is null"));
    }

    @Test
    void shouldUpgradeLegacySqliteRowsWithoutForcingClusterBackfill() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            createLegacyRuntimeTables(statement);
            statement.executeUpdate("insert into datasource_connection_health "
                    + "(id, tenant_id, connection_fingerprint) values (1, 'tenant-a', 'legacy-fp')");

            JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            StudioSchemaUpgradeService upgradeService = new StudioSchemaUpgradeService(jdbcTemplate);
            invoke(upgradeService, "ensureRuntimeClusterTablesSqlite");
            invoke(upgradeService, "ensureRuntimeClusterColumnsSqlite");
            invoke(upgradeService, "ensureRuntimeClusterTablesSqlite");
            invoke(upgradeService, "ensureRuntimeClusterColumnsSqlite");

            for (String table : RUNTIME_TABLES) {
                assertEquals(1, count(statement,
                        "select count(*) from sqlite_master where type='table' and name='" + table + "'"));
            }
            for (String index : RUNTIME_INDEXES) {
                assertEquals(1, count(statement,
                        "select count(*) from sqlite_master where type='index' and name='" + index + "'"), index);
            }
            assertEquals(1, count(statement,
                    "select count(*) from datasource_connection_health where id=1 and runtime_cluster_id is null"));
            assertEquals(0, columnNotNull(statement, "dispatch_task", "target_cluster_id"));
            assertEquals(0, columnNotNull(statement, "run_record", "requested_cluster_id"));
            assertEquals(0, columnNotNull(statement, "run_record", "worker_boot_id"));
            assertEquals(0, columnNotNull(statement, "studio_alert_incident", "requested_cluster_id"));
            assertEquals(0, columnNotNull(statement, "studio_alert_incident", "actual_cluster_id"));
            assertEquals(0, columnNotNull(statement, "worker_lease", "runtime_cluster_id"));
            assertEquals(0, columnNotNull(statement, "model_sync_task", "runtime_cluster_id"));

            assertThrows(SQLException.class, () -> statement.executeUpdate("insert into datasource_connection_health "
                    + "(id, tenant_id, connection_fingerprint) values (5, 'tenant-a', 'legacy-fp')"));

            statement.executeUpdate("insert into datasource_connection_health "
                    + "(id, tenant_id, runtime_cluster_id, connection_fingerprint) "
                    + "values (2, 'tenant-a', 10, 'legacy-fp')");
            statement.executeUpdate("insert into datasource_connection_health "
                    + "(id, tenant_id, runtime_cluster_id, connection_fingerprint) "
                    + "values (3, 'tenant-a', 20, 'legacy-fp')");
            assertThrows(SQLException.class, () -> statement.executeUpdate("insert into datasource_connection_health "
                    + "(id, tenant_id, runtime_cluster_id, connection_fingerprint) "
                    + "values (4, 'tenant-a', 10, 'legacy-fp')"));

            statement.executeUpdate("insert into studio_runtime_cluster "
                    + "(id, tenant_id, code, name) values (10, 'tenant-a', 'DEFAULT-LOCAL', 'Default local')");
            try (ResultSet resultSet = statement.executeQuery(
                    "select status, enabled from studio_runtime_cluster where id=10")) {
                assertTrue(resultSet.next());
                assertEquals("UNKNOWN", resultSet.getString("status"));
                assertEquals(1, resultSet.getInt("enabled"));
            }
        }
    }

    @Test
    void resetShouldIncludeRuntimeTablesBeforeTheirParents() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/jdragon/studio/infra/service/StudioInitializationService.java"),
                StandardCharsets.UTF_8);
        String resetTables = source.substring(source.indexOf("private static final String[] RESET_TABLES"),
                source.indexOf("};", source.indexOf("private static final String[] RESET_TABLES")));

        for (String table : RUNTIME_TABLES) {
            assertTrue(resetTables.contains("\"" + table + "\""), table + " missing from RESET_TABLES");
        }
        assertBefore(resetTables, "studio_runtime_validation", "collection_task_definition");
        assertBefore(resetTables, "datasource_cluster_binding", "datasource_definition");
        assertBefore(resetTables, "studio_project_runtime_cluster", "studio_project");
        assertBefore(resetTables, "studio_runtime_endpoint", "studio_runtime_cluster");
        assertBefore(resetTables, "datasource_cluster_binding", "studio_runtime_cluster");
        assertBefore(resetTables, "studio_project_runtime_cluster", "studio_runtime_cluster");
        assertBefore(resetTables, "dispatch_task", "studio_runtime_cluster");
        assertBefore(resetTables, "workflow_definition", "studio_runtime_cluster");
        assertBefore(resetTables, "datasource_connection_health", "studio_runtime_cluster");
        assertBefore(resetTables, "datasource_definition", "studio_runtime_cluster");
        assertBefore(resetTables, "dispatch_task", "run_record");
        assertBefore(resetTables, "studio_project", "studio_tenant");
    }

    private void executeSection(Statement statement, String schema, String startMarker, String endMarker) throws Exception {
        int start = schema.indexOf(startMarker);
        int end = schema.indexOf(endMarker, start);
        String section = schema.substring(start, end);
        for (String sql : section.split(";")) {
            if (!sql.trim().isEmpty()) {
                statement.execute(sql.trim());
            }
        }
    }

    private int count(Statement statement, String sql) throws Exception {
        try (java.sql.ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private String readBackendFile(String relativePath) throws Exception {
        return Files.readString(Path.of("..").resolve(relativePath).normalize(), StandardCharsets.UTF_8);
    }

    private void assertTableColumns(String schema, String tableName, String... columns) {
        String table = tableDefinition(schema, tableName);
        for (String column : columns) {
            assertTrue(table.contains(column), tableName + "." + column + " missing");
        }
    }

    private void assertNullableColumn(String schema, String tableName, String columnName) {
        String table = tableDefinition(schema, tableName);
        int start = table.indexOf(columnName);
        assertTrue(start >= 0, tableName + "." + columnName + " missing");
        int end = table.indexOf(',', start);
        String definition = table.substring(start, end < 0 ? table.length() : end).toLowerCase();
        assertFalse(definition.contains("not null"),
                tableName + "." + columnName + " must remain nullable for pre-backfill compatibility");
    }

    private String tableDefinition(String schema, String tableName) {
        int start = schema.indexOf("create table if not exists " + tableName + " (");
        assertTrue(start >= 0, tableName + " definition missing");
        int end = schema.indexOf(");", start);
        assertTrue(end > start, tableName + " definition is incomplete");
        return schema.substring(start, end + 2).toLowerCase();
    }

    private void createLegacyRuntimeTables(Statement statement) throws Exception {
        statement.execute("create table datasource_connection_health "
                + "(id integer primary key, tenant_id text, connection_fingerprint text not null)");
        statement.execute("create unique index uk_ds_conn_health_fp "
                + "on datasource_connection_health(tenant_id, connection_fingerprint)");
        statement.execute("create table datasource_connection_test_record "
                + "(id integer primary key, tenant_id text, connection_fingerprint text, ended_at text)");
        for (String table : Arrays.asList(
                "collection_task_definition", "quality_task_definition", "workflow_definition",
                "workflow_definition_version", "data_dev_script", "data_service_definition",
                "data_ingestion_service", "protocol_conversion_service",
                "data_service_access_log", "data_ingestion_access_log", "protocol_conversion_access_log")) {
            statement.execute("create table " + table + " (id integer primary key)");
        }
        statement.execute("create table model_sync_task "
                + "(id integer primary key, project_id integer, status text)");
        statement.execute("create table dispatch_task (id integer primary key, status text, created_at text)");
        statement.execute("create table run_record (id integer primary key, project_id integer, created_at text)");
        statement.execute("create table studio_alert_incident "
                + "(id integer primary key, project_id integer, last_triggered_at text)");
        statement.execute("create table worker_lease "
                + "(id integer primary key, status text, last_heartbeat_at text)");
    }

    private void invoke(StudioSchemaUpgradeService service, String methodName) throws Exception {
        Method method = StudioSchemaUpgradeService.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(service);
    }

    private int columnNotNull(Statement statement, String tableName, String columnName) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("pragma table_info('" + tableName + "')")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return resultSet.getInt("notnull");
                }
            }
        }
        return fail(tableName + "." + columnName + " missing");
    }

    private void assertBefore(String source, String child, String parent) {
        int childIndex = source.indexOf("\"" + child + "\"");
        int parentIndex = source.indexOf("\"" + parent + "\"");
        assertTrue(childIndex >= 0 && parentIndex >= 0 && childIndex < parentIndex,
                child + " must be reset before " + parent);
    }
}
