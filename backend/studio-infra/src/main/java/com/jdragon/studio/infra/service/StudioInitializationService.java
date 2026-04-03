package com.jdragon.studio.infra.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudioInitializationService {

    private static final String[] RESET_TABLES = new String[]{
            "worker_lease",
            "run_record",
            "dispatch_task",
            "workflow_schedule",
            "workflow_edge",
            "workflow_node",
            "workflow_definition_version",
            "workflow_definition",
            "data_model",
            "datasource_definition",
            "meta_field_definition",
            "meta_schema_version",
            "meta_schema",
            "catalog_plugin",
            "sys_role_permission",
            "sys_user_role",
            "sys_permission",
            "sys_role",
            "sys_user"
    };

    private final JdbcTemplate jdbcTemplate;
    private final BootstrapDataService bootstrapDataService;
    private final PluginCatalogService pluginCatalogService;
    private final DefaultMetadataSchemaBootstrapService metadataBootstrapService;

    public StudioInitializationService(JdbcTemplate jdbcTemplate,
                                       BootstrapDataService bootstrapDataService,
                                       PluginCatalogService pluginCatalogService,
                                       DefaultMetadataSchemaBootstrapService metadataBootstrapService) {
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapDataService = bootstrapDataService;
        this.pluginCatalogService = pluginCatalogService;
        this.metadataBootstrapService = metadataBootstrapService;
    }

    @Transactional
    public void initialize(boolean resetDatabase) {
        if (resetDatabase) {
            resetDatabase();
        }
        bootstrapDataService.bootstrap();
        pluginCatalogService.bootstrapCatalog();
        metadataBootstrapService.bootstrap();
    }

    public void resetDatabase() {
        for (String table : RESET_TABLES) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
