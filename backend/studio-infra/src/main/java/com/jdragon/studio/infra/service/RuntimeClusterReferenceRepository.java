package com.jdragon.studio.infra.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Checks live configuration and work that would be orphaned by deleting a runtime cluster. */
@Repository
public class RuntimeClusterReferenceRepository {

    private static final String COUNT_BLOCKING_REFERENCES_SQL =
            "select coalesce(sum(reference_count), 0) from (" +
                    "select count(*) reference_count from studio_runtime_endpoint where tenant_id=? and runtime_cluster_id=? and deleted=0 " +
                    "union all select count(*) from studio_project_runtime_cluster where tenant_id=? and runtime_cluster_id=? and deleted=0 and enabled=1 " +
                    "union all select count(*) from datasource_cluster_binding where tenant_id=? and runtime_cluster_id=? and deleted=0 and enabled=1 " +
                    "union all select count(*) from collection_task_definition where tenant_id=? and runtime_cluster_id=? and deleted=0 " +
                    "union all select count(*) from quality_task_definition where tenant_id=? and runtime_cluster_id=? and deleted=0 " +
                    "union all select count(*) from workflow_definition where tenant_id=? and runtime_cluster_id=? and deleted=0 " +
                    "union all select count(*) from data_dev_script where tenant_id=? and runtime_cluster_id=? and deleted=0 " +
                    "union all select count(*) from data_service_definition where tenant_id=? and runtime_cluster_id=? and deleted=0 " +
                    "union all select count(*) from data_ingestion_service where tenant_id=? and runtime_cluster_id=? and deleted=0 " +
                    "union all select count(*) from protocol_conversion_service where tenant_id=? and runtime_cluster_id=? and deleted=0 " +
                    "union all select count(*) from model_sync_task where tenant_id=? and runtime_cluster_id=? and deleted=0 and status in ('PENDING','RUNNING','STOPPING') " +
                    "union all select count(*) from dispatch_task where tenant_id=? and target_cluster_id=? and deleted=0 and status in ('QUEUED','RUNNING')" +
                    ") runtime_cluster_references";

    private final JdbcTemplate jdbcTemplate;

    public RuntimeClusterReferenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countBlockingReferences(String tenantId, Long runtimeClusterId) {
        Long count = jdbcTemplate.queryForObject(COUNT_BLOCKING_REFERENCES_SQL, Long.class,
                tenantId, runtimeClusterId,
                tenantId, runtimeClusterId,
                tenantId, runtimeClusterId,
                tenantId, runtimeClusterId,
                tenantId, runtimeClusterId,
                tenantId, runtimeClusterId,
                tenantId, runtimeClusterId,
                tenantId, runtimeClusterId,
                tenantId, runtimeClusterId,
                tenantId, runtimeClusterId,
                tenantId, runtimeClusterId,
                tenantId, runtimeClusterId);
        return count == null ? 0L : count.longValue();
    }

    /** Removes current-configuration rows that were already disabled before cluster deletion. */
    public int cleanupNonBlockingReferences(String tenantId, Long runtimeClusterId) {
        int deleted = jdbcTemplate.update(
                "delete from studio_project_runtime_cluster where tenant_id=? and runtime_cluster_id=?",
                tenantId, runtimeClusterId);
        deleted += jdbcTemplate.update(
                "delete from datasource_cluster_binding where tenant_id=? and runtime_cluster_id=?",
                tenantId, runtimeClusterId);
        deleted += jdbcTemplate.update(
                "delete from studio_runtime_validation where tenant_id=? and runtime_cluster_id=?",
                tenantId, runtimeClusterId);
        return deleted;
    }
}
