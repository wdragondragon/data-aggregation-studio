package com.jdragon.studio.desktopruntime.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.EncryptionService;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Creates the explicit one-cluster registry used by the combined Desktop control and Worker process. */
@Service
public class DesktopRuntimeClusterBootstrapService {

    private static final String DEFAULT_CLUSTER_CODE = "DEFAULT-LOCAL";
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
    private final EncryptionService encryptionService;
    private final StudioPlatformProperties properties;
    private final ServletWebServerApplicationContext webServerApplicationContext;

    public DesktopRuntimeClusterBootstrapService(JdbcTemplate jdbcTemplate,
                                                 EncryptionService encryptionService,
                                                 StudioPlatformProperties properties,
                                                 ServletWebServerApplicationContext webServerApplicationContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.encryptionService = encryptionService;
        this.properties = properties;
        this.webServerApplicationContext = webServerApplicationContext;
    }

    @Transactional
    public BootstrapResult ensureSingleClusterRuntime() {
        String clusterCode = normalizeClusterCode(properties.getRuntimeClusterCode());
        String workerBaseUrl = resolveWorkerBaseUrl();
        properties.setRuntimeClusterCode(clusterCode);
        properties.setWorkerApiBaseUrl(workerBaseUrl);

        BootstrapResult result = new BootstrapResult(clusterCode, workerBaseUrl);
        List<String> tenantIds = jdbcTemplate.queryForList(
                "select tenant_id from studio_tenant where deleted=0 order by tenant_id", String.class);
        for (String tenantId : tenantIds) {
            Long clusterId = ensureCluster(tenantId, clusterCode);
            result.clusterCount++;
            result.endpointCount += ensureEndpoint(tenantId, clusterId, workerBaseUrl);
            result.projectAuthorizationCount += ensureProjectAuthorizations(tenantId, clusterId);
            result.datasourceBindingCount += ensureDatasourceBindings(tenantId, clusterId);
            result.resourceBackfillCount += backfillRuntimeResources(tenantId, clusterId);
        }
        return result;
    }

