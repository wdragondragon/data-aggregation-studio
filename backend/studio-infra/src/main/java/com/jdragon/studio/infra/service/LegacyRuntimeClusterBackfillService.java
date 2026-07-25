package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit one-time compatibility backfill. Normal application startup never invokes this
 * service, and it never infers a target for a tenant that already contains more than one
 * physical runtime-cluster record.
 */
public class LegacyRuntimeClusterBackfillService {

    static final String DEFAULT_LOCAL_CODE = "DEFAULT-LOCAL";

    private static final List<String> REQUIRED_TABLES = List.of(
            "studio_tenant",
            "studio_project",
            "studio_runtime_cluster",
            "studio_project_runtime_cluster",
            "datasource_definition",
            "datasource_cluster_binding");

    private static final List<String> RUNTIME_RESOURCE_TABLES = List.of(
            "collection_task_definition",
            "quality_task_definition",
            "workflow_definition",
            "workflow_definition_version",
            "data_dev_script",
            "data_service_definition",
            "data_ingestion_service",
            "protocol_conversion_service",
            "model_sync_task");

    private final JdbcTemplate jdbcTemplate;
    private final StudioSchemaIntrospector schemaIntrospector;

    public LegacyRuntimeClusterBackfillService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaIntrospector = new StudioSchemaIntrospector(jdbcTemplate);
    }

    /** Runs the one-time maintenance action explicitly; normal application startup never invokes it. */
    @Transactional
    public BackfillReport backfill(boolean dryRun) {
        for (String table : REQUIRED_TABLES) {
            if (!schemaIntrospector.tableExists(table)) {
                return BackfillReport.skipped("SCHEMA_NOT_READY:" + table);
            }
        }

        BackfillReport report = new BackfillReport(dryRun ? "DRY_RUN" : "COMPLETED", dryRun);
        List<String> tenantIds = jdbcTemplate.queryForList(
                "select distinct tenant_id from studio_tenant " +
                        "where deleted=0 and tenant_id is not null order by tenant_id",
                String.class);
        for (String tenantId : tenantIds) {
            backfillTenant(tenantId, dryRun, report);
        }
        return report;
    }

    private void backfillTenant(String tenantId, boolean dryRun, BackfillReport report) {
        ClusterResolution cluster = resolveCluster(tenantId, dryRun);
        if (!cluster.eligible) {
            report.skippedTenantIds.add(tenantId);
            return;
        }

        report.eligibleTenantCount++;
        if (cluster.created) {
            report.createdClusterCount++;
        }
        Long clusterId = cluster.clusterId;
        report.projectAuthorizationCount += backfillProjectAuthorizations(tenantId, clusterId, dryRun);
        report.datasourceBindingCount += backfillDatasourceBindings(tenantId, clusterId, dryRun);
        for (String table : RUNTIME_RESOURCE_TABLES) {
            if (!schemaIntrospector.tableExists(table)
                    || !schemaIntrospector.columnExists(table, "runtime_cluster_id")) {
                continue;
            }
            int count = backfillResourceTable(table, tenantId, clusterId, dryRun);
            report.resourceCounts.put(table,
                    report.resourceCounts.getOrDefault(table, Integer.valueOf(0)) + count);
        }
    }

    private ClusterResolution resolveCluster(String tenantId, boolean dryRun) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, code, deleted, enabled from studio_runtime_cluster where tenant_id=? order by id",
                tenantId);
        if (rows.size() > 1) {
            return ClusterResolution.ineligible();
        }
        if (rows.size() == 1) {
            Map<String, Object> row = rows.get(0);
            String code = row.get("code") == null ? null : String.valueOf(row.get("code"));
            if (!DEFAULT_LOCAL_CODE.equalsIgnoreCase(trim(code))
                    || integer(row.get("deleted")) != 0
                    || integer(row.get("enabled")) != 1) {
                return ClusterResolution.ineligible();
            }
            return ClusterResolution.existing(number(row.get("id")));
        }
        if (dryRun) {
            return ClusterResolution.dryRunCreate();
        }

        Long id = IdWorker.getId();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update(
                "insert into studio_runtime_cluster " +
                        "(id,tenant_id,deleted,created_at,updated_at,code,name,enabled,status,instances_json) " +
                        "values (?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, 0, now, now, DEFAULT_LOCAL_CODE, "默认本地集群", 1, "UNKNOWN", "{}");
        return ClusterResolution.created(id);
    }

    private int backfillProjectAuthorizations(String tenantId, Long clusterId, boolean dryRun) {
        List<Long> projectIds;
        if (clusterId == null) {
            projectIds = jdbcTemplate.queryForList(
                    "select id from studio_project where tenant_id=? and deleted=0 order by id",
                    Long.class, tenantId);
        } else {
            projectIds = jdbcTemplate.queryForList(
                    "select p.id from studio_project p where p.tenant_id=? and p.deleted=0 " +
                            "and not exists (select 1 from studio_project_runtime_cluster a " +
                            "where a.tenant_id=p.tenant_id and a.project_id=p.id and a.runtime_cluster_id=?) " +
                            "order by p.id",
                    Long.class, tenantId, clusterId);
        }
        if (dryRun) {
            return projectIds.size();
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (Long projectId : projectIds) {
            jdbcTemplate.update(
                    "insert into studio_project_runtime_cluster " +
                            "(id,tenant_id,project_id,deleted,created_at,updated_at,runtime_cluster_id,enabled,preferred,allow_manual_override) " +
                            "values (?,?,?,?,?,?,?,?,?,?)",
                    IdWorker.getId(), tenantId, projectId, 0, now, now, clusterId, 1, 1, 0);
        }
        return projectIds.size();
    }

    private int backfillDatasourceBindings(String tenantId, Long clusterId, boolean dryRun) {
        List<Long> datasourceIds;
        if (clusterId == null) {
            datasourceIds = jdbcTemplate.queryForList(
                    "select id from datasource_definition where tenant_id=? and deleted=0 order by id",
                    Long.class, tenantId);
        } else {
            datasourceIds = jdbcTemplate.queryForList(
                    "select d.id from datasource_definition d where d.tenant_id=? and d.deleted=0 " +
                            "and not exists (select 1 from datasource_cluster_binding b " +
                            "where b.tenant_id=d.tenant_id and b.datasource_id=d.id and b.runtime_cluster_id=?) " +
                            "order by d.id",
                    Long.class, tenantId, clusterId);
        }
        if (dryRun) {
            return datasourceIds.size();
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (Long datasourceId : datasourceIds) {
            jdbcTemplate.update(
                    "insert into datasource_cluster_binding " +
                            "(id,tenant_id,deleted,created_at,updated_at,datasource_id,runtime_cluster_id,enabled) " +
                            "values (?,?,?,?,?,?,?,?)",
                    IdWorker.getId(), tenantId, 0, now, now, datasourceId, clusterId, 1);
        }
        return datasourceIds.size();
    }

    private int backfillResourceTable(String table, String tenantId, Long clusterId, boolean dryRun) {
        if (dryRun) {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from " + table +
                            " where tenant_id=? and deleted=0 and runtime_cluster_id is null",
                    Integer.class, tenantId);
            return count == null ? 0 : count.intValue();
        }
        return jdbcTemplate.update(
                "update " + table + " set runtime_cluster_id=? " +
                        "where tenant_id=? and deleted=0 and runtime_cluster_id is null",
                clusterId, tenantId);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private int integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
    }

    private Long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : Long.valueOf(String.valueOf(value));
    }

    private static final class ClusterResolution {
        private final boolean eligible;
        private final Long clusterId;
        private final boolean created;

        private ClusterResolution(boolean eligible, Long clusterId, boolean created) {
            this.eligible = eligible;
            this.clusterId = clusterId;
            this.created = created;
        }

        private static ClusterResolution ineligible() { return new ClusterResolution(false, null, false); }
        private static ClusterResolution existing(Long id) { return new ClusterResolution(true, id, false); }
        private static ClusterResolution created(Long id) { return new ClusterResolution(true, id, true); }
        private static ClusterResolution dryRunCreate() { return new ClusterResolution(true, null, true); }
    }

    public static final class BackfillReport {
        private final String status;
        private final boolean dryRun;
        private int eligibleTenantCount;
        private int createdClusterCount;
        private int projectAuthorizationCount;
        private int datasourceBindingCount;
        private final List<String> skippedTenantIds = new ArrayList<String>();
        private final Map<String, Integer> resourceCounts = new LinkedHashMap<String, Integer>();

        private BackfillReport(String status, boolean dryRun) {
            this.status = status;
            this.dryRun = dryRun;
        }

        private static BackfillReport skipped(String reason) {
            return new BackfillReport("SKIPPED:" + reason, false);
        }

        public String getStatus() { return status; }
        public boolean isDryRun() { return dryRun; }
        public int getEligibleTenantCount() { return eligibleTenantCount; }
        public int getCreatedClusterCount() { return createdClusterCount; }
        public int getProjectAuthorizationCount() { return projectAuthorizationCount; }
        public int getDatasourceBindingCount() { return datasourceBindingCount; }
        public List<String> getSkippedTenantIds() { return Collections.unmodifiableList(skippedTenantIds); }
        public Map<String, Integer> getResourceCounts() { return Collections.unmodifiableMap(resourceCounts); }
        public int getResourceCount() {
            int total = 0;
            for (Integer count : resourceCounts.values()) {
                total += count == null ? 0 : count.intValue();
            }
            return total;
        }

        @Override
        public String toString() {
            return "BackfillReport{" +
                    "status='" + status + '\'' +
                    ", dryRun=" + dryRun +
                    ", eligibleTenants=" + eligibleTenantCount +
                    ", skippedTenants=" + skippedTenantIds.size() +
                    ", createdClusters=" + createdClusterCount +
                    ", projectAuthorizations=" + projectAuthorizationCount +
                    ", datasourceBindings=" + datasourceBindingCount +
                    ", resources=" + getResourceCount() +
                    '}';
        }
    }
}
