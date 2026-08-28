package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

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

class NativeStreamingSchemaIntegrationTest {

    private static final List<String> TABLES = List.of(
            "stream_task_deploy", "stream_task_run", "stream_task_attempt",
            "stream_metric_bucket", "stream_task_event", "run_log_chunk");

    private static final List<String> INDEXES = List.of(
            "uk_stream_deploy_task", "idx_stream_deploy_state",
            "uk_stream_run_task_gen", "idx_stream_run_task_time",
            "uk_stream_attempt_run_no", "idx_stream_attempt_task_status",
            "uk_stream_metric_attempt_min", "idx_stream_metric_task_time",
            "idx_stream_event_task_time", "idx_stream_event_run_attempt",
            "uk_run_log_chunk_attempt_seq", "idx_run_log_chunk_record_seq",
            "idx_run_log_chunk_task_time");

    @Test
    void mysqlSqliteMigrationAndStartupUpgradeStayAligned() throws Exception {
        String mysql = backendFile("studio-server/src/main/resources/schema-mysql.sql");
        String sqlite = backendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String migration = backendFile(
                "studio-server/src/main/resources/update/20260827/20260827-native-kafka-streaming.sql");
        String upgrade = Files.readString(Path.of(
                "src/main/java/com/jdragon/studio/infra/service/StudioStreamingSchemaUpgradeSupport.java"),
                StandardCharsets.UTF_8);

        for (String source : List.of(mysql, sqlite, migration, upgrade)) {
            assertTrue(source.contains("execution_mode"));
            assertTrue(source.contains("streaming_options_json"));
            for (String table : TABLES) {
                assertTrue(source.contains("create table if not exists " + table),
                        table + " missing from a schema source");
            }
            for (String index : INDEXES) {
                assertTrue(source.contains(index), index + " missing from a schema source");
            }
        }
        assertTrue(mysql.contains("execution_mode varchar(32) not null default 'BATCH'"));
        assertTrue(sqlite.contains("execution_mode text not null default 'BATCH'"));
        assertTrue(migration.contains("where execution_mode is null or trim(execution_mode)=''"));
        assertTrue(migration.contains("Historical collection tasks remain BATCH"));
    }

    @Test
    void sqliteInitializationCreatesStreamingTablesAndIndexes() throws Exception {
        String schema = backendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String section = section(schema, "create table if not exists collection_task_definition",
                "create table if not exists data_service_definition");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            executeScript(statement, section);
            for (String table : TABLES) {
                assertEquals(1, count(statement,
                        "select count(*) from sqlite_master where type='table' and name='" + table + "'"), table);
            }
            for (String index : INDEXES) {
                assertEquals(1, count(statement,
                        "select count(*) from sqlite_master where type='index' and name='" + index + "'"), index);
            }
            statement.executeUpdate("insert into collection_task_definition "
                    + "(id, tenant_id, project_id, name, status) values (1, 'default', 20, 'legacy', 'DRAFT')");
            assertEquals("BATCH", value(statement,
                    "select execution_mode from collection_task_definition where id=1"));
        }
    }

    @Test
    void sqliteLegacyUpgradeIsIdempotentAndDoesNotCreateDeployments() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("create table collection_task_definition ("
                    + "id integer primary key, tenant_id text, project_id integer, name text, status text)");
            statement.executeUpdate("insert into collection_task_definition "
                    + "(id, tenant_id, project_id, name, status) values (1, 'default', 20, 'legacy', 'ONLINE')");
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            StudioStreamingSchemaUpgradeSupport support = new StudioStreamingSchemaUpgradeSupport(
                    jdbc, new StudioSchemaIntrospector(jdbc));

            support.ensureSqlite();
            support.ensureSqlite();

            assertEquals(1, columnCount(statement, "collection_task_definition", "execution_mode"));
            assertEquals(1, columnCount(statement, "collection_task_definition", "streaming_options_json"));
            assertEquals("BATCH", value(statement,
                    "select execution_mode from collection_task_definition where id=1"));
            assertEquals(0, count(statement, "select count(*) from stream_task_deploy"));
            for (String table : TABLES) {
                assertEquals(1, count(statement,
                        "select count(*) from sqlite_master where type='table' and name='" + table + "'"), table);
            }
            for (String index : INDEXES) {
                assertEquals(1, count(statement,
                        "select count(*) from sqlite_master where type='index' and name='" + index + "'"), index);
            }

            statement.executeUpdate("insert into stream_task_deploy "
                    + "(id, tenant_id, project_id, collection_task_id) values (10, 'default', 20, 1)");
            assertThrows(SQLException.class, () -> statement.executeUpdate("insert into stream_task_deploy "
                    + "(id, tenant_id, project_id, collection_task_id) values (11, 'default', 20, 1)"));
        }
    }

    private String backendFile(String relativePath) throws Exception {
        return Files.readString(Path.of("..").resolve(relativePath).normalize(), StandardCharsets.UTF_8);
    }

    private String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start, "schema section markers must exist");
        return source.substring(start, end);
    }

    private void executeScript(Statement statement, String script) throws Exception {
        for (String sql : script.split(";")) {
            if (!sql.trim().isEmpty()) {
                statement.execute(sql.trim());
            }
        }
    }

    private int columnCount(Statement statement, String table, String column) throws Exception {
        return count(statement, "select count(*) from pragma_table_info('" + table + "') where name='" + column + "'");
    }

    private int count(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private String value(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
