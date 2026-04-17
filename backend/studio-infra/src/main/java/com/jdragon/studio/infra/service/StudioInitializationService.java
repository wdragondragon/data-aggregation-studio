package com.jdragon.studio.infra.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudioInitializationService {

    private static final String[] RESET_TABLES = new String[]{
            "studio_resource_share",
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
            "collection_task_schedule",
            "collection_task_definition",
            "model_sync_task_item",
            "model_sync_task",
            "data_service_access_log",
            "data_service_access_counter",
            "data_service_subscription",
            "data_service_publish_param",
            "data_service_response_param",
            "data_service_request_param",
            "data_service_definition",
            "studio_project",
            "studio_tenant",
            "worker_lease",
            "run_record",
            "dispatch_task",
            "workflow_schedule",
            "workflow_edge",
            "workflow_node",
            "workflow_definition_version",
            "workflow_definition",
            "data_dev_script",
            "data_dev_directory",
            "data_model_lineage_relation",
            "data_model_attr_index",
            "data_model",
            "datasource_definition",
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
            "sys_user"
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
        if (resetDatabase) {
            resetDatabase();
        }
        bootstrapDataService.bootstrap();
        datasourceTypeCapabilityService.bootstrapDefaults();
        metadataBootstrapService.bootstrap();
        builtinRuleBootstrapService.bootstrap();
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
