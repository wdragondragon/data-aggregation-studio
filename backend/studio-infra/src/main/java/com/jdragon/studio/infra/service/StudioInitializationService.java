package com.jdragon.studio.infra.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Locale;

@Service
public class StudioInitializationService {

    private static final String[] MYSQL_RESET_SEED_SCRIPTS = new String[]{
            "data-mysql-base.sql",
            "data-mysql-builtin.sql",
            "data-mysql-runtime-options.sql"
    };

    private static final String[] RESET_TABLES = new String[]{
            "studio_runtime_idempotency",
            "studio_runtime_validation",
            "datasource_cluster_binding",
            "studio_resource_share",
            "studio_alert_delivery",
            "studio_alert_event",
            "studio_alert_incident",
            "studio_alert_channel",
            "studio_alert_rule",
            "studio_project_worker_binding",
            "studio_external_user_binding",
            "studio_project_member_request",
            "studio_project_member",
            "studio_tenant_member",
            "studio_notification",
            "studio_follow_subscription",
            "user_registration_request",
            "quality_issue_comment",
            "quality_issue_event",
            "quality_issue",
            "quality_metric_snapshot",
            "quality_task_alert",
            "quality_task_schedule",
            "quality_task_definition",
            "run_log_chunk",
            "stream_metric_bucket",
            "stream_task_event",
            "stream_task_attempt",
            "stream_task_run",
            "stream_task_deploy",
            "collection_task_schedule",
            "collection_task_metric_binding",
            "collection_task_definition",
            "model_sync_task_item",
            "model_sync_task",
            "protocol_conversion_access_log",
            "protocol_conversion_access_counter",
            "protocol_conversion_subscription",
            "protocol_conversion_service",
            "data_ingestion_access_log",
            "data_ingestion_access_counter",
            "data_ingestion_subscription",
            "data_ingestion_service",
            "data_service_access_log",
            "data_service_access_counter",
            "data_service_subscription",
            "data_service_publish_param",
            "data_service_response_param",
            "data_service_request_param",
            "data_service_definition",
            "studio_project_runtime_cluster",
            "file_transfer_event_outbox",
            "file_transfer_event_consumer_cursor",
            "file_transfer_metric_sample",
            "file_transfer_run_item",
            "file_transfer_run",
            "file_transfer_task_definition",
            "dispatch_task",
            "run_record",
            "worker_lease",
            "workflow_schedule",
            "workflow_edge",
            "workflow_node",
            "workflow_definition_version",
            "workflow_definition",
            "so_pf_env_dep_rel",
            "so_pf_script_env",
            "so_pf_env_dep",
            "data_dev_script",
            "data_dev_directory",
            "data_model_lineage_relation",
            "data_model_attr_index",
            "data_model",
            "datasource_connection_test_record",
            "datasource_connection_health",
            "datasource_definition",
            "studio_runtime_endpoint",
            "studio_runtime_cluster",
            "quality_rule_output_param",
            "quality_rule_input_param",
            "quality_rule",
            "field_mapping_rule_param",
            "field_mapping_rule",
            "meta_field_definition",
            "meta_schema_version",
            "meta_schema",
            "datasource_type_capability",
            "sys_role_permission",
            "sys_user_role",
            "sys_permission",
            "sys_role",
            "sys_user",
            "studio_project",
            "studio_tenant"
    };

    private final JdbcTemplate jdbcTemplate;
    private final BootstrapDataService bootstrapDataService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final DefaultMetadataSchemaBootstrapService metadataBootstrapService;
    private final BuiltinRuleBootstrapService builtinRuleBootstrapService;

    public StudioInitializationService(JdbcTemplate jdbcTemplate,
                                       BootstrapDataService bootstrapDataService,
                                       DatasourceTypeCapabilityService datasourceTypeCapabilityService,
                                       DefaultMetadataSchemaBootstrapService metadataBootstrapService,
                                       BuiltinRuleBootstrapService builtinRuleBootstrapService) {
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapDataService = bootstrapDataService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.metadataBootstrapService = metadataBootstrapService;
        this.builtinRuleBootstrapService = builtinRuleBootstrapService;
    }

    @Transactional
    public void initialize(boolean resetDatabase) {
        if (isMySql()) {
            initializeMySql(resetDatabase);
            return;
        }
        initializeLegacy(resetDatabase);
    }

    private void initializeLegacy(boolean resetDatabase) {
        if (resetDatabase) {
            resetDatabase();
        }
        bootstrapDataService.bootstrap();
        datasourceTypeCapabilityService.bootstrapDefaults();
        metadataBootstrapService.bootstrap();
        builtinRuleBootstrapService.bootstrap();
    }

    private void initializeMySql(boolean resetDatabase) {
        if (!resetDatabase) {
            throw new IllegalStateException("MySQL first initialization is SQL-first. Execute schema-mysql.sql, data-mysql-base.sql and data-mysql-builtin.sql before starting studio. Use init-studio-data.ps1 -ResetDatabase only after the schema already exists.");
        }
        ensureMySqlSchemaExists();
        resetDatabase();
        for (String script : MYSQL_RESET_SEED_SCRIPTS) {
            executeSqlScript(script);
        }
    }

    public void resetDatabase() {
        for (String table : RESET_TABLES) {
            try {
                jdbcTemplate.update("DELETE FROM " + table);
            } catch (DataAccessException ex) {
                if (!isMissingTable(ex)) {
                    throw ex;
                }
            }
        }
    }

    private boolean isMySql() {
        String productName = jdbcTemplate.execute((Connection connection) -> {
            DatabaseMetaData metaData = connection.getMetaData();
            return metaData == null ? null : metaData.getDatabaseProductName();
        });
        if (productName == null) {
            return false;
        }
        String normalized = productName.trim().toLowerCase(Locale.ENGLISH);
        return normalized.contains("mysql") || normalized.contains("mariadb");
    }

    private void ensureMySqlSchemaExists() {
        if (!existsMySqlTable("sys_user")
                || !existsMySqlTable("datasource_type_capability")
                || !existsMySqlTable("meta_schema")) {
            throw new IllegalStateException("MySQL reset requires an existing studio schema. Execute schema-mysql.sql first, then rerun init-studio-data.ps1 -ResetDatabase.");
        }
    }

    private boolean existsMySqlTable(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = database() and table_name = ?",
                Integer.class,
                tableName);
        return count != null && count.intValue() > 0;
    }

    private void executeSqlScript(String scriptName) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(false);
        populator.setIgnoreFailedDrops(true);
        populator.setSqlScriptEncoding("UTF-8");
        populator.addScript(new ClassPathResource(scriptName));
        populator.execute(requireDataSource());
    }

    private DataSource requireDataSource() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("JdbcTemplate has no DataSource bound for studio initialization");
        }
        return dataSource;
    }

    private boolean isMissingTable(DataAccessException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("no such table")
                || normalized.contains("doesn't exist")
                || normalized.contains("does not exist")
                || normalized.contains("unknown table");
    }
}
