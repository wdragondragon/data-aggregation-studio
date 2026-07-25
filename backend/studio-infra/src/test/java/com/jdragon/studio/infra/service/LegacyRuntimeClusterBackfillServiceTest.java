package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRuntimeClusterBackfillServiceTest {

    private static final List<String> RESOURCE_TABLES = Arrays.asList(
            "collection_task_definition",
            "quality_task_definition",
            "workflow_definition",
            "workflow_definition_version",
            "data_dev_script",
            "data_service_definition",
            "data_ingestion_service",
            "protocol_conversion_service",
            "model_sync_task");

    @Test
    void shouldBackfillSingleClusterHistoryAndRemainIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(connection);
            createSchema(connection);
            seedTenant(connection, "tenant-a", 1L, 101L, 201L);
            jdbcTemplate.update("insert into collection_task_definition " +
                    "(id,tenant_id,project_id,deleted,runtime_cluster_id) values (999,'tenant-a',101,0,88)");

            LegacyRuntimeClusterBackfillService service = service(jdbcTemplate);
            LegacyRuntimeClusterBackfillService.BackfillReport first = service.backfill(false);

            assertEquals("COMPLETED", first.getStatus());
            assertEquals(1, first.getEligibleTenantCount());
            assertEquals(1, first.getCreatedClusterCount());
            assertEquals(1, first.getProjectAuthorizationCount());
            assertEquals(1, first.getDatasourceBindingCount());
            assertEquals(RESOURCE_TABLES.size(), first.getResourceCount());

            Long clusterId = jdbcTemplate.queryForObject(
                    "select id from studio_runtime_cluster where tenant_id='tenant-a'", Long.class);
            assertEquals(1, count(jdbcTemplate,
                    "select count(*) from studio_project_runtime_cluster where project_id=101 and runtime_cluster_id=?",
                    clusterId));
            assertEquals(1, count(jdbcTemplate,
                    "select count(*) from datasource_cluster_binding where datasource_id=201 and runtime_cluster_id=?",
                    clusterId));
            for (String table : RESOURCE_TABLES) {
                assertEquals(1, count(jdbcTemplate,
                        "select count(*) from " + table + " where tenant_id='tenant-a' and runtime_cluster_id=?",
                        clusterId), table);
            }
            assertEquals(1, count(jdbcTemplate,
                    "select count(*) from collection_task_definition where id=999 and runtime_cluster_id=88"));

            LegacyRuntimeClusterBackfillService.BackfillReport second = service.backfill(false);

            assertEquals(0, second.getCreatedClusterCount());
            assertEquals(0, second.getProjectAuthorizationCount());
            assertEquals(0, second.getDatasourceBindingCount());
            assertEquals(0, second.getResourceCount());
            assertEquals(1, count(jdbcTemplate,
                    "select count(*) from studio_runtime_cluster where tenant_id='tenant-a'"));
        }
    }

    @Test
    void shouldNeverInferTenantWithMultipleOrNonDefaultClusters() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(connection);
            createSchema(connection);
            seedTenant(connection, "tenant-many", 1L, 101L, 201L);
            seedTenant(connection, "tenant-other", 2L, 102L, 202L);
            statement.executeUpdate("insert into studio_runtime_cluster " +
                    "(id,tenant_id,deleted,code,name,enabled,status) values " +
                    "(10,'tenant-many',0,'DEFAULT-LOCAL','Default',1,'UNKNOWN')");
            statement.executeUpdate("insert into studio_runtime_cluster " +
                    "(id,tenant_id,deleted,code,name,enabled,status) values " +
                    "(11,'tenant-many',0,'46','46',1,'UNKNOWN')");
            statement.executeUpdate("insert into studio_runtime_cluster " +
                    "(id,tenant_id,deleted,code,name,enabled,status) values " +
                    "(20,'tenant-other',0,'OMS','OMS',1,'UNKNOWN')");

            LegacyRuntimeClusterBackfillService.BackfillReport report = service(jdbcTemplate).backfill(false);

            assertEquals(0, report.getEligibleTenantCount());
            assertEquals(List.of("tenant-many", "tenant-other"), report.getSkippedTenantIds());
            assertEquals(0, count(jdbcTemplate, "select count(*) from studio_project_runtime_cluster"));
            assertEquals(0, count(jdbcTemplate, "select count(*) from datasource_cluster_binding"));
            assertEquals(2, count(jdbcTemplate,
                    "select count(*) from collection_task_definition where runtime_cluster_id is null"));
        }
    }

    @Test
    void shouldNotBackfillAgainstExplicitlyDisabledDefaultCluster() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(connection);
            createSchema(connection);
            seedTenant(connection, "tenant-a", 1L, 101L, 201L);
            statement.executeUpdate("insert into studio_runtime_cluster " +
                    "(id,tenant_id,deleted,code,name,enabled,status) values " +
                    "(10,'tenant-a',0,'DEFAULT-LOCAL','Default',0,'OFFLINE')");

            LegacyRuntimeClusterBackfillService.BackfillReport report = service(jdbcTemplate).backfill(false);

            assertEquals(0, report.getEligibleTenantCount());
            assertEquals(List.of("tenant-a"), report.getSkippedTenantIds());
            assertEquals(0, count(jdbcTemplate, "select count(*) from studio_project_runtime_cluster"));
            assertEquals(0, count(jdbcTemplate, "select count(*) from datasource_cluster_binding"));
            assertEquals(RESOURCE_TABLES.size(), count(jdbcTemplate,
                    "select count(*) from (" + String.join(" union all ", RESOURCE_TABLES.stream()
                            .map(table -> "select id from " + table + " where runtime_cluster_id is null")
                            .toList()) + ") pending"));
        }
    }

    @Test
    void shouldRunOnlyWhenExplicitlyInvoked() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(connection);
            createSchema(connection);
            seedTenant(connection, "tenant-a", 1L, 101L, 201L);

            assertEquals("DRY_RUN", service(jdbcTemplate).backfill(true).getStatus());

            assertEquals(0, count(jdbcTemplate, "select count(*) from studio_runtime_cluster"));
            assertEquals(1, count(jdbcTemplate,
                    "select count(*) from collection_task_definition where runtime_cluster_id is null"));
        }
    }

    @Test
    void shouldReportDryRunWithoutChangingRows() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(connection);
            createSchema(connection);
            seedTenant(connection, "tenant-a", 1L, 101L, 201L);
            LegacyRuntimeClusterBackfillService.BackfillReport report = service(jdbcTemplate).backfill(true);

            assertEquals("DRY_RUN", report.getStatus());
            assertTrue(report.isDryRun());
            assertEquals(1, report.getCreatedClusterCount());
            assertEquals(1, report.getProjectAuthorizationCount());
            assertEquals(1, report.getDatasourceBindingCount());
            assertEquals(RESOURCE_TABLES.size(), report.getResourceCount());
            assertEquals(0, count(jdbcTemplate, "select count(*) from studio_runtime_cluster"));
            assertEquals(0, count(jdbcTemplate, "select count(*) from studio_project_runtime_cluster"));
            assertEquals(0, count(jdbcTemplate, "select count(*) from datasource_cluster_binding"));
            assertEquals(1, count(jdbcTemplate,
                    "select count(*) from collection_task_definition where runtime_cluster_id is null"));
        }
    }

    private LegacyRuntimeClusterBackfillService service(JdbcTemplate jdbcTemplate) {
        return new LegacyRuntimeClusterBackfillService(jdbcTemplate);
    }

    private JdbcTemplate jdbcTemplate(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    private void createSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table studio_tenant (id integer primary key, tenant_id text, deleted integer)");
            statement.execute("create table studio_project (id integer primary key, tenant_id text, deleted integer)");
            statement.execute("create table studio_runtime_cluster (" +
                    "id integer primary key,tenant_id text,deleted integer,created_at text,updated_at text," +
                    "code text,name text,enabled integer,status text,instances_json text)");
            statement.execute("create unique index uk_runtime_cluster_tenant_code " +
                    "on studio_runtime_cluster(tenant_id,code)");
            statement.execute("create table studio_project_runtime_cluster (" +
                    "id integer primary key,tenant_id text,project_id integer,deleted integer,created_at text,updated_at text," +
                    "runtime_cluster_id integer,enabled integer,preferred integer,allow_manual_override integer)");
            statement.execute("create unique index uk_project_runtime_cluster " +
                    "on studio_project_runtime_cluster(tenant_id,project_id,runtime_cluster_id)");
            statement.execute("create table datasource_definition (" +
                    "id integer primary key,tenant_id text,project_id integer,deleted integer)");
            statement.execute("create table datasource_cluster_binding (" +
                    "id integer primary key,tenant_id text,deleted integer,created_at text,updated_at text," +
                    "datasource_id integer,runtime_cluster_id integer,enabled integer)");
            statement.execute("create unique index uk_datasource_cluster_binding " +
                    "on datasource_cluster_binding(tenant_id,datasource_id,runtime_cluster_id)");
            for (String table : RESOURCE_TABLES) {
                statement.execute("create table " + table + " (" +
                        "id integer primary key,tenant_id text,project_id integer,deleted integer,runtime_cluster_id integer)");
            }
        }
    }

    private void seedTenant(Connection connection, String tenantId, Long tenantRowId,
                            Long projectId, Long datasourceId) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into studio_tenant (id,tenant_id,deleted) values (" +
                    tenantRowId + ",'" + tenantId + "',0)");
            statement.executeUpdate("insert into studio_project (id,tenant_id,deleted) values (" +
                    projectId + ",'" + tenantId + "',0)");
            statement.executeUpdate("insert into datasource_definition (id,tenant_id,project_id,deleted) values (" +
                    datasourceId + ",'" + tenantId + "'," + projectId + ",0)");
            long resourceId = datasourceId * 100;
            for (String table : RESOURCE_TABLES) {
                statement.executeUpdate("insert into " + table +
                        " (id,tenant_id,project_id,deleted,runtime_cluster_id) values (" +
                        resourceId++ + ",'" + tenantId + "'," + projectId + ",0,null)");
            }
        }
    }

    private int count(JdbcTemplate jdbcTemplate, String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count.intValue();
    }
}
