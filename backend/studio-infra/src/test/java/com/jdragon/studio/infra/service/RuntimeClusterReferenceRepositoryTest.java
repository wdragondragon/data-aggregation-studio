package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeClusterReferenceRepositoryTest {

    @Test
    void shouldCountOnlyBlockingRuntimeClusterReferencesOnSqlite() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            createReferenceTables(statement);
            RuntimeClusterReferenceRepository repository = new RuntimeClusterReferenceRepository(
                    new JdbcTemplate(new SingleConnectionDataSource(connection, true)));

            statement.executeUpdate("insert into studio_project_runtime_cluster "
                    + "(tenant_id,runtime_cluster_id,deleted,enabled) values ('tenant-a',46,0,0)");
            statement.executeUpdate("insert into datasource_cluster_binding "
                    + "(tenant_id,runtime_cluster_id,deleted,enabled) values ('tenant-a',46,0,0)");
            statement.executeUpdate("insert into studio_runtime_validation "
                    + "(tenant_id,runtime_cluster_id,deleted) values ('tenant-a',46,0)");
            assertEquals(0L, repository.countBlockingReferences("tenant-a", 46L));

            statement.executeUpdate("insert into collection_task_definition "
                    + "(tenant_id,runtime_cluster_id,deleted) values ('tenant-a',46,0)");
            assertEquals(1L, repository.countBlockingReferences("tenant-a", 46L));

            statement.executeUpdate("insert into dispatch_task "
                    + "(tenant_id,target_cluster_id,deleted,status) values ('tenant-a',46,0,'QUEUED')");
            assertEquals(2L, repository.countBlockingReferences("tenant-a", 46L));

            statement.executeUpdate("delete from collection_task_definition");
            statement.executeUpdate("delete from dispatch_task");
            assertEquals(3, repository.cleanupNonBlockingReferences("tenant-a", 46L));
            assertEquals(0, count(statement, "studio_project_runtime_cluster"));
            assertEquals(0, count(statement, "datasource_cluster_binding"));
            assertEquals(0, count(statement, "studio_runtime_validation"));
        }
    }

    private void createReferenceTables(Statement statement) throws Exception {
        statement.execute("create table studio_runtime_endpoint (tenant_id text, runtime_cluster_id integer, deleted integer)");
        statement.execute("create table studio_project_runtime_cluster (tenant_id text, runtime_cluster_id integer, deleted integer, enabled integer)");
        statement.execute("create table datasource_cluster_binding (tenant_id text, runtime_cluster_id integer, deleted integer, enabled integer)");
        statement.execute("create table studio_runtime_validation (tenant_id text, runtime_cluster_id integer, deleted integer)");
        statement.execute("create table collection_task_definition (tenant_id text, runtime_cluster_id integer, deleted integer)");
        statement.execute("create table quality_task_definition (tenant_id text, runtime_cluster_id integer, deleted integer)");
        statement.execute("create table workflow_definition (tenant_id text, runtime_cluster_id integer, deleted integer)");
        statement.execute("create table data_dev_script (tenant_id text, runtime_cluster_id integer, deleted integer)");
        statement.execute("create table data_service_definition (tenant_id text, runtime_cluster_id integer, deleted integer)");
        statement.execute("create table data_ingestion_service (tenant_id text, runtime_cluster_id integer, deleted integer)");
        statement.execute("create table protocol_conversion_service (tenant_id text, runtime_cluster_id integer, deleted integer)");
        statement.execute("create table model_sync_task (tenant_id text, runtime_cluster_id integer, deleted integer, status text)");
        statement.execute("create table dispatch_task (tenant_id text, target_cluster_id integer, deleted integer, status text)");
    }

    private int count(Statement statement, String tableName) throws Exception {
        try (java.sql.ResultSet resultSet = statement.executeQuery("select count(*) from " + tableName)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }
}
