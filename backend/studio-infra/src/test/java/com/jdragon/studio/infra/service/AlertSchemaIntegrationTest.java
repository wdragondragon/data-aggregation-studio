package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertSchemaIntegrationTest {

    @Test
    void shouldCreateAndEnforceSqliteAlertSchema() throws Exception {
        String schema = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String alertSchema = section(schema, "create table if not exists studio_alert_rule",
                "create table if not exists user_registration_request");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            for (String sql : alertSchema.split(";")) {
                if (!sql.trim().isEmpty()) {
                    statement.execute(sql.trim());
                }
            }
            assertEquals(5, count(connection,
                    "select count(*) from sqlite_master where type='table' and name like 'studio_alert_%'"));
            assertEquals(1, count(connection,
                    "select count(*) from sqlite_master where type='index' and name='uk_alert_rule_active_name'"));
            assertEquals(1, count(connection,
                    "select count(*) from sqlite_master where type='index' and name='uk_alert_channel_active_name'"));

            statement.executeUpdate("insert into studio_alert_rule " +
                    "(id, tenant_id, project_id, deleted, name, rule_type, subject_type, severity) " +
                    "values (1, 'tenant-a', 10, 0, 'Primary', 'EXECUTION_FAILED', 'COLLECTION_TASK', 'WARNING')");
            assertThrows(SQLException.class, () -> statement.executeUpdate("insert into studio_alert_rule " +
                    "(id, tenant_id, project_id, deleted, name, rule_type, subject_type, severity) " +
                    "values (2, 'tenant-a', 10, 0, 'primary', 'EXECUTION_FAILED', 'COLLECTION_TASK', 'WARNING')"));
            statement.executeUpdate("update studio_alert_rule set deleted=1 where id=1");
            statement.executeUpdate("insert into studio_alert_rule " +
                    "(id, tenant_id, project_id, deleted, name, rule_type, subject_type, severity) " +
                    "values (3, 'tenant-a', 10, 0, 'primary', 'EXECUTION_FAILED', 'COLLECTION_TASK', 'WARNING')");

            statement.executeUpdate("insert into studio_alert_incident " +
                    "(id, tenant_id, project_id, rule_id, signature) values (11, 'tenant-a', 10, 3, 'sig')");
            assertEquals(0, count(connection, "select version from studio_alert_incident where id=11"));
        }
    }

    @Test
    void shouldKeepMysqlInitMigrationAndUpgradeDefinitionsAligned() throws Exception {
        String schema = readBackendFile("studio-server/src/main/resources/schema-mysql.sql");
        String migration = readBackendFile("studio-server/src/main/resources/update/20260713/20260713-alert-center.sql");
        String upgrade = Files.readString(Path.of("src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java"),
                StandardCharsets.UTF_8);
        for (String source : new String[]{schema, migration, upgrade}) {
            assertTrue(source.contains("uk_alert_rule_active_name"));
            assertTrue(source.contains("uk_alert_channel_active_name"));
            assertTrue(source.contains("active_name"));
            assertTrue(source.contains("uk_alert_incident_signature"));
            assertTrue(source.contains("uk_alert_event_source"));
            assertTrue(source.contains("uk_alert_delivery_event_key"));
        }
        assertTrue(schema.contains("condition_json json"));
        assertTrue(schema.contains("payload_json json"));
        assertTrue(schema.contains("version int default 0"));
    }

    private String readBackendFile(String relativePath) throws Exception {
        return Files.readString(Path.of("..").resolve(relativePath).normalize(), StandardCharsets.UTF_8);
    }

    private String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start, "schema section markers must exist");
        return source.substring(start, end);
    }

    private int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }
}
