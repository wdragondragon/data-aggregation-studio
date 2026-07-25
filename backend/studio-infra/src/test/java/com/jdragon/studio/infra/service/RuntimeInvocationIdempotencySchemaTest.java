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
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeInvocationIdempotencySchemaTest {

    @Test
    void shouldKeepBaselineMigrationAndStartupUpgradeAligned() throws Exception {
        String mysql = readBackendFile("studio-server/src/main/resources/schema-mysql.sql");
        String sqlite = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String migration = readBackendFile(
                "studio-server/src/main/resources/update/20260722/20260722-runtime-invocation-idempotency.sql");
        String upgrade = Files.readString(Path.of(
                "src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java"),
                StandardCharsets.UTF_8);
        String reset = Files.readString(Path.of(
                "src/main/java/com/jdragon/studio/infra/service/StudioInitializationService.java"),
                StandardCharsets.UTF_8);

        for (String source : new String[]{mysql, sqlite, migration, upgrade}) {
            assertTrue(source.contains("studio_runtime_idempotency"));
            assertTrue(source.contains("uk_runtime_idem_scope_key"));
            assertTrue(source.contains("owner_token_hash"));
            assertTrue(source.contains("owner_instance_id"));
            assertTrue(source.contains("owner_boot_id"));
            assertTrue(source.contains("request_fingerprint"));
            assertTrue(source.contains("response_body_ciphertext"));
            assertTrue(source.contains("version"));
        }
        assertTrue(mysql.contains("response_body_ciphertext longtext"));
        assertTrue(migration.contains("response_body_ciphertext longtext"));
        assertTrue(reset.contains("\"studio_runtime_idempotency\""));
        assertFalse(migration.contains("idempotency_key varchar"));
        assertFalse(migration.contains("request_body"));
        assertFalse(migration.contains("authorization"));
    }

    @Test
    void shouldEnforceScopeKeyUniquenessInSqliteBaseline() throws Exception {
        String sqlite = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            executeSection(statement, sqlite, "create table if not exists studio_runtime_idempotency",
                    "create table if not exists studio_project_runtime_cluster");
            statement.executeUpdate(insertSql(1, "a"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(insertSql(2, "b")));
            statement.executeUpdate("insert into studio_runtime_idempotency " +
                    "(id,tenant_id,project_id,resource_type,resource_id,key_hash,request_fingerprint,status," +
                    "owner_token_hash,owner_instance_id,owner_boot_id,version) values " +
                    "(3,'tenant-a',101,'DATA_INGESTION_SERVICE',33,'" + hex('1') + "','" + hex('c') +
                    "','RUNNING','" + hex('d') + "','worker-1','boot-1',0)");
            assertEquals(2, count(statement,
                    "select count(*) from studio_runtime_idempotency"));
        }
    }

    @Test
    void startupUpgradeShouldCreateIdempotencySchemaIdempotently() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            StudioSchemaUpgradeService service = new StudioSchemaUpgradeService(jdbcTemplate);
            Method method = StudioSchemaUpgradeService.class.getDeclaredMethod(
                    "ensureRuntimeClusterTablesSqlite");
            method.setAccessible(true);
            method.invoke(service);
            method.invoke(service);
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, count(statement, "select count(*) from sqlite_master " +
                        "where type='table' and name='studio_runtime_idempotency'"));
                assertEquals(1, count(statement, "select count(*) from sqlite_master " +
                        "where type='index' and name='uk_runtime_idem_scope_key'"));
            }
        }
    }

    private String insertSql(long id, String fingerprint) {
        return "insert into studio_runtime_idempotency " +
                "(id,tenant_id,project_id,resource_type,resource_id,key_hash,request_fingerprint,status," +
                "owner_token_hash,owner_instance_id,owner_boot_id,version) values (" + id +
                ",'tenant-a',101,'DATA_INGESTION_SERVICE',32,'" + hex('1') + "','" + hex(fingerprint.charAt(0)) +
                "','RUNNING','" + hex('2') + "','worker-1','boot-1',0)";
    }

    private void executeSection(Statement statement, String schema,
                                String startMarker, String endMarker) throws Exception {
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
        try (java.sql.ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private String readBackendFile(String relative) throws Exception {
        return Files.readString(Path.of("..").resolve(relative).normalize(), StandardCharsets.UTF_8);
    }

    private String hex(char value) {
        return String.valueOf(value).repeat(64);
    }
}
