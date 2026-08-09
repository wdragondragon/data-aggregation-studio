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
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnstructuredManagementSchemaIntegrationTest {

    private static final List<String> TABLES = List.of(
            "unstructured_source_acl", "unstructured_path_acl", "unstructured_op_audit");
    private static final List<String> INDEXES = List.of(
            "uk_unstructured_source_acl", "idx_unstructured_source_acl_source",
            "uk_unstructured_path_acl", "idx_unstructured_path_acl_source",
            "idx_unstructured_op_audit_source");

    @Test
    void sqliteUpgradeCreatesUnstructuredSchemaIdempotently() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            StudioSchemaUpgradeService upgrade = new StudioSchemaUpgradeService(jdbcTemplate);

            invoke(upgrade, "ensureUnstructuredManagementTablesSqlite");
            invoke(upgrade, "ensureUnstructuredManagementTablesSqlite");

            for (String table : TABLES) {
                assertEquals(1, count(statement,
                        "select count(*) from sqlite_master where type='table' and name='" + table + "'"));
            }
            for (String index : INDEXES) {
                assertEquals(1, count(statement,
                        "select count(*) from sqlite_master where type='index' and name='" + index + "'"), index);
            }
            assertEquals(1, count(statement,
                    "select count(*) from pragma_table_info('unstructured_path_acl') where name='directory'"));
            statement.executeUpdate("insert into unstructured_op_audit " +
                    "(id, tenant_id, project_id, datasource_id, runtime_cluster_id, operation, `recursive`, status) " +
                    "values (1, 'tenant-a', 10, 20, 30, 'DELETE', 1, 'SUCCESS')");
            assertEquals(1, count(statement,
                    "select count(*) from unstructured_op_audit where `recursive` = 1"));
        }
    }

    @Test
    void mysqlSqliteMigrationAndStartupUpgradeStayAligned() throws Exception {
        String mysql = backendFile("studio-server/src/main/resources/schema-mysql.sql");
        String sqlite = backendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String migration = backendFile(
                "studio-server/src/main/resources/update/20260809/20260809-unstructured-management.sql");
        String upgrade = Files.readString(Path.of(
                "src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java"),
                StandardCharsets.UTF_8);

        for (String table : TABLES) {
            assertTrue(mysql.contains("create table if not exists " + table), table);
            assertTrue(sqlite.contains("create table if not exists " + table), table);
            assertTrue(migration.contains("create table if not exists " + table), table);
            assertTrue(upgrade.contains("create table if not exists " + table), table);
        }
        for (String index : INDEXES) {
            assertTrue(mysql.contains(index), index + " missing from MySQL schema");
            assertTrue(sqlite.contains(index), index + " missing from SQLite schema");
            assertTrue(migration.contains(index), index + " missing from migration");
            assertTrue(upgrade.contains(index), index + " missing from startup upgrade");
        }
        assertTrue(mysql.contains("`recursive` int"));
        assertTrue(sqlite.contains("`recursive` integer"));
        assertTrue(migration.contains("`recursive` int"));
        assertTrue(upgrade.contains("`recursive` int"));
        assertTrue(migration.contains("information_schema.statistics"));
        assertTrue(migration.contains("index_name = 'idx_unstructured_source_acl_source'"));
        assertTrue(migration.contains("index_name = 'idx_unstructured_path_acl_source'"));
        assertTrue(mysql.contains("directory int default 1"));
        assertTrue(sqlite.contains("directory integer default 1"));
        assertTrue(migration.contains("column_name = 'directory'"));
        assertTrue(upgrade.contains("unstructured_path_acl add column directory"));
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

    private String backendFile(String relativePath) throws Exception {
        return Files.readString(Path.of("..").resolve(relativePath).normalize(), StandardCharsets.UTF_8);
    }
}