    private Long ensureCluster(String tenantId, String clusterCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id from studio_runtime_cluster "
                        + "where tenant_id=? and upper(code)=upper(?) order by deleted asc, id asc",
                tenantId, clusterCode);
        String now = timestamp();
        if (rows.isEmpty()) {
            Long clusterId = IdWorker.getId();
            jdbcTemplate.update(
                    "insert into studio_runtime_cluster "
                            + "(id,tenant_id,deleted,created_at,updated_at,code,name,enabled,status,instances_json) "
                            + "values (?,?,?,?,?,?,?,?,?,?)",
                    clusterId, tenantId, 0, now, now, clusterCode,
                    "Default local runtime", 1, "UNKNOWN", "{}");
            return clusterId;
        }
        Long clusterId = number(rows.get(0).get("id"));
        jdbcTemplate.update(
                "update studio_runtime_cluster set deleted=0,updated_at=?,code=?,name=?,enabled=1 "
                        + "where id=?",
                now, clusterCode, "Default local runtime", clusterId);
        return clusterId;
    }

    private int ensureEndpoint(String tenantId, Long clusterId, String workerBaseUrl) {
        List<Map<String, Object>> endpoints = jdbcTemplate.queryForList(
                "select id,mode from studio_runtime_endpoint where tenant_id=? and runtime_cluster_id=? "
                        + "order by case when upper(mode)='HTTP' then 0 else 1 end, deleted asc, id asc",
                tenantId, clusterId);
        Long endpointId = endpoints.isEmpty() ? IdWorker.getId() : number(endpoints.get(0).get("id"));
        String now = timestamp();
        String endpointCiphertext = encryptionService.encrypt(workerBaseUrl);
        String headersCiphertext = encryptionService.encrypt("{}");
        if (endpoints.isEmpty()) {
            jdbcTemplate.update(
                    "insert into studio_runtime_endpoint "
                            + "(id,tenant_id,deleted,created_at,updated_at,runtime_cluster_id,mode,"
                            + "endpoint_ciphertext,headers_ciphertext,token_ciphertext,connect_timeout_millis,"
                            + "read_timeout_millis,enabled) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    endpointId, tenantId, 0, now, now, clusterId, "HTTP", endpointCiphertext,
                    headersCiphertext, null, 3000, 5000, 1);
        } else {
            jdbcTemplate.update(
                    "update studio_runtime_endpoint set deleted=0,updated_at=?,mode='HTTP',endpoint_ciphertext=?,"
                            + "headers_ciphertext=?,token_ciphertext=null,connect_timeout_millis=3000,"
                            + "read_timeout_millis=5000,enabled=1 where id=?",
                    now, endpointCiphertext, headersCiphertext, endpointId);
        }
        jdbcTemplate.update(
                "update studio_runtime_endpoint set enabled=0,updated_at=? "
                        + "where tenant_id=? and runtime_cluster_id=? and id<>?",
                now, tenantId, clusterId, endpointId);
        return 1;
    }

    private int ensureProjectAuthorizations(String tenantId, Long clusterId) {
        List<Long> projectIds = jdbcTemplate.queryForList(
                "select id from studio_project where tenant_id=? and deleted=0 order by id",
                Long.class, tenantId);
        int changed = 0;
        for (Long projectId : projectIds) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select id from studio_project_runtime_cluster "
                            + "where tenant_id=? and project_id=? and runtime_cluster_id=? order by deleted asc,id asc",
                    tenantId, projectId, clusterId);
            String now = timestamp();
            if (rows.isEmpty()) {
                jdbcTemplate.update(
                        "insert into studio_project_runtime_cluster "
                                + "(id,tenant_id,project_id,deleted,created_at,updated_at,runtime_cluster_id,"
                                + "enabled,preferred,allow_manual_override) values (?,?,?,?,?,?,?,?,?,?)",
                        IdWorker.getId(), tenantId, projectId, 0, now, now, clusterId, 1, 1, 0);
            } else {
                jdbcTemplate.update(
                        "update studio_project_runtime_cluster set deleted=0,updated_at=?,enabled=1,"
                                + "preferred=1,allow_manual_override=0 where id=?",
                        now, number(rows.get(0).get("id")));
            }
            jdbcTemplate.update(
                    "update studio_project_runtime_cluster set preferred=0,updated_at=? "
                            + "where tenant_id=? and project_id=? and runtime_cluster_id<>?",
                    now, tenantId, projectId, clusterId);
            changed++;
        }
        return changed;
    }

    private int ensureDatasourceBindings(String tenantId, Long clusterId) {
        List<Long> datasourceIds = jdbcTemplate.queryForList(
                "select id from datasource_definition where tenant_id=? and deleted=0 order by id",
                Long.class, tenantId);
        int changed = 0;
        for (Long datasourceId : datasourceIds) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select id from datasource_cluster_binding "
                            + "where tenant_id=? and datasource_id=? and runtime_cluster_id=? order by deleted asc,id asc",
                    tenantId, datasourceId, clusterId);
            String now = timestamp();
            if (rows.isEmpty()) {
                jdbcTemplate.update(
                        "insert into datasource_cluster_binding "
                                + "(id,tenant_id,deleted,created_at,updated_at,datasource_id,runtime_cluster_id,enabled) "
                                + "values (?,?,?,?,?,?,?,?)",
                        IdWorker.getId(), tenantId, 0, now, now, datasourceId, clusterId, 1);
            } else {
                jdbcTemplate.update(
                        "update datasource_cluster_binding set deleted=0,updated_at=?,enabled=1 where id=?",
                        now, number(rows.get(0).get("id")));
            }
            changed++;
        }
        return changed;
    }

    private int backfillRuntimeResources(String tenantId, Long clusterId) {
        int changed = 0;
        for (String table : RUNTIME_RESOURCE_TABLES) {
            changed += jdbcTemplate.update(
                    "update " + table + " set runtime_cluster_id=? "
                            + "where tenant_id=? and deleted=0 and runtime_cluster_id is null",
                    clusterId, tenantId);
        }
        return changed;
    }

    private String resolveWorkerBaseUrl() {
        int port = webServerApplicationContext.getWebServer().getPort();
        if (port <= 0) {
            throw new IllegalStateException("Desktop Worker HTTP port is unavailable");
        }
        return "http://127.0.0.1:" + port;
    }

    private String normalizeClusterCode(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : DEFAULT_CLUSTER_CODE;
    }

    private Long number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private String timestamp() {
        return LocalDateTime.now().toString();
    }

    public static final class BootstrapResult {
        private final String clusterCode;
        private final String workerBaseUrl;
        private int clusterCount;
        private int endpointCount;
        private int projectAuthorizationCount;
        private int datasourceBindingCount;
        private int resourceBackfillCount;

        private BootstrapResult(String clusterCode, String workerBaseUrl) {
            this.clusterCode = clusterCode;
            this.workerBaseUrl = workerBaseUrl;
        }

        public String getClusterCode() {
            return clusterCode;
        }

        public String getWorkerBaseUrl() {
            return workerBaseUrl;
        }

        public int getClusterCount() {
            return clusterCount;
        }

        public int getEndpointCount() {
            return endpointCount;
        }

        public int getProjectAuthorizationCount() {
            return projectAuthorizationCount;
        }

        public int getDatasourceBindingCount() {
            return datasourceBindingCount;
        }

        public int getResourceBackfillCount() {
            return resourceBackfillCount;
        }

        @Override
        public String toString() {
            return "BootstrapResult{" +
                    "clusterCode='" + clusterCode + '\'' +
                    ", workerBaseUrl='" + workerBaseUrl + '\'' +
                    ", clusters=" + clusterCount +
                    ", endpoints=" + endpointCount +
                    ", projectAuthorizations=" + projectAuthorizationCount +
                    ", datasourceBindings=" + datasourceBindingCount +
                    ", resourcesBackfilled=" + resourceBackfillCount +
                    '}';
        }
    }
}
