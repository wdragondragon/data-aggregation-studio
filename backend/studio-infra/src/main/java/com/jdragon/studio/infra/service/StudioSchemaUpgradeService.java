package com.jdragon.studio.infra.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class StudioSchemaUpgradeService {

    private final JdbcTemplate jdbcTemplate;
    private final StudioSchemaIntrospector schemaIntrospector;
    private final StudioDatabaseDialectDetector dialectDetector;
    private final StudioDatasourceCapabilityUpgradeSupport datasourceCapabilityUpgradeSupport;

    public StudioSchemaUpgradeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaIntrospector = new StudioSchemaIntrospector(jdbcTemplate);
        this.dialectDetector = new StudioDatabaseDialectDetector(jdbcTemplate);
        this.datasourceCapabilityUpgradeSupport = new StudioDatasourceCapabilityUpgradeSupport(jdbcTemplate, schemaIntrospector);
    }

    public void upgrade() {
        StudioDatabaseDialect dialect = dialectDetector.detect();
        if (dialect == StudioDatabaseDialect.MYSQL) {
            upgradeMysql();
            return;
        }
        if (dialect == StudioDatabaseDialect.SQLITE) {
            upgradeSqlite();
        }
    }

    private void upgradeMysql() {
        ensureColumn("meta_field_definition", "searchable_flag", "alter table meta_field_definition add column searchable_flag int default 0");
        ensureColumn("meta_field_definition", "sortable_flag", "alter table meta_field_definition add column sortable_flag int default 0");
        ensureColumn("meta_field_definition", "query_operators", "alter table meta_field_definition add column query_operators json");
        ensureColumn("meta_field_definition", "query_default_operator", "alter table meta_field_definition add column query_default_operator varchar(64)");
        ensureColumn("datasource_definition", "project_id", "alter table datasource_definition add column project_id bigint");
        ensureColumn("data_model", "project_id", "alter table data_model add column project_id bigint");
        ensureColumn("data_model_attr_index", "project_id", "alter table data_model_attr_index add column project_id bigint");
        ensureColumn("workflow_definition", "project_id", "alter table workflow_definition add column project_id bigint");
        ensureColumn("workflow_definition", "created_by", "alter table workflow_definition add column created_by bigint");
        ensureColumn("workflow_definition_version", "project_id", "alter table workflow_definition_version add column project_id bigint");
        ensureColumn("workflow_node", "project_id", "alter table workflow_node add column project_id bigint");
        ensureColumn("workflow_edge", "project_id", "alter table workflow_edge add column project_id bigint");
        ensureColumn("workflow_schedule", "project_id", "alter table workflow_schedule add column project_id bigint");
        ensureColumn("collection_task_definition", "project_id", "alter table collection_task_definition add column project_id bigint");
        ensureColumn("collection_task_definition", "created_by", "alter table collection_task_definition add column created_by bigint");
        ensureColumn("collection_task_schedule", "project_id", "alter table collection_task_schedule add column project_id bigint");
        ensureColumn("data_dev_directory", "project_id", "alter table data_dev_directory add column project_id bigint");
        ensureColumn("data_dev_script", "project_id", "alter table data_dev_script add column project_id bigint");
        ensureColumn("data_dev_script", "environment_id", "alter table data_dev_script add column environment_id bigint");
        ensureColumn("dispatch_task", "execution_type", "alter table dispatch_task add column execution_type varchar(64)");
        ensureColumn("dispatch_task", "workflow_run_id", "alter table dispatch_task add column workflow_run_id bigint");
        ensureColumn("dispatch_task", "collection_task_id", "alter table dispatch_task add column collection_task_id bigint");
        ensureColumn("dispatch_task", "quality_task_id", "alter table dispatch_task add column quality_task_id bigint");
        ensureColumn("dispatch_task", "triggered_by_user_id", "alter table dispatch_task add column triggered_by_user_id bigint");
        ensureColumn("dispatch_task", "run_record_id", "alter table dispatch_task add column run_record_id bigint");
        ensureColumn("dispatch_task", "project_id", "alter table dispatch_task add column project_id bigint");
        ensureColumn("dispatch_task", "worker_group_code", "alter table dispatch_task add column worker_group_code varchar(255)");
        ensureColumn("dispatch_task", "worker_instance_id", "alter table dispatch_task add column worker_instance_id varchar(255)");
        ensureColumn("dispatch_task", "scheduled_fire_time", "alter table dispatch_task add column scheduled_fire_time datetime");
        ensureColumn("workflow_schedule", "last_triggered_at", "alter table workflow_schedule add column last_triggered_at datetime");
        ensureColumn("run_record", "execution_type", "alter table run_record add column execution_type varchar(64)");
        ensureColumn("run_record", "workflow_run_id", "alter table run_record add column workflow_run_id bigint");
        ensureColumn("run_record", "collection_task_id", "alter table run_record add column collection_task_id bigint");
        ensureColumn("run_record", "quality_task_id", "alter table run_record add column quality_task_id bigint");
        ensureColumn("run_record", "triggered_by_user_id", "alter table run_record add column triggered_by_user_id bigint");
        ensureColumn("run_record", "project_id", "alter table run_record add column project_id bigint");
        ensureColumn("run_record", "worker_group_code", "alter table run_record add column worker_group_code varchar(255)");
        ensureColumn("run_record", "worker_instance_id", "alter table run_record add column worker_instance_id varchar(255)");
        ensureColumn("run_record", "worker_pod_name", "alter table run_record add column worker_pod_name varchar(255)");
        ensureColumn("run_record", "worker_node_name", "alter table run_record add column worker_node_name varchar(255)");
        ensureColumn("run_record", "log_file_path", "alter table run_record add column log_file_path varchar(1000)");
        ensureColumn("run_record", "log_size_bytes", "alter table run_record add column log_size_bytes bigint");
        ensureColumn("run_record", "log_charset", "alter table run_record add column log_charset varchar(64)");
        ensureColumn("run_record", "log_storage_type", "alter table run_record add column log_storage_type varchar(64)");
        ensureColumn("run_record", "log_object_bucket", "alter table run_record add column log_object_bucket varchar(255)");
        ensureColumn("run_record", "log_object_key", "alter table run_record add column log_object_key varchar(1000)");
        ensureColumn("run_record", "log_chunk_count", "alter table run_record add column log_chunk_count int");
        ensureColumn("run_record", "log_status", "alter table run_record add column log_status varchar(64)");
        ensureColumn("run_record", "log_error_summary", "alter table run_record add column log_error_summary varchar(1000)");
        ensureColumn("run_record", "collected_records", "alter table run_record add column collected_records bigint");
        ensureColumn("run_record", "read_succeed_records", "alter table run_record add column read_succeed_records bigint");
        ensureColumn("run_record", "read_failed_records", "alter table run_record add column read_failed_records bigint");
        ensureColumn("run_record", "write_succeed_records", "alter table run_record add column write_succeed_records bigint");
        ensureColumn("run_record", "write_failed_records", "alter table run_record add column write_failed_records bigint");
        ensureColumn("run_record", "failed_records", "alter table run_record add column failed_records bigint");
        ensureColumn("run_record", "success_records", "alter table run_record add column success_records bigint");
        ensureColumn("run_record", "transformer_total_records", "alter table run_record add column transformer_total_records bigint");
        ensureColumn("run_record", "transformer_success_records", "alter table run_record add column transformer_success_records bigint");
        ensureColumn("run_record", "transformer_failed_records", "alter table run_record add column transformer_failed_records bigint");
        ensureColumn("run_record", "transformer_filter_records", "alter table run_record add column transformer_filter_records bigint");
        ensureColumn("studio_project_worker_binding", "worker_group_code", "alter table studio_project_worker_binding add column worker_group_code varchar(255)");
        ensureColumn("worker_lease", "worker_group_code", "alter table worker_lease add column worker_group_code varchar(255)");
        ensureColumn("worker_lease", "instance_id", "alter table worker_lease add column instance_id varchar(255)");
        ensureColumn("worker_lease", "pod_name", "alter table worker_lease add column pod_name varchar(255)");
        ensureColumn("worker_lease", "node_name", "alter table worker_lease add column node_name varchar(255)");
        ensureColumn("worker_lease", "lease_expires_at", "alter table worker_lease add column lease_expires_at datetime");
        ensureClusterLockTableMysql();
        ensureColumn("data_model_lineage_relation", "manual_maintainer_user_id", "alter table data_model_lineage_relation add column manual_maintainer_user_id bigint");
        ensureColumn("data_model_lineage_relation", "manual_maintainer_name_snapshot", "alter table data_model_lineage_relation add column manual_maintainer_name_snapshot varchar(255)");
        ensureQualityTablesMysql();
        ensureDataServiceTablesMysql();
        ensureColumn("data_service_response_param", "transformers_json", "alter table data_service_response_param add column transformers_json json");
        ensureDataIngestionTablesMysql();
        ensureProtocolConversionTablesMysql();
        datasourceCapabilityUpgradeSupport.ensureDatasourceTypeCapabilityTablesMysql();
        ensureOdpsFieldMetadataDefinitionsMysql();
        ensureCurrentOdpsFieldMetadataDefinitions();

        if (!tableExists("data_model_lineage_relation")) {
            jdbcTemplate.execute("create table data_model_lineage_relation (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "level varchar(32)," +
                    "source_type varchar(64)," +
                    "collection_task_id bigint," +
                    "collection_task_name_snapshot varchar(255)," +
                    "source_datasource_id bigint," +
                    "source_datasource_name_snapshot varchar(255)," +
                    "source_datasource_type_snapshot varchar(128)," +
                    "source_database_name_snapshot varchar(255)," +
                    "source_host_snapshot varchar(255)," +
                    "source_port_snapshot varchar(64)," +
                    "source_model_id bigint," +
                    "source_model_name_snapshot varchar(255)," +
                    "source_model_locator_snapshot varchar(1000)," +
                    "source_field_key varchar(255)," +
                    "target_datasource_id bigint," +
                    "target_datasource_name_snapshot varchar(255)," +
                    "target_datasource_type_snapshot varchar(128)," +
                    "target_database_name_snapshot varchar(255)," +
                    "target_host_snapshot varchar(255)," +
                    "target_port_snapshot varchar(64)," +
                    "target_model_id bigint," +
                    "target_model_name_snapshot varchar(255)," +
                    "target_model_locator_snapshot varchar(1000)," +
                    "target_field_key varchar(255)," +
                    "mapping_mode varchar(64)," +
                    "expression_snapshot text," +
                    "manual_maintainer_user_id bigint," +
                    "manual_maintainer_name_snapshot varchar(255)," +
                    "latest_run_id bigint," +
                    "latest_run_status varchar(64)," +
                    "latest_run_at datetime" +
                    ")");
        }

        if (!tableExists("data_model_attr_index")) {
            jdbcTemplate.execute("create table data_model_attr_index (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "model_id bigint," +
                    "datasource_id bigint," +
                    "meta_schema_version_id bigint," +
                    "meta_schema_code varchar(255)," +
                    "scope varchar(64)," +
                    "meta_model_code varchar(128)," +
                    "item_key varchar(255)," +
                    "field_key varchar(255)," +
                    "value_type varchar(64)," +
                    "keyword_value varchar(1000)," +
                    "text_value text," +
                    "number_value decimal(38, 10)," +
                    "bool_value int," +
                    "raw_value text" +
                    ")");
        }

        if (!tableExists("collection_task_definition")) {
            jdbcTemplate.execute("create table collection_task_definition (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "name varchar(255)," +
                    "task_type varchar(64)," +
                    "status varchar(64)," +
                    "source_count int default 1," +
                    "source_bindings_json json," +
                    "target_binding_json json," +
                    "field_mappings_json json," +
                    "execution_options_json json" +
                    ")");
        }

        if (!tableExists("collection_task_schedule")) {
            jdbcTemplate.execute("create table collection_task_schedule (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "collection_task_id bigint," +
                    "cron_expression varchar(255)," +
                    "enabled int default 0," +
                    "timezone varchar(64)," +
                    "last_triggered_at datetime" +
                    ")");
        }

        if (!tableExists("model_sync_task")) {
            jdbcTemplate.execute("create table model_sync_task (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "datasource_id bigint not null," +
                    "datasource_type varchar(128)," +
                    "datasource_name_snapshot varchar(255)," +
                    "batch_no int not null," +
                    "name varchar(255)," +
                    "source varchar(64)," +
                    "status varchar(64)," +
                    "total_count int default 0," +
                    "success_count int default 0," +
                    "failed_count int default 0," +
                    "stopped_count int default 0," +
                    "progress_percent int default 0," +
                    "stop_requested int default 0," +
                    "created_by bigint," +
                    "started_at datetime," +
                    "finished_at datetime," +
                    "duration_ms bigint," +
                    "last_error varchar(1000)" +
                    ")");
        }

        if (!tableExists("model_sync_task_item")) {
            jdbcTemplate.execute("create table model_sync_task_item (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "task_id bigint not null," +
                    "seq_no int not null," +
                    "physical_locator varchar(255)," +
                    "model_name_snapshot varchar(255)," +
                    "status varchar(64)," +
                    "message varchar(2000)," +
                    "started_at datetime," +
                    "finished_at datetime," +
                    "duration_ms bigint" +
                    ")");
        }

        if (!tableExists("field_mapping_rule")) {
            jdbcTemplate.execute("create table field_mapping_rule (" +
                    "id bigint primary key," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "mapping_name varchar(255) not null," +
                    "mapping_type varchar(255) not null," +
                    "mapping_code varchar(255) not null," +
                    "enabled int default 1," +
                    "description varchar(1000)," +
                    "created_by bigint" +
                    ")");
        }

        if (!tableExists("field_mapping_rule_param")) {
            jdbcTemplate.execute("create table field_mapping_rule_param (" +
                    "id bigint primary key," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "rule_id bigint not null," +
                    "param_name varchar(255) not null," +
                    "param_order int not null," +
                    "component_type varchar(64) not null," +
                    "param_value_json text," +
                    "description varchar(1000)" +
                    ")");
        }

        if (!tableExists("user_registration_request")) {
            jdbcTemplate.execute("create table user_registration_request (" +
                    "id bigint primary key," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "status varchar(64) not null," +
                    "username varchar(128) not null," +
                    "password_hash varchar(255) not null," +
                    "display_name varchar(255)," +
                    "reason varchar(1000)," +
                    "review_comment varchar(1000)," +
                    "reviewer_user_id bigint," +
                    "approved_user_id bigint," +
                    "reviewed_at datetime" +
                    ")");
        }

        if (!tableExists("studio_notification")) {
            jdbcTemplate.execute("create table studio_notification (" +
                    "id bigint primary key," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "recipient_user_id bigint not null," +
                    "tenant_id varchar(64)," +
                    "project_id bigint," +
                    "category varchar(128)," +
                    "title varchar(255)," +
                    "content varchar(2000)," +
                    "target_type varchar(128)," +
                    "target_id bigint," +
                    "target_path varchar(1000)," +
                    "target_tenant_id varchar(64)," +
                    "target_project_id bigint," +
                    "dedupe_key varchar(255)," +
                    "read_at datetime," +
                    "archived_at datetime," +
                    "payload_json json" +
                    ")");
        }

        if (!tableExists("studio_follow_subscription")) {
            jdbcTemplate.execute("create table studio_follow_subscription (" +
                    "id bigint primary key," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "user_id bigint not null," +
                    "target_type varchar(128) not null," +
                    "target_id bigint not null," +
                    "enabled int default 1" +
                    ")");
        }

        if (!tableExists("data_dev_directory")) {
            jdbcTemplate.execute("create table data_dev_directory (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "parent_id bigint," +
                    "name varchar(255)," +
                    "permission_code varchar(255)," +
                    "description varchar(1000)" +
                    ")");
        }

        if (!tableExists("data_dev_script")) {
            jdbcTemplate.execute("create table data_dev_script (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "directory_id bigint," +
                    "file_name varchar(255)," +
                    "script_type varchar(64)," +
                    "datasource_id bigint," +
                    "environment_id bigint," +
                    "description varchar(1000)," +
                    "content longtext" +
                    ")");
        }

        ensureScriptEnvironmentTablesMysql();

        if (!tableExists("studio_tenant")) {
            jdbcTemplate.execute("create table studio_tenant (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "tenant_code varchar(64) not null," +
                    "tenant_name varchar(255) not null," +
                    "description varchar(1000)," +
                    "enabled int default 1" +
                    ")");
        }

        if (!tableExists("studio_project")) {
            jdbcTemplate.execute("create table studio_project (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "project_code varchar(128) not null," +
                    "project_name varchar(255) not null," +
                    "description varchar(1000)," +
                    "enabled int default 1," +
                    "default_project int default 0" +
                    ")");
        }

        if (!tableExists("studio_tenant_member")) {
            jdbcTemplate.execute("create table studio_tenant_member (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "user_id bigint not null," +
                    "role_code varchar(128) not null," +
                    "status varchar(64) not null" +
                    ")");
        }

        if (!tableExists("studio_project_member")) {
            jdbcTemplate.execute("create table studio_project_member (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "project_id bigint not null," +
                    "user_id bigint not null," +
                    "role_code varchar(128) not null," +
                    "status varchar(64) not null" +
                    ")");
        }

        if (!tableExists("studio_project_member_request")) {
            jdbcTemplate.execute("create table studio_project_member_request (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "project_id bigint not null," +
                    "user_id bigint not null," +
                    "request_type varchar(64) not null," +
                    "status varchar(64) not null," +
                    "inviter_user_id bigint," +
                    "reviewer_user_id bigint," +
                    "reason varchar(1000)," +
                    "review_comment varchar(1000)" +
                    ")");
        }

        if (!tableExists("studio_project_worker_binding")) {
            jdbcTemplate.execute("create table studio_project_worker_binding (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "project_id bigint not null," +
                    "worker_group_code varchar(255)," +
                    "worker_code varchar(255) not null," +
                    "enabled int default 1" +
                    ")");
        }

        if (!tableExists("studio_resource_share")) {
            jdbcTemplate.execute("create table studio_resource_share (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "source_project_id bigint not null," +
                    "target_project_id bigint not null," +
                    "resource_type varchar(128) not null," +
                    "resource_id bigint not null," +
                    "shared_by_user_id bigint," +
                    "enabled int default 1" +
                    ")");
        }

        ensureIndex("data_model_attr_index", "idx_model_attr_index_model",
                "alter table data_model_attr_index add key idx_model_attr_index_model (model_id)");
        ensureIndex("data_model_attr_index", "idx_model_attr_index_tenant_model_item",
                "alter table data_model_attr_index add key idx_model_attr_index_tenant_model_item (tenant_id, model_id, item_key)");
        ensureIndex("data_model_attr_index", "idx_model_attr_index_datasource",
                "alter table data_model_attr_index add key idx_model_attr_index_datasource (datasource_id)");
        ensureIndex("data_model_attr_index", "idx_model_attr_index_project",
                "alter table data_model_attr_index add key idx_model_attr_index_project (project_id)");
        ensureIndex("data_model_attr_index", "idx_model_attr_index_lookup",
                "alter table data_model_attr_index add key idx_model_attr_index_lookup (meta_schema_code(128), scope, field_key(128), keyword_value(128))");
        ensureIndex("data_model_attr_index", "idx_model_attr_index_number",
                "alter table data_model_attr_index add key idx_model_attr_index_number (meta_schema_code, scope, field_key, number_value)");
        ensureIndex("data_model_attr_index", "idx_model_attr_index_tenant_lookup",
                "alter table data_model_attr_index add key idx_model_attr_index_tenant_lookup (tenant_id, meta_schema_code(128), scope, field_key(128), keyword_value(128))");
        ensureIndex("data_model_attr_index", "idx_model_attr_index_tenant_number",
                "alter table data_model_attr_index add key idx_model_attr_index_tenant_number (tenant_id, meta_schema_code(128), scope, field_key(128), number_value)");
        ensureIndex("datasource_definition", "uk_datasource_definition_project_name",
                "alter table datasource_definition add unique key uk_datasource_definition_project_name (project_id, name)");
        ensureIndex("datasource_definition", "idx_datasource_definition_project",
                "alter table datasource_definition add key idx_datasource_definition_project (project_id)");
        ensureDataModelNameUniqueIndexMysql();
        ensureIndex("data_model", "idx_data_model_project",
                "alter table data_model add key idx_data_model_project (project_id)");
        ensureIndex("data_model", "idx_data_model_tenant_project_created",
                "alter table data_model add key idx_data_model_tenant_project_created (tenant_id, project_id, created_at)");
        ensureIndex("data_model", "idx_data_model_tenant_datasource_created",
                "alter table data_model add key idx_data_model_tenant_datasource_created (tenant_id, datasource_id, created_at)");
        ensureIndex("model_sync_task", "uk_model_sync_task_project_datasource_batch",
                "alter table model_sync_task add unique key uk_model_sync_task_project_datasource_batch (project_id, datasource_id, batch_no)");
        ensureIndex("model_sync_task", "idx_model_sync_task_project_created",
                "alter table model_sync_task add key idx_model_sync_task_project_created (project_id, created_at)");
        ensureIndex("model_sync_task", "idx_model_sync_task_project_status",
                "alter table model_sync_task add key idx_model_sync_task_project_status (project_id, status)");
        ensureIndex("model_sync_task_item", "idx_model_sync_task_item_task_seq",
                "alter table model_sync_task_item add key idx_model_sync_task_item_task_seq (task_id, seq_no)");
        ensureIndex("model_sync_task_item", "idx_model_sync_task_item_task_status",
                "alter table model_sync_task_item add key idx_model_sync_task_item_task_status (task_id, status)");
        ensureIndex("field_mapping_rule", "idx_field_mapping_rule_type_enabled",
                "alter table field_mapping_rule add key idx_field_mapping_rule_type_enabled (mapping_type, enabled)");
        ensureIndex("field_mapping_rule", "idx_field_mapping_rule_created_at",
                "alter table field_mapping_rule add key idx_field_mapping_rule_created_at (created_at)");
        ensureIndex("field_mapping_rule", "uk_field_mapping_rule_code",
                "alter table field_mapping_rule add unique key uk_field_mapping_rule_code (mapping_code)");
        ensureIndex("field_mapping_rule_param", "idx_field_mapping_rule_param_rule_order",
                "alter table field_mapping_rule_param add key idx_field_mapping_rule_param_rule_order (rule_id, param_order)");
        ensureIndex("field_mapping_rule_param", "idx_field_mapping_rule_param_rule_name",
                "alter table field_mapping_rule_param add key idx_field_mapping_rule_param_rule_name (rule_id, param_name)");
        ensureIndex("user_registration_request", "idx_user_registration_request_status_created",
                "alter table user_registration_request add key idx_user_registration_request_status_created (status, created_at)");
        ensureIndex("user_registration_request", "uk_user_registration_request_username_status",
                "alter table user_registration_request add unique key uk_user_registration_request_username_status (username, status)");
        ensureIndex("studio_notification", "idx_studio_notification_recipient_created",
                "alter table studio_notification add key idx_studio_notification_recipient_created (recipient_user_id, created_at)");
        ensureIndex("studio_notification", "idx_studio_notification_recipient_unread",
                "alter table studio_notification add key idx_studio_notification_recipient_unread (recipient_user_id, read_at, archived_at)");
        ensureIndex("studio_notification", "uk_studio_notification_recipient_dedupe",
                "alter table studio_notification add unique key uk_studio_notification_recipient_dedupe (recipient_user_id, dedupe_key)");
        ensureIndex("studio_follow_subscription", "uk_studio_follow_subscription_target",
                "alter table studio_follow_subscription add unique key uk_studio_follow_subscription_target (tenant_id, project_id, user_id, target_type, target_id)");
        ensureIndex("studio_follow_subscription", "idx_studio_follow_subscription_lookup",
                "alter table studio_follow_subscription add key idx_studio_follow_subscription_lookup (target_type, target_id, enabled)");
        ensureIndex("workflow_definition", "uk_workflow_definition_project_code",
                "alter table workflow_definition add unique key uk_workflow_definition_project_code (project_id, code)");
        ensureIndex("workflow_definition", "uk_workflow_definition_project_name",
                "alter table workflow_definition add unique key uk_workflow_definition_project_name (project_id, name)");
        ensureIndex("workflow_definition", "idx_workflow_definition_project",
                "alter table workflow_definition add key idx_workflow_definition_project (project_id)");
        ensureIndex("workflow_definition_version", "idx_workflow_definition_version_project",
                "alter table workflow_definition_version add key idx_workflow_definition_version_project (project_id)");
        ensureIndex("workflow_node", "idx_workflow_node_project",
                "alter table workflow_node add key idx_workflow_node_project (project_id)");
        ensureIndex("workflow_edge", "idx_workflow_edge_project",
                "alter table workflow_edge add key idx_workflow_edge_project (project_id)");
        ensureIndex("workflow_schedule", "idx_workflow_schedule_project",
                "alter table workflow_schedule add key idx_workflow_schedule_project (project_id)");
        ensureIndex("collection_task_definition", "uk_collection_task_definition_project_name",
                "alter table collection_task_definition add unique key uk_collection_task_definition_project_name (project_id, name)");
        ensureIndex("collection_task_definition", "idx_collection_task_definition_project",
                "alter table collection_task_definition add key idx_collection_task_definition_project (project_id)");
        ensureIndex("collection_task_schedule", "idx_collection_task_schedule_project",
                "alter table collection_task_schedule add key idx_collection_task_schedule_project (project_id)");
        ensureIndex("data_dev_directory", "idx_data_dev_directory_project_parent",
                "alter table data_dev_directory add key idx_data_dev_directory_project_parent (project_id, parent_id)");
        ensureIndex("data_dev_directory", "idx_data_dev_directory_parent",
                "alter table data_dev_directory add key idx_data_dev_directory_parent (parent_id)");
        ensureIndex("data_dev_script", "idx_data_dev_script_project_directory",
                "alter table data_dev_script add key idx_data_dev_script_project_directory (project_id, directory_id)");
        ensureIndex("data_dev_script", "idx_data_dev_script_directory",
                "alter table data_dev_script add key idx_data_dev_script_directory (directory_id)");
        ensureIndex("data_dev_script", "idx_data_dev_script_datasource",
                "alter table data_dev_script add key idx_data_dev_script_datasource (datasource_id)");
        ensureIndex("data_dev_script", "idx_data_dev_script_environment",
                "alter table data_dev_script add key idx_data_dev_script_environment (environment_id)");
        ensureIndex("so_pf_env_dep", "idx_so_pf_env_dep_tenant_enabled",
                "alter table so_pf_env_dep add key idx_so_pf_env_dep_tenant_enabled (tenant_id, enabled)");
        ensureIndex("so_pf_env_dep", "uk_so_pf_env_dep_name_ver",
                "alter table so_pf_env_dep add unique key uk_so_pf_env_dep_name_ver (tenant_id, name, version)");
        ensureIndex("so_pf_env_dep_file", "idx_so_pf_env_dep_file_dep",
                "alter table so_pf_env_dep_file add key idx_so_pf_env_dep_file_dep (tenant_id, dependency_id, visible)");
        ensureIndex("so_pf_env_dep_file", "idx_so_pf_env_dep_file_runtime",
                "alter table so_pf_env_dep_file add key idx_so_pf_env_dep_file_runtime (tenant_id, dependency_id, runtime_artifact)");
        ensureIndex("so_pf_script_env", "uk_so_pf_script_env_code",
                "alter table so_pf_script_env add unique key uk_so_pf_script_env_code (tenant_id, environment_code)");
        ensureIndex("so_pf_script_env", "idx_so_pf_script_env_enabled",
                "alter table so_pf_script_env add key idx_so_pf_script_env_enabled (tenant_id, enabled)");
        ensureIndex("so_pf_env_dep_rel", "idx_so_pf_env_dep_rel_env",
                "alter table so_pf_env_dep_rel add key idx_so_pf_env_dep_rel_env (environment_id, sort_order)");
        ensureIndex("so_pf_env_dep_rel", "uk_so_pf_env_dep_rel",
                "alter table so_pf_env_dep_rel add unique key uk_so_pf_env_dep_rel (environment_id, dependency_id)");
        ensureIndex("dispatch_task", "idx_dispatch_task_project_status",
                "alter table dispatch_task add key idx_dispatch_task_project_status (project_id, status)");
        ensureIndex("dispatch_task", "idx_dispatch_task_project_workflow_run",
                "alter table dispatch_task add key idx_dispatch_task_project_workflow_run (project_id, workflow_run_id)");
        ensureIndex("dispatch_task", "idx_dispatch_task_project_quality_task_status",
                "alter table dispatch_task add key idx_dispatch_task_project_quality_task_status (project_id, quality_task_id, status)");
        ensureIndex("dispatch_task", "idx_dispatch_task_project_status_created",
                "alter table dispatch_task add key idx_dispatch_task_project_status_created (project_id, status, created_at)");
        ensureIndex("dispatch_task", "idx_dispatch_task_group_status_created",
                "alter table dispatch_task add key idx_dispatch_task_group_status_created (worker_group_code, status, created_at)");
        ensureIndex("run_record", "idx_run_record_project_created",
                "alter table run_record add key idx_run_record_project_created (project_id, created_at)");
        ensureIndex("run_record", "idx_run_record_project_workflow_run",
                "alter table run_record add key idx_run_record_project_workflow_run (project_id, workflow_run_id)");
        ensureIndex("run_record", "idx_run_record_project_collection_task_ended",
                "alter table run_record add key idx_run_record_project_collection_task_ended (project_id, collection_task_id, ended_at)");
        ensureIndex("run_record", "idx_run_record_project_quality_task_ended",
                "alter table run_record add key idx_run_record_project_quality_task_ended (project_id, quality_task_id, ended_at)");
        ensureIndex("data_model_lineage_relation", "idx_data_model_lineage_target_level",
                "alter table data_model_lineage_relation add key idx_data_model_lineage_target_level (tenant_id, target_model_id, level)");
        ensureIndex("data_model_lineage_relation", "idx_data_model_lineage_source_level",
                "alter table data_model_lineage_relation add key idx_data_model_lineage_source_level (tenant_id, source_model_id, level)");
        ensureIndex("data_model_lineage_relation", "idx_data_model_lineage_task",
                "alter table data_model_lineage_relation add key idx_data_model_lineage_task (tenant_id, collection_task_id)");
        ensureIndex("data_model_lineage_relation", "idx_data_model_lineage_target_datasource_level",
                "alter table data_model_lineage_relation add key idx_data_model_lineage_target_datasource_level (tenant_id, target_datasource_id, level)");
        ensureIndex("data_model_lineage_relation", "idx_data_model_lineage_source_datasource_level",
                "alter table data_model_lineage_relation add key idx_data_model_lineage_source_datasource_level (tenant_id, source_datasource_id, level)");
        ensureIndex("studio_tenant", "uk_studio_tenant_code",
                "alter table studio_tenant add unique key uk_studio_tenant_code (tenant_code)");
        ensureIndex("studio_project", "uk_studio_project_code",
                "alter table studio_project add unique key uk_studio_project_code (tenant_id, project_code)");
        ensureIndex("studio_project", "uk_studio_project_name",
                "alter table studio_project add unique key uk_studio_project_name (tenant_id, project_name)");
        ensureIndex("studio_tenant_member", "uk_studio_tenant_member_user",
                "alter table studio_tenant_member add unique key uk_studio_tenant_member_user (tenant_id, user_id)");
        ensureIndex("studio_project_member", "uk_studio_project_member_user",
                "alter table studio_project_member add unique key uk_studio_project_member_user (project_id, user_id)");
        ensureIndex("studio_project_member_request", "idx_studio_project_member_request_lookup",
                "alter table studio_project_member_request add key idx_studio_project_member_request_lookup (project_id, user_id, status)");
        ensureIndex("studio_project_worker_binding", "uk_studio_project_worker_binding",
                "alter table studio_project_worker_binding add unique key uk_studio_project_worker_binding (project_id, worker_code)");
        ensureIndex("studio_project_worker_binding", "idx_studio_project_worker_group",
                "alter table studio_project_worker_binding add key idx_studio_project_worker_group (project_id, worker_group_code)");
        ensureIndex("worker_lease", "idx_worker_lease_code_instance",
                "alter table worker_lease add key idx_worker_lease_code_instance (worker_code, instance_id)");
        ensureIndex("worker_lease", "idx_worker_lease_group_instance",
                "alter table worker_lease add key idx_worker_lease_group_instance (worker_group_code, instance_id)");
        ensureIndex("worker_lease", "idx_worker_lease_status_heartbeat",
                "alter table worker_lease add key idx_worker_lease_status_heartbeat (status, last_heartbeat_at)");
        ensureIndex("studio_resource_share", "uk_studio_resource_share_target",
                "alter table studio_resource_share add unique key uk_studio_resource_share_target (resource_type, resource_id, target_project_id)");
        ensureIndex("studio_resource_share", "idx_studio_resource_share_project",
                "alter table studio_resource_share add key idx_studio_resource_share_project (target_project_id)");

        backfillProjectIdsMysql();
        backfillWorkerGroupColumnsMysql();
    }

    private void upgradeSqlite() {
        ensureColumn("sys_user", "auth_source", "alter table sys_user add column auth_source text default 'LOCAL'");
        ensureColumn("meta_field_definition", "searchable_flag", "alter table meta_field_definition add column searchable_flag integer default 0");
        ensureColumn("meta_field_definition", "sortable_flag", "alter table meta_field_definition add column sortable_flag integer default 0");
        ensureColumn("meta_field_definition", "query_operators", "alter table meta_field_definition add column query_operators text");
        ensureColumn("meta_field_definition", "query_default_operator", "alter table meta_field_definition add column query_default_operator text");
        ensureColumn("datasource_definition", "project_id", "alter table datasource_definition add column project_id integer");
        ensureColumn("data_model", "project_id", "alter table data_model add column project_id integer");
        ensureColumn("data_model_attr_index", "project_id", "alter table data_model_attr_index add column project_id integer");
        ensureColumn("workflow_definition", "project_id", "alter table workflow_definition add column project_id integer");
        ensureColumn("workflow_definition", "created_by", "alter table workflow_definition add column created_by integer");
        ensureColumn("workflow_definition_version", "project_id", "alter table workflow_definition_version add column project_id integer");
        ensureColumn("workflow_node", "project_id", "alter table workflow_node add column project_id integer");
        ensureColumn("workflow_edge", "project_id", "alter table workflow_edge add column project_id integer");
        ensureColumn("workflow_schedule", "project_id", "alter table workflow_schedule add column project_id integer");
        ensureColumn("collection_task_definition", "project_id", "alter table collection_task_definition add column project_id integer");
        ensureColumn("collection_task_definition", "created_by", "alter table collection_task_definition add column created_by integer");
        ensureColumn("collection_task_schedule", "project_id", "alter table collection_task_schedule add column project_id integer");
        ensureColumn("data_dev_directory", "project_id", "alter table data_dev_directory add column project_id integer");
        ensureColumn("data_dev_script", "project_id", "alter table data_dev_script add column project_id integer");
        ensureColumn("data_dev_script", "environment_id", "alter table data_dev_script add column environment_id integer");
        ensureColumn("dispatch_task", "execution_type", "alter table dispatch_task add column execution_type text");
        ensureColumn("dispatch_task", "workflow_run_id", "alter table dispatch_task add column workflow_run_id integer");
        ensureColumn("dispatch_task", "collection_task_id", "alter table dispatch_task add column collection_task_id integer");
        ensureColumn("dispatch_task", "quality_task_id", "alter table dispatch_task add column quality_task_id integer");
        ensureColumn("dispatch_task", "triggered_by_user_id", "alter table dispatch_task add column triggered_by_user_id integer");
        ensureColumn("dispatch_task", "run_record_id", "alter table dispatch_task add column run_record_id integer");
        ensureColumn("dispatch_task", "project_id", "alter table dispatch_task add column project_id integer");
        ensureColumn("dispatch_task", "worker_group_code", "alter table dispatch_task add column worker_group_code text");
        ensureColumn("dispatch_task", "worker_instance_id", "alter table dispatch_task add column worker_instance_id text");
        ensureColumn("dispatch_task", "scheduled_fire_time", "alter table dispatch_task add column scheduled_fire_time text");
        ensureColumn("workflow_schedule", "last_triggered_at", "alter table workflow_schedule add column last_triggered_at text");
        ensureColumn("run_record", "execution_type", "alter table run_record add column execution_type text");
        ensureColumn("run_record", "workflow_run_id", "alter table run_record add column workflow_run_id integer");
        ensureColumn("run_record", "collection_task_id", "alter table run_record add column collection_task_id integer");
        ensureColumn("run_record", "quality_task_id", "alter table run_record add column quality_task_id integer");
        ensureColumn("run_record", "triggered_by_user_id", "alter table run_record add column triggered_by_user_id integer");
        ensureColumn("run_record", "project_id", "alter table run_record add column project_id integer");
        ensureColumn("run_record", "worker_group_code", "alter table run_record add column worker_group_code text");
        ensureColumn("run_record", "worker_instance_id", "alter table run_record add column worker_instance_id text");
        ensureColumn("run_record", "worker_pod_name", "alter table run_record add column worker_pod_name text");
        ensureColumn("run_record", "worker_node_name", "alter table run_record add column worker_node_name text");
        ensureColumn("run_record", "log_file_path", "alter table run_record add column log_file_path text");
        ensureColumn("run_record", "log_size_bytes", "alter table run_record add column log_size_bytes integer");
        ensureColumn("run_record", "log_charset", "alter table run_record add column log_charset text");
        ensureColumn("run_record", "log_storage_type", "alter table run_record add column log_storage_type text");
        ensureColumn("run_record", "log_object_bucket", "alter table run_record add column log_object_bucket text");
        ensureColumn("run_record", "log_object_key", "alter table run_record add column log_object_key text");
        ensureColumn("run_record", "log_chunk_count", "alter table run_record add column log_chunk_count integer");
        ensureColumn("run_record", "log_status", "alter table run_record add column log_status text");
        ensureColumn("run_record", "log_error_summary", "alter table run_record add column log_error_summary text");
        ensureColumn("run_record", "collected_records", "alter table run_record add column collected_records integer");
        ensureColumn("run_record", "read_succeed_records", "alter table run_record add column read_succeed_records integer");
        ensureColumn("run_record", "read_failed_records", "alter table run_record add column read_failed_records integer");
        ensureColumn("run_record", "write_succeed_records", "alter table run_record add column write_succeed_records integer");
        ensureColumn("run_record", "write_failed_records", "alter table run_record add column write_failed_records integer");
        ensureColumn("run_record", "failed_records", "alter table run_record add column failed_records integer");
        ensureColumn("run_record", "success_records", "alter table run_record add column success_records integer");
        ensureColumn("run_record", "transformer_total_records", "alter table run_record add column transformer_total_records integer");
        ensureColumn("run_record", "transformer_success_records", "alter table run_record add column transformer_success_records integer");
        ensureColumn("run_record", "transformer_failed_records", "alter table run_record add column transformer_failed_records integer");
        ensureColumn("run_record", "transformer_filter_records", "alter table run_record add column transformer_filter_records integer");
        ensureColumn("studio_project_worker_binding", "worker_group_code", "alter table studio_project_worker_binding add column worker_group_code text");
        ensureColumn("worker_lease", "worker_group_code", "alter table worker_lease add column worker_group_code text");
        ensureColumn("worker_lease", "instance_id", "alter table worker_lease add column instance_id text");
        ensureColumn("worker_lease", "pod_name", "alter table worker_lease add column pod_name text");
        ensureColumn("worker_lease", "node_name", "alter table worker_lease add column node_name text");
        ensureColumn("worker_lease", "lease_expires_at", "alter table worker_lease add column lease_expires_at text");
        ensureClusterLockTableSqlite();
        ensureQualityTablesSqlite();
        ensureDataServiceTablesSqlite();
        ensureColumn("data_service_response_param", "transformers_json", "alter table data_service_response_param add column transformers_json text");
        ensureDataIngestionTablesSqlite();
        ensureProtocolConversionTablesSqlite();
        datasourceCapabilityUpgradeSupport.ensureDatasourceTypeCapabilityTablesSqlite();
        ensureOdpsFieldMetadataDefinitionsSqlite();
        ensureCurrentOdpsFieldMetadataDefinitions();

        jdbcTemplate.execute("create table if not exists data_model_attr_index (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "model_id integer," +
                "datasource_id integer," +
                "meta_schema_version_id integer," +
                "meta_schema_code text," +
                "scope text," +
                "meta_model_code text," +
                "item_key text," +
                "field_key text," +
                "value_type text," +
                "keyword_value text," +
                "text_value text," +
                "number_value numeric," +
                "bool_value integer," +
                "raw_value text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_model_attr_index_project on data_model_attr_index(project_id)");
        jdbcTemplate.execute("create index if not exists idx_model_attr_index_model on data_model_attr_index(model_id)");
        jdbcTemplate.execute("create index if not exists idx_model_attr_index_tenant_model_item on data_model_attr_index(tenant_id, model_id, item_key)");
        jdbcTemplate.execute("create index if not exists idx_model_attr_index_datasource on data_model_attr_index(datasource_id)");
        jdbcTemplate.execute("create index if not exists idx_model_attr_index_lookup on data_model_attr_index(meta_schema_code, scope, field_key, keyword_value)");
        jdbcTemplate.execute("create index if not exists idx_model_attr_index_number on data_model_attr_index(meta_schema_code, scope, field_key, number_value)");
        jdbcTemplate.execute("create index if not exists idx_model_attr_index_tenant_lookup on data_model_attr_index(tenant_id, meta_schema_code, scope, field_key, keyword_value)");
        jdbcTemplate.execute("create index if not exists idx_model_attr_index_tenant_number on data_model_attr_index(tenant_id, meta_schema_code, scope, field_key, number_value)");

        jdbcTemplate.execute("create table if not exists collection_task_definition (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "name text," +
                "task_type text," +
                "status text," +
                "source_count integer default 1," +
                "source_bindings_json text," +
                "target_binding_json text," +
                "field_mappings_json text," +
                "execution_options_json text" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_collection_task_definition_project_name on collection_task_definition(project_id, name)");
        jdbcTemplate.execute("create index if not exists idx_collection_task_definition_project on collection_task_definition(project_id)");
        jdbcTemplate.execute("create table if not exists collection_task_schedule (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "collection_task_id integer," +
                "cron_expression text," +
                "enabled integer default 0," +
                "timezone text," +
                "last_triggered_at text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_collection_task_schedule_project on collection_task_schedule(project_id)");

        jdbcTemplate.execute("create table if not exists model_sync_task (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "datasource_id integer not null," +
                "datasource_type text," +
                "datasource_name_snapshot text," +
                "batch_no integer not null," +
                "name text," +
                "source text," +
                "status text," +
                "total_count integer default 0," +
                "success_count integer default 0," +
                "failed_count integer default 0," +
                "stopped_count integer default 0," +
                "progress_percent integer default 0," +
                "stop_requested integer default 0," +
                "created_by integer," +
                "started_at text," +
                "finished_at text," +
                "duration_ms integer," +
                "last_error text" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_model_sync_task_project_datasource_batch on model_sync_task(project_id, datasource_id, batch_no)");
        jdbcTemplate.execute("create index if not exists idx_model_sync_task_project_created on model_sync_task(project_id, created_at)");
        jdbcTemplate.execute("create index if not exists idx_model_sync_task_project_status on model_sync_task(project_id, status)");

        jdbcTemplate.execute("create table if not exists model_sync_task_item (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "task_id integer not null," +
                "seq_no integer not null," +
                "physical_locator text," +
                "model_name_snapshot text," +
                "status text," +
                "message text," +
                "started_at text," +
                "finished_at text," +
                "duration_ms integer" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_model_sync_task_item_task_seq on model_sync_task_item(task_id, seq_no)");
        jdbcTemplate.execute("create index if not exists idx_model_sync_task_item_task_status on model_sync_task_item(task_id, status)");

        jdbcTemplate.execute("create table if not exists field_mapping_rule (" +
                "id integer primary key," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "mapping_name text not null," +
                "mapping_type text not null," +
                "mapping_code text not null," +
                "enabled integer default 1," +
                "description text," +
                "created_by integer" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_field_mapping_rule_type_enabled on field_mapping_rule(mapping_type, enabled)");
        jdbcTemplate.execute("create index if not exists idx_field_mapping_rule_created_at on field_mapping_rule(created_at)");
        jdbcTemplate.execute("create unique index if not exists uk_field_mapping_rule_code on field_mapping_rule(mapping_code)");

        jdbcTemplate.execute("create table if not exists field_mapping_rule_param (" +
                "id integer primary key," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "rule_id integer not null," +
                "param_name text not null," +
                "param_order integer not null," +
                "component_type text not null," +
                "param_value_json text," +
                "description text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_field_mapping_rule_param_rule_order on field_mapping_rule_param(rule_id, param_order)");
        jdbcTemplate.execute("create index if not exists idx_field_mapping_rule_param_rule_name on field_mapping_rule_param(rule_id, param_name)");

        jdbcTemplate.execute("create table if not exists user_registration_request (" +
                "id integer primary key," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "status text not null," +
                "username text not null," +
                "password_hash text not null," +
                "display_name text," +
                "reason text," +
                "review_comment text," +
                "reviewer_user_id integer," +
                "approved_user_id integer," +
                "reviewed_at text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_user_registration_request_status_created on user_registration_request(status, created_at)");
        jdbcTemplate.execute("create unique index if not exists uk_user_registration_request_username_status on user_registration_request(username, status)");

        jdbcTemplate.execute("create table if not exists studio_notification (" +
                "id integer primary key," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "recipient_user_id integer not null," +
                "tenant_id text," +
                "project_id integer," +
                "category text," +
                "title text," +
                "content text," +
                "target_type text," +
                "target_id integer," +
                "target_path text," +
                "target_tenant_id text," +
                "target_project_id integer," +
                "dedupe_key text," +
                "read_at text," +
                "archived_at text," +
                "payload_json text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_studio_notification_recipient_created on studio_notification(recipient_user_id, created_at)");
        jdbcTemplate.execute("create index if not exists idx_studio_notification_recipient_unread on studio_notification(recipient_user_id, read_at, archived_at)");
        jdbcTemplate.execute("create unique index if not exists uk_studio_notification_recipient_dedupe on studio_notification(recipient_user_id, dedupe_key)");

        jdbcTemplate.execute("create table if not exists studio_follow_subscription (" +
                "id integer primary key," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "user_id integer not null," +
                "target_type text not null," +
                "target_id integer not null," +
                "enabled integer default 1" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_studio_follow_subscription_target on studio_follow_subscription(tenant_id, project_id, user_id, target_type, target_id)");
        jdbcTemplate.execute("create index if not exists idx_studio_follow_subscription_lookup on studio_follow_subscription(target_type, target_id, enabled)");

        jdbcTemplate.execute("create table if not exists data_dev_directory (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "parent_id integer," +
                "name text," +
                "permission_code text," +
                "description text" +
                ")");
        jdbcTemplate.execute("create table if not exists data_dev_script (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "directory_id integer," +
                "file_name text," +
                "script_type text," +
                "datasource_id integer," +
                "environment_id integer," +
                "description text," +
                "content text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_data_dev_directory_project_parent on data_dev_directory(project_id, parent_id)");
        jdbcTemplate.execute("create index if not exists idx_data_dev_directory_parent on data_dev_directory(parent_id)");
        jdbcTemplate.execute("create index if not exists idx_data_dev_script_project_directory on data_dev_script(project_id, directory_id)");
        jdbcTemplate.execute("create index if not exists idx_data_dev_script_directory on data_dev_script(directory_id)");
        jdbcTemplate.execute("create index if not exists idx_data_dev_script_datasource on data_dev_script(datasource_id)");
        jdbcTemplate.execute("create index if not exists idx_data_dev_script_environment on data_dev_script(environment_id)");
        ensureScriptEnvironmentTablesSqlite();
        jdbcTemplate.execute("create unique index if not exists uk_datasource_definition_project_name on datasource_definition(project_id, name)");
        jdbcTemplate.execute("create index if not exists idx_datasource_definition_project on datasource_definition(project_id)");
        ensureDataModelNameUniqueIndexSqlite();
        jdbcTemplate.execute("create index if not exists idx_data_model_project on data_model(project_id)");
        jdbcTemplate.execute("create index if not exists idx_data_model_tenant_project_created on data_model(tenant_id, project_id, created_at)");
        jdbcTemplate.execute("create index if not exists idx_data_model_tenant_datasource_created on data_model(tenant_id, datasource_id, created_at)");
        jdbcTemplate.execute("create unique index if not exists uk_workflow_definition_project_code on workflow_definition(project_id, code)");
        jdbcTemplate.execute("create unique index if not exists uk_workflow_definition_project_name on workflow_definition(project_id, name)");
        jdbcTemplate.execute("create index if not exists idx_workflow_definition_project on workflow_definition(project_id)");
        jdbcTemplate.execute("create index if not exists idx_workflow_definition_version_project on workflow_definition_version(project_id)");
        jdbcTemplate.execute("create index if not exists idx_workflow_node_project on workflow_node(project_id)");
        jdbcTemplate.execute("create index if not exists idx_workflow_edge_project on workflow_edge(project_id)");
        jdbcTemplate.execute("create index if not exists idx_workflow_schedule_project on workflow_schedule(project_id)");
        jdbcTemplate.execute("create index if not exists idx_dispatch_task_project_status on dispatch_task(project_id, status)");
        jdbcTemplate.execute("create index if not exists idx_dispatch_task_project_workflow_run on dispatch_task(project_id, workflow_run_id)");
        jdbcTemplate.execute("create index if not exists idx_dispatch_task_project_quality_task_status on dispatch_task(project_id, quality_task_id, status)");
        jdbcTemplate.execute("create index if not exists idx_dispatch_task_project_status_created on dispatch_task(project_id, status, created_at)");
        jdbcTemplate.execute("create index if not exists idx_dispatch_task_group_status_created on dispatch_task(worker_group_code, status, created_at)");
        jdbcTemplate.execute("create index if not exists idx_run_record_project_created on run_record(project_id, created_at)");
        jdbcTemplate.execute("create index if not exists idx_run_record_project_workflow_run on run_record(project_id, workflow_run_id)");
        jdbcTemplate.execute("create index if not exists idx_run_record_project_collection_task_ended on run_record(project_id, collection_task_id, ended_at)");
        jdbcTemplate.execute("create index if not exists idx_run_record_project_quality_task_ended on run_record(project_id, quality_task_id, ended_at)");
        jdbcTemplate.execute("create table if not exists data_model_lineage_relation (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "level text," +
                "source_type text," +
                "collection_task_id integer," +
                "collection_task_name_snapshot text," +
                "source_datasource_id integer," +
                "source_datasource_name_snapshot text," +
                "source_datasource_type_snapshot text," +
                "source_database_name_snapshot text," +
                "source_host_snapshot text," +
                "source_port_snapshot text," +
                "source_model_id integer," +
                "source_model_name_snapshot text," +
                "source_model_locator_snapshot text," +
                "source_field_key text," +
                "target_datasource_id integer," +
                "target_datasource_name_snapshot text," +
                "target_datasource_type_snapshot text," +
                "target_database_name_snapshot text," +
                "target_host_snapshot text," +
                "target_port_snapshot text," +
                "target_model_id integer," +
                "target_model_name_snapshot text," +
                "target_model_locator_snapshot text," +
                    "target_field_key text," +
                    "mapping_mode text," +
                    "expression_snapshot text," +
                    "manual_maintainer_user_id integer," +
                    "manual_maintainer_name_snapshot text," +
                    "latest_run_id integer," +
                    "latest_run_status text," +
                    "latest_run_at text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_data_model_lineage_target_level on data_model_lineage_relation(tenant_id, target_model_id, level)");
        jdbcTemplate.execute("create index if not exists idx_data_model_lineage_source_level on data_model_lineage_relation(tenant_id, source_model_id, level)");
        jdbcTemplate.execute("create index if not exists idx_data_model_lineage_task on data_model_lineage_relation(tenant_id, collection_task_id)");
        jdbcTemplate.execute("create index if not exists idx_data_model_lineage_target_datasource_level on data_model_lineage_relation(tenant_id, target_datasource_id, level)");
        jdbcTemplate.execute("create index if not exists idx_data_model_lineage_source_datasource_level on data_model_lineage_relation(tenant_id, source_datasource_id, level)");

        jdbcTemplate.execute("create table if not exists studio_tenant (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "tenant_code text not null," +
                "tenant_name text not null," +
                "description text," +
                "enabled integer default 1" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_studio_tenant_code on studio_tenant(tenant_code)");

        jdbcTemplate.execute("create table if not exists studio_project (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "project_code text not null," +
                "project_name text not null," +
                "description text," +
                "enabled integer default 1," +
                "default_project integer default 0" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_studio_project_code on studio_project(tenant_id, project_code)");
        jdbcTemplate.execute("create unique index if not exists uk_studio_project_name on studio_project(tenant_id, project_name)");

        jdbcTemplate.execute("create table if not exists studio_tenant_member (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "user_id integer not null," +
                "role_code text not null," +
                "status text not null" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_studio_tenant_member_user on studio_tenant_member(tenant_id, user_id)");

        jdbcTemplate.execute("create table if not exists studio_project_member (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "project_id integer not null," +
                "user_id integer not null," +
                "role_code text not null," +
                "status text not null" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_studio_project_member_user on studio_project_member(project_id, user_id)");

        jdbcTemplate.execute("create table if not exists studio_project_member_request (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "project_id integer not null," +
                "user_id integer not null," +
                "request_type text not null," +
                "status text not null," +
                "inviter_user_id integer," +
                "reviewer_user_id integer," +
                "reason text," +
                "review_comment text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_studio_project_member_request_lookup on studio_project_member_request(project_id, user_id, status)");

        jdbcTemplate.execute("create table if not exists studio_project_worker_binding (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "project_id integer not null," +
                "worker_group_code text," +
                "worker_code text not null," +
                "enabled integer default 1" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_studio_project_worker_binding on studio_project_worker_binding(project_id, worker_code)");
        jdbcTemplate.execute("create index if not exists idx_studio_project_worker_group on studio_project_worker_binding(project_id, worker_group_code)");
        jdbcTemplate.execute("create index if not exists idx_worker_lease_code_instance on worker_lease(worker_code, instance_id)");
        jdbcTemplate.execute("create index if not exists idx_worker_lease_group_instance on worker_lease(worker_group_code, instance_id)");
        jdbcTemplate.execute("create index if not exists idx_worker_lease_status_heartbeat on worker_lease(status, last_heartbeat_at)");

        jdbcTemplate.execute("create table if not exists studio_resource_share (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "source_project_id integer not null," +
                "target_project_id integer not null," +
                "resource_type text not null," +
                "resource_id integer not null," +
                "shared_by_user_id integer," +
                "enabled integer default 1" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_studio_resource_share_target on studio_resource_share(resource_type, resource_id, target_project_id)");
        jdbcTemplate.execute("create index if not exists idx_studio_resource_share_project on studio_resource_share(target_project_id)");

        jdbcTemplate.execute("create unique index if not exists uk_datasource_definition_project_name on datasource_definition(project_id, name)");
        jdbcTemplate.execute("create index if not exists idx_datasource_definition_project on datasource_definition(project_id)");
        ensureDataModelNameUniqueIndexSqlite();
        jdbcTemplate.execute("create index if not exists idx_data_model_project on data_model(project_id)");
        jdbcTemplate.execute("create unique index if not exists uk_workflow_definition_project_code on workflow_definition(project_id, code)");
        jdbcTemplate.execute("create unique index if not exists uk_workflow_definition_project_name on workflow_definition(project_id, name)");
        jdbcTemplate.execute("create index if not exists idx_workflow_definition_project on workflow_definition(project_id)");
        jdbcTemplate.execute("create index if not exists idx_workflow_definition_version_project on workflow_definition_version(project_id)");
        jdbcTemplate.execute("create index if not exists idx_workflow_node_project on workflow_node(project_id)");
        jdbcTemplate.execute("create index if not exists idx_workflow_edge_project on workflow_edge(project_id)");
        jdbcTemplate.execute("create index if not exists idx_workflow_schedule_project on workflow_schedule(project_id)");
        jdbcTemplate.execute("create index if not exists idx_dispatch_task_project_status on dispatch_task(project_id, status)");
        jdbcTemplate.execute("create index if not exists idx_dispatch_task_project_workflow_run on dispatch_task(project_id, workflow_run_id)");
        jdbcTemplate.execute("create index if not exists idx_dispatch_task_project_quality_task_status on dispatch_task(project_id, quality_task_id, status)");
        jdbcTemplate.execute("create index if not exists idx_run_record_project_created on run_record(project_id, created_at)");
        jdbcTemplate.execute("create index if not exists idx_run_record_project_workflow_run on run_record(project_id, workflow_run_id)");
        jdbcTemplate.execute("create index if not exists idx_run_record_project_collection_task_ended on run_record(project_id, collection_task_id, ended_at)");
        jdbcTemplate.execute("create index if not exists idx_run_record_project_quality_task_ended on run_record(project_id, quality_task_id, ended_at)");

        backfillProjectIdsSqlite();
        backfillWorkerGroupColumnsSqlite();
    }

    private void backfillProjectIdsMysql() {
        backfillProjectIdMysql("datasource_definition");
        backfillProjectIdMysql("data_model");
        backfillProjectIdMysql("data_model_attr_index");
        backfillProjectIdMysql("collection_task_definition");
        backfillProjectIdMysql("collection_task_schedule");
        backfillProjectIdMysql("data_dev_directory");
        backfillProjectIdMysql("data_dev_script");
        backfillProjectIdMysql("workflow_definition");
        backfillProjectIdMysql("workflow_definition_version");
        backfillProjectIdMysql("workflow_node");
        backfillProjectIdMysql("workflow_edge");
        backfillProjectIdMysql("workflow_schedule");
        backfillProjectIdMysql("dispatch_task");
        backfillProjectIdMysql("run_record");
    }

    private void backfillProjectIdMysql(String tableName) {
        if (!tableExists(tableName) || !columnExists(tableName, "project_id") || !tableExists("studio_project")) {
            return;
        }
        jdbcTemplate.execute("update " + tableName + " resource " +
                "join studio_project project on project.tenant_id = resource.tenant_id and project.default_project = 1 " +
                "set resource.project_id = project.id where resource.project_id is null");
    }

    private void backfillProjectIdsSqlite() {
        backfillProjectIdSqlite("datasource_definition");
        backfillProjectIdSqlite("data_model");
        backfillProjectIdSqlite("data_model_attr_index");
        backfillProjectIdSqlite("collection_task_definition");
        backfillProjectIdSqlite("collection_task_schedule");
        backfillProjectIdSqlite("data_dev_directory");
        backfillProjectIdSqlite("data_dev_script");
        backfillProjectIdSqlite("workflow_definition");
        backfillProjectIdSqlite("workflow_definition_version");
        backfillProjectIdSqlite("workflow_node");
        backfillProjectIdSqlite("workflow_edge");
        backfillProjectIdSqlite("workflow_schedule");
        backfillProjectIdSqlite("dispatch_task");
        backfillProjectIdSqlite("run_record");
    }

    private void backfillProjectIdSqlite(String tableName) {
        if (!tableExists(tableName) || !columnExists(tableName, "project_id") || !tableExists("studio_project")) {
            return;
        }
        jdbcTemplate.execute("update " + tableName +
                " set project_id = (" +
                "select id from studio_project project " +
                "where project.tenant_id = " + tableName + ".tenant_id and project.default_project = 1 " +
                "limit 1" +
                ") where project_id is null");
    }

    private void backfillWorkerGroupColumnsMysql() {
        if (tableExists("studio_project_worker_binding") && columnExists("studio_project_worker_binding", "worker_group_code")) {
            jdbcTemplate.execute("update studio_project_worker_binding " +
                    "set worker_group_code = worker_code " +
                    "where (worker_group_code is null or worker_group_code = '') and worker_code is not null");
        }
        if (tableExists("worker_lease") && columnExists("worker_lease", "worker_group_code")) {
            jdbcTemplate.execute("update worker_lease " +
                    "set worker_group_code = worker_code " +
                    "where (worker_group_code is null or worker_group_code = '') and worker_code is not null");
        }
        if (tableExists("dispatch_task") && columnExists("dispatch_task", "worker_group_code")) {
            jdbcTemplate.execute("update dispatch_task " +
                    "set worker_group_code = lease_owner " +
                    "where (worker_group_code is null or worker_group_code = '') and lease_owner is not null");
        }
        if (tableExists("run_record") && columnExists("run_record", "worker_group_code")) {
            jdbcTemplate.execute("update run_record " +
                    "set worker_group_code = worker_code " +
                    "where (worker_group_code is null or worker_group_code = '') and worker_code is not null");
        }
    }

    private void backfillWorkerGroupColumnsSqlite() {
        if (tableExists("studio_project_worker_binding") && columnExists("studio_project_worker_binding", "worker_group_code")) {
            jdbcTemplate.execute("update studio_project_worker_binding " +
                    "set worker_group_code = worker_code " +
                    "where (worker_group_code is null or worker_group_code = '') and worker_code is not null");
        }
        if (tableExists("worker_lease") && columnExists("worker_lease", "worker_group_code")) {
            jdbcTemplate.execute("update worker_lease " +
                    "set worker_group_code = worker_code " +
                    "where (worker_group_code is null or worker_group_code = '') and worker_code is not null");
        }
        if (tableExists("dispatch_task") && columnExists("dispatch_task", "worker_group_code")) {
            jdbcTemplate.execute("update dispatch_task " +
                    "set worker_group_code = lease_owner " +
                    "where (worker_group_code is null or worker_group_code = '') and lease_owner is not null");
        }
        if (tableExists("run_record") && columnExists("run_record", "worker_group_code")) {
            jdbcTemplate.execute("update run_record " +
                    "set worker_group_code = worker_code " +
                    "where (worker_group_code is null or worker_group_code = '') and worker_code is not null");
        }
    }

    private void ensureOdpsFieldMetadataDefinitionsMysql() {
        if (!tableExists("meta_field_definition")) {
            return;
        }
        upsertOdpsFieldMetadataDefinitionMysql(2047489211925291011L, "name", "字段名", "STRING", "INPUT", 1, 0, 10, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionMysql(2047489211925291012L, "type", "字段类型", "STRING", "INPUT", 0, 0, 20, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionMysql(2047489211925291013L, "size", "长度", "INTEGER", "NUMBER", 0, 0, 30, null, 1, 1, "[\"EQ\", \"GT\", \"GE\", \"LT\", \"LE\", \"BETWEEN\", \"IN\"]", "EQ", "[]");
        upsertOdpsFieldMetadataDefinitionMysql(2047489211925291014L, "scale", "精度", "INTEGER", "NUMBER", 0, 0, 40, null, 1, 1, "[\"EQ\", \"GT\", \"GE\", \"LT\", \"LE\", \"BETWEEN\", \"IN\"]", "EQ", "[]");
        upsertOdpsFieldMetadataDefinitionMysql(2047489211925291015L, "nullable", "是否可空", "STRING", "INPUT", 0, 0, 50, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionMysql(2047489211925291016L, "primaryKey", "是否主键", "STRING", "INPUT", 0, 0, 60, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionMysql(2047489211925291017L, "autoIncrement", "是否自增", "STRING", "INPUT", 0, 0, 70, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionMysql(2047489211925291018L, "remarks", "备注", "STRING", "TEXTAREA", 0, 0, 80, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionMysql(2047489211925291019L, "defaultValue", "默认值", "STRING", "INPUT", 0, 0, 90, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionMysql(2047489211925291020L, "partitionColumn", "是否分区字段", "BOOLEAN", "SWITCH", 0, 0, 100, "false", 1, 1, "[\"EQ\"]", "EQ", "[]");
    }

    private void upsertOdpsFieldMetadataDefinitionMysql(long id,
                                                        String fieldKey,
                                                        String fieldName,
                                                        String valueType,
                                                        String componentType,
                                                        int requiredFlag,
                                                        int sensitiveFlag,
                                                        int sortOrder,
                                                        String defaultValue,
                                                        int searchableFlag,
                                                        int sortableFlag,
                                                        String queryOperators,
                                                        String queryDefaultOperator,
                                                        String options) {
        jdbcTemplate.update("insert into meta_field_definition (" +
                        "id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options" +
                        ") values (?, 'default', 0, current_timestamp, current_timestamp, 2047489211925291010, ?, ?, ?, 'TECHNICAL', ?, ?, ?, ?, ?, null, null, ?, ?, ?, ?, ?, ?) " +
                        "on duplicate key update " +
                        "tenant_id = values(tenant_id), deleted = values(deleted), updated_at = values(updated_at), schema_version_id = values(schema_version_id), field_key = values(field_key), field_name = values(field_name), description = values(description), scope = values(scope), value_type = values(value_type), component_type = values(component_type), required_flag = values(required_flag), sensitive_flag = values(sensitive_flag), sort_order = values(sort_order), validation_rule = values(validation_rule), placeholder = values(placeholder), default_value = values(default_value), searchable_flag = values(searchable_flag), sortable_flag = values(sortable_flag), query_operators = values(query_operators), query_default_operator = values(query_default_operator), options = values(options)",
                Long.valueOf(id),
                fieldKey,
                fieldName,
                fieldName,
                valueType,
                componentType,
                Integer.valueOf(requiredFlag),
                Integer.valueOf(sensitiveFlag),
                Integer.valueOf(sortOrder),
                defaultValue,
                Integer.valueOf(searchableFlag),
                Integer.valueOf(sortableFlag),
                queryOperators,
                queryDefaultOperator,
                options);
    }

    private void ensureOdpsFieldMetadataDefinitionsSqlite() {
        if (!tableExists("meta_field_definition")) {
            return;
        }
        upsertOdpsFieldMetadataDefinitionSqlite(2047489211925291011L, "name", "字段名", "STRING", "INPUT", 1, 0, 10, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionSqlite(2047489211925291012L, "type", "字段类型", "STRING", "INPUT", 0, 0, 20, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionSqlite(2047489211925291013L, "size", "长度", "INTEGER", "NUMBER", 0, 0, 30, null, 1, 1, "[\"EQ\", \"GT\", \"GE\", \"LT\", \"LE\", \"BETWEEN\", \"IN\"]", "EQ", "[]");
        upsertOdpsFieldMetadataDefinitionSqlite(2047489211925291014L, "scale", "精度", "INTEGER", "NUMBER", 0, 0, 40, null, 1, 1, "[\"EQ\", \"GT\", \"GE\", \"LT\", \"LE\", \"BETWEEN\", \"IN\"]", "EQ", "[]");
        upsertOdpsFieldMetadataDefinitionSqlite(2047489211925291015L, "nullable", "是否可空", "STRING", "INPUT", 0, 0, 50, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionSqlite(2047489211925291016L, "primaryKey", "是否主键", "STRING", "INPUT", 0, 0, 60, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionSqlite(2047489211925291017L, "autoIncrement", "是否自增", "STRING", "INPUT", 0, 0, 70, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionSqlite(2047489211925291018L, "remarks", "备注", "STRING", "TEXTAREA", 0, 0, 80, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionSqlite(2047489211925291019L, "defaultValue", "默认值", "STRING", "INPUT", 0, 0, 90, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionSqlite(2047489211925291020L, "partitionColumn", "是否分区字段", "BOOLEAN", "SWITCH", 0, 0, 100, "false", 1, 1, "[\"EQ\"]", "EQ", "[]");
    }

    private void upsertOdpsFieldMetadataDefinitionSqlite(long id,
                                                         String fieldKey,
                                                         String fieldName,
                                                         String valueType,
                                                         String componentType,
                                                         int requiredFlag,
                                                         int sensitiveFlag,
                                                         int sortOrder,
                                                         String defaultValue,
                                                         int searchableFlag,
                                                         int sortableFlag,
                                                         String queryOperators,
                                                         String queryDefaultOperator,
                                                         String options) {
        jdbcTemplate.update("insert or replace into meta_field_definition (" +
                        "id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options" +
                        ") values (?, 'default', 0, datetime('now'), datetime('now'), 2047489211925291010, ?, ?, ?, 'TECHNICAL', ?, ?, ?, ?, ?, null, null, ?, ?, ?, ?, ?, ?)",
                Long.valueOf(id),
                fieldKey,
                fieldName,
                fieldName,
                valueType,
                componentType,
                Integer.valueOf(requiredFlag),
                Integer.valueOf(sensitiveFlag),
                Integer.valueOf(sortOrder),
                defaultValue,
                Integer.valueOf(searchableFlag),
                Integer.valueOf(sortableFlag),
                queryOperators,
                queryDefaultOperator,
                options);
    }

    private void ensureCurrentOdpsFieldMetadataDefinitions() {
        if (!tableExists("meta_schema") || !tableExists("meta_field_definition")) {
            return;
        }
        List<Long> versionIds = jdbcTemplate.queryForList(
                "select current_version_id from meta_schema where schema_code = 'technical:odps:field' and current_version_id is not null",
                Long.class);
        for (Long versionId : versionIds) {
            ensureOdpsFieldMetadataDefinitionsForVersion(versionId);
        }
    }

    private void ensureOdpsFieldMetadataDefinitionsForVersion(Long versionId) {
        if (versionId == null) {
            return;
        }
        upsertOdpsFieldMetadataDefinitionForVersion(versionId, "name", "字段名", "STRING", "INPUT", 1, 0, 10, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionForVersion(versionId, "type", "字段类型", "STRING", "INPUT", 0, 0, 20, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionForVersion(versionId, "size", "长度", "INTEGER", "NUMBER", 0, 0, 30, null, 1, 1, "[\"EQ\", \"GT\", \"GE\", \"LT\", \"LE\", \"BETWEEN\", \"IN\"]", "EQ", "[]");
        upsertOdpsFieldMetadataDefinitionForVersion(versionId, "scale", "精度", "INTEGER", "NUMBER", 0, 0, 40, null, 1, 1, "[\"EQ\", \"GT\", \"GE\", \"LT\", \"LE\", \"BETWEEN\", \"IN\"]", "EQ", "[]");
        upsertOdpsFieldMetadataDefinitionForVersion(versionId, "nullable", "是否可空", "STRING", "INPUT", 0, 0, 50, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionForVersion(versionId, "primaryKey", "是否主键", "STRING", "INPUT", 0, 0, 60, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionForVersion(versionId, "autoIncrement", "是否自增", "STRING", "INPUT", 0, 0, 70, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionForVersion(versionId, "remarks", "备注", "STRING", "TEXTAREA", 0, 0, 80, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionForVersion(versionId, "defaultValue", "默认值", "STRING", "INPUT", 0, 0, 90, null, 1, 1, "[\"EQ\", \"LIKE\", \"IN\"]", "LIKE", "[]");
        upsertOdpsFieldMetadataDefinitionForVersion(versionId, "partitionColumn", "是否分区字段", "BOOLEAN", "SWITCH", 0, 0, 100, "false", 1, 1, "[\"EQ\"]", "EQ", "[]");
        jdbcTemplate.update("delete from meta_field_definition where schema_version_id = ? and field_key = 'sourceType'",
                versionId);
    }

    private void upsertOdpsFieldMetadataDefinitionForVersion(Long versionId,
                                                             String fieldKey,
                                                             String fieldName,
                                                             String valueType,
                                                             String componentType,
                                                             int requiredFlag,
                                                             int sensitiveFlag,
                                                             int sortOrder,
                                                             String defaultValue,
                                                             int searchableFlag,
                                                             int sortableFlag,
                                                             String queryOperators,
                                                             String queryDefaultOperator,
                                                             String options) {
        Long id = findMetaFieldDefinitionId(versionId, fieldKey);
        if (id == null && "partitionColumn".equals(fieldKey)) {
            id = findMetaFieldDefinitionId(versionId, "sourceType");
        }
        if (id == null) {
            jdbcTemplate.update("insert into meta_field_definition (" +
                            "id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options" +
                            ") values (?, 'default', 0, current_timestamp, current_timestamp, ?, ?, ?, ?, 'TECHNICAL', ?, ?, ?, ?, ?, null, null, ?, ?, ?, ?, ?, ?)",
                    Long.valueOf(generatedMetaFieldDefinitionId(versionId, fieldKey)),
                    versionId,
                    fieldKey,
                    fieldName,
                    fieldName,
                    valueType,
                    componentType,
                    Integer.valueOf(requiredFlag),
                    Integer.valueOf(sensitiveFlag),
                    Integer.valueOf(sortOrder),
                    defaultValue,
                    Integer.valueOf(searchableFlag),
                    Integer.valueOf(sortableFlag),
                    queryOperators,
                    queryDefaultOperator,
                    options);
            return;
        }
        jdbcTemplate.update("update meta_field_definition set " +
                        "tenant_id = 'default', deleted = 0, updated_at = current_timestamp, schema_version_id = ?, field_key = ?, field_name = ?, description = ?, scope = 'TECHNICAL', value_type = ?, component_type = ?, required_flag = ?, sensitive_flag = ?, sort_order = ?, validation_rule = null, placeholder = null, default_value = ?, searchable_flag = ?, sortable_flag = ?, query_operators = ?, query_default_operator = ?, options = ? " +
                        "where id = ?",
                versionId,
                fieldKey,
                fieldName,
                fieldName,
                valueType,
                componentType,
                Integer.valueOf(requiredFlag),
                Integer.valueOf(sensitiveFlag),
                Integer.valueOf(sortOrder),
                defaultValue,
                Integer.valueOf(searchableFlag),
                Integer.valueOf(sortableFlag),
                queryOperators,
                queryDefaultOperator,
                options,
                id);
    }

    private Long findMetaFieldDefinitionId(Long versionId, String fieldKey) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from meta_field_definition where schema_version_id = ? and field_key = ? order by id limit 1",
                Long.class,
                versionId,
                fieldKey);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long generatedMetaFieldDefinitionId(Long versionId, String fieldKey) {
        UUID uuid = UUID.nameUUIDFromBytes(("meta_field_definition|odps|" + versionId + "|" + fieldKey)
                .getBytes(StandardCharsets.UTF_8));
        long value = uuid.getMostSignificantBits() & Long.MAX_VALUE;
        if (value == 0L) {
            value = uuid.getLeastSignificantBits() & Long.MAX_VALUE;
        }
        return value == 0L ? 1L : value;
    }

    private void ensureColumn(String tableName, String columnName, String ddl) {
        schemaIntrospector.ensureColumn(tableName, columnName, ddl);
    }

    private void ensureInvocationLogArchiveColumnsMysql(String tableName) {
        ensureColumn(tableName, "log_storage_type",
                "alter table " + tableName + " add column log_storage_type varchar(64)");
        ensureColumn(tableName, "log_object_bucket",
                "alter table " + tableName + " add column log_object_bucket varchar(255)");
        ensureColumn(tableName, "log_object_key",
                "alter table " + tableName + " add column log_object_key varchar(1000)");
        ensureColumn(tableName, "log_size_bytes",
                "alter table " + tableName + " add column log_size_bytes bigint");
        ensureColumn(tableName, "log_charset",
                "alter table " + tableName + " add column log_charset varchar(64)");
        ensureColumn(tableName, "log_archive_status",
                "alter table " + tableName + " add column log_archive_status varchar(64)");
        ensureColumn(tableName, "log_archive_error",
                "alter table " + tableName + " add column log_archive_error varchar(1000)");
    }

    private void ensureInvocationLogArchiveColumnsSqlite(String tableName) {
        ensureColumn(tableName, "log_storage_type",
                "alter table " + tableName + " add column log_storage_type text");
        ensureColumn(tableName, "log_object_bucket",
                "alter table " + tableName + " add column log_object_bucket text");
        ensureColumn(tableName, "log_object_key",
                "alter table " + tableName + " add column log_object_key text");
        ensureColumn(tableName, "log_size_bytes",
                "alter table " + tableName + " add column log_size_bytes integer");
        ensureColumn(tableName, "log_charset",
                "alter table " + tableName + " add column log_charset text");
        ensureColumn(tableName, "log_archive_status",
                "alter table " + tableName + " add column log_archive_status text");
        ensureColumn(tableName, "log_archive_error",
                "alter table " + tableName + " add column log_archive_error text");
    }

    private void ensureQualityTablesMysql() {
        if (!tableExists("quality_rule")) {
            jdbcTemplate.execute("create table quality_rule (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "created_by bigint," +
                    "rule_name varchar(255) not null," +
                    "rule_code varchar(255) not null," +
                    "scope_type varchar(32) not null," +
                    "rule_dimension varchar(64) not null," +
                    "description varchar(2000)," +
                    "supported_datasource_types_json json," +
                    "granularity varchar(32) not null," +
                    "logic_sql text," +
                    "enabled int default 1" +
                    ")");
        }
        ensureIndex("quality_rule", "uk_quality_rule_scope_code",
                "alter table quality_rule add unique key uk_quality_rule_scope_code (tenant_id, project_id, scope_type, rule_code)");
        ensureIndex("quality_rule", "idx_quality_rule_scope_enabled",
                "alter table quality_rule add key idx_quality_rule_scope_enabled (tenant_id, project_id, scope_type, enabled)");
        ensureIndex("quality_rule", "idx_quality_rule_dimension_enabled",
                "alter table quality_rule add key idx_quality_rule_dimension_enabled (rule_dimension, enabled)");

        if (!tableExists("quality_rule_input_param")) {
            jdbcTemplate.execute("create table quality_rule_input_param (" +
                    "id bigint primary key," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "rule_id bigint not null," +
                    "param_order int not null," +
                    "param_name varchar(255) not null," +
                    "param_type varchar(32) not null," +
                    "param_meaning varchar(1000)" +
                    ")");
        }
        ensureIndex("quality_rule_input_param", "idx_quality_rule_input_param_rule_order",
                "alter table quality_rule_input_param add key idx_quality_rule_input_param_rule_order (rule_id, param_order)");
        ensureIndex("quality_rule_input_param", "idx_quality_rule_input_param_rule_name",
                "alter table quality_rule_input_param add key idx_quality_rule_input_param_rule_name (rule_id, param_name)");

        if (!tableExists("quality_rule_output_param")) {
            jdbcTemplate.execute("create table quality_rule_output_param (" +
                    "id bigint primary key," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "rule_id bigint not null," +
                    "output_order int not null," +
                    "result_field varchar(255) not null," +
                    "output_type varchar(32) not null," +
                    "output_description varchar(1000)" +
                    ")");
        }
        ensureIndex("quality_rule_output_param", "idx_quality_rule_output_param_rule_order",
                "alter table quality_rule_output_param add key idx_quality_rule_output_param_rule_order (rule_id, output_order)");
        ensureIndex("quality_rule_output_param", "idx_quality_rule_output_param_rule_field",
                "alter table quality_rule_output_param add key idx_quality_rule_output_param_rule_field (rule_id, result_field)");

        if (!tableExists("quality_task_definition")) {
            jdbcTemplate.execute("create table quality_task_definition (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "created_by bigint," +
                    "task_name varchar(255) not null," +
                    "task_code varchar(255) not null," +
                    "status varchar(64)," +
                    "rule_id bigint not null," +
                    "rule_name_snapshot varchar(255)," +
                    "rule_dimension varchar(64)," +
                    "granularity varchar(32)," +
                    "datasource_id bigint," +
                    "datasource_name_snapshot varchar(255)," +
                    "datasource_type_code varchar(128)," +
                    "model_id bigint," +
                    "model_name_snapshot varchar(255)," +
                    "model_physical_locator varchar(500)," +
                    "column_name varchar(255)," +
                    "where_clause text," +
                    "resolved_sql_preview text," +
                    "parameter_bindings_json json," +
                    "rule_snapshot_json json" +
                    ")");
        }
        ensureIndex("quality_task_definition", "uk_quality_task_definition_project_code",
                "alter table quality_task_definition add unique key uk_quality_task_definition_project_code (project_id, task_code)");
        ensureIndex("quality_task_definition", "uk_quality_task_definition_project_name",
                "alter table quality_task_definition add unique key uk_quality_task_definition_project_name (project_id, task_name)");
        ensureIndex("quality_task_definition", "idx_quality_task_definition_project_status",
                "alter table quality_task_definition add key idx_quality_task_definition_project_status (project_id, status)");
        ensureIndex("quality_task_definition", "idx_quality_task_definition_rule_dimension",
                "alter table quality_task_definition add key idx_quality_task_definition_rule_dimension (project_id, rule_dimension)");

        if (!tableExists("quality_task_schedule")) {
            jdbcTemplate.execute("create table quality_task_schedule (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "quality_task_id bigint," +
                    "cron_expression varchar(255)," +
                    "enabled int default 0," +
                    "timezone varchar(64)," +
                    "last_triggered_at datetime" +
                    ")");
        }
        ensureIndex("quality_task_schedule", "idx_quality_task_schedule_project",
                "alter table quality_task_schedule add key idx_quality_task_schedule_project (project_id)");
        ensureIndex("quality_task_schedule", "idx_quality_task_schedule_task",
                "alter table quality_task_schedule add key idx_quality_task_schedule_task (quality_task_id)");

        if (!tableExists("quality_task_alert")) {
            jdbcTemplate.execute("create table quality_task_alert (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "quality_task_id bigint not null," +
                    "output_order int not null," +
                    "result_field varchar(255) not null," +
                    "output_type varchar(32) not null," +
                    "enabled int default 0," +
                    "operator varchar(32)," +
                    "expected_value varchar(255)," +
                    "min_value varchar(255)," +
                    "max_value varchar(255)" +
                    ")");
        }
        ensureIndex("quality_task_alert", "idx_quality_task_alert_task",
                "alter table quality_task_alert add key idx_quality_task_alert_task (quality_task_id)");
        ensureIndex("quality_task_alert", "idx_quality_task_alert_task_order",
                "alter table quality_task_alert add key idx_quality_task_alert_task_order (quality_task_id, output_order)");

        if (!tableExists("quality_issue")) {
            jdbcTemplate.execute("create table quality_issue (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "issue_code varchar(64)," +
                    "signature varchar(1024) not null," +
                    "issue_type varchar(64)," +
                    "quality_task_id bigint," +
                    "quality_task_name_snapshot varchar(255)," +
                    "rule_id bigint," +
                    "rule_name_snapshot varchar(255)," +
                    "rule_dimension varchar(64)," +
                    "datasource_id bigint," +
                    "datasource_name_snapshot varchar(255)," +
                    "datasource_type_code varchar(128)," +
                    "model_id bigint," +
                    "model_name_snapshot varchar(255)," +
                    "model_physical_locator varchar(500)," +
                    "column_name varchar(255)," +
                    "output_field varchar(255)," +
                    "granularity varchar(32)," +
                    "title varchar(255)," +
                    "latest_message varchar(2000)," +
                    "severity varchar(32)," +
                    "system_severity varchar(32)," +
                    "manual_severity varchar(32)," +
                    "status varchar(32)," +
                    "assignee_user_id bigint," +
                    "assignee_name_snapshot varchar(255)," +
                    "first_seen_at datetime," +
                    "last_seen_at datetime," +
                    "last_recovery_at datetime," +
                    "sla_due_at datetime," +
                    "occurrence_count int default 0," +
                    "consecutive_failure_count int default 0," +
                    "reopen_count int default 0," +
                    "last_run_record_id bigint," +
                    "last_run_status varchar(32)," +
                    "current_evidence_json json" +
                    ")");
        }
        ensureIndex("quality_issue", "uk_quality_issue_signature",
                "alter table quality_issue add unique key uk_quality_issue_signature (tenant_id, project_id, signature(255))");
        ensureIndex("quality_issue", "idx_quality_issue_status_severity",
                "alter table quality_issue add key idx_quality_issue_status_severity (project_id, status, severity)");
        ensureIndex("quality_issue", "idx_quality_issue_asset",
                "alter table quality_issue add key idx_quality_issue_asset (project_id, datasource_id, model_id)");
        ensureIndex("quality_issue", "idx_quality_issue_task",
                "alter table quality_issue add key idx_quality_issue_task (project_id, quality_task_id)");
        ensureIndex("quality_issue", "idx_quality_issue_last_seen",
                "alter table quality_issue add key idx_quality_issue_last_seen (project_id, last_seen_at)");

        if (!tableExists("quality_issue_comment")) {
            jdbcTemplate.execute("create table quality_issue_comment (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "issue_id bigint not null," +
                    "author_user_id bigint," +
                    "author_name_snapshot varchar(255)," +
                    "content text" +
                    ")");
        }
        ensureIndex("quality_issue_comment", "idx_quality_issue_comment_issue",
                "alter table quality_issue_comment add key idx_quality_issue_comment_issue (issue_id, created_at)");

        if (!tableExists("quality_issue_event")) {
            jdbcTemplate.execute("create table quality_issue_event (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "issue_id bigint not null," +
                    "event_type varchar(64)," +
                    "event_title varchar(255)," +
                    "event_message text," +
                    "actor_user_id bigint," +
                    "actor_name_snapshot varchar(255)," +
                    "metadata_json json" +
                    ")");
        }
        ensureIndex("quality_issue_event", "idx_quality_issue_event_issue",
                "alter table quality_issue_event add key idx_quality_issue_event_issue (issue_id, created_at)");
        ensureIndex("quality_issue_event", "idx_quality_issue_event_project_type",
                "alter table quality_issue_event add key idx_quality_issue_event_project_type (project_id, event_type, created_at)");

        if (!tableExists("quality_metric_snapshot")) {
            jdbcTemplate.execute("create table quality_metric_snapshot (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "snapshot_date date," +
                    "datasource_id bigint," +
                    "datasource_name_snapshot varchar(255)," +
                    "datasource_type_code varchar(128)," +
                    "model_id bigint," +
                    "model_name_snapshot varchar(255)," +
                    "model_physical_locator varchar(500)," +
                    "rule_dimension varchar(64)," +
                    "execution_health_score bigint," +
                    "governance_risk_score bigint," +
                    "active_issue_count bigint," +
                    "overdue_issue_count bigint," +
                    "coverage_rate bigint," +
                    "failure_rate bigint," +
                    "affected_asset_count bigint," +
                    "reopen_rate bigint" +
                    ")");
        }
        ensureIndex("quality_metric_snapshot", "idx_quality_metric_snapshot_project_date",
                "alter table quality_metric_snapshot add key idx_quality_metric_snapshot_project_date (project_id, snapshot_date)");
        ensureIndex("quality_metric_snapshot", "idx_quality_metric_snapshot_asset",
                "alter table quality_metric_snapshot add key idx_quality_metric_snapshot_asset (project_id, datasource_id, model_id, snapshot_date)");
        ensureIndex("quality_metric_snapshot", "idx_quality_metric_snapshot_dimension",
                "alter table quality_metric_snapshot add key idx_quality_metric_snapshot_dimension (project_id, rule_dimension, snapshot_date)");
    }

    private void ensureQualityTablesSqlite() {
        jdbcTemplate.execute("create table if not exists quality_rule (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "created_by integer," +
                "rule_name text not null," +
                "rule_code text not null," +
                "scope_type text not null," +
                "rule_dimension text not null," +
                "description text," +
                "supported_datasource_types_json text," +
                "granularity text not null," +
                "logic_sql text," +
                "enabled integer default 1" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_quality_rule_scope_code on quality_rule(tenant_id, project_id, scope_type, rule_code)");
        jdbcTemplate.execute("create index if not exists idx_quality_rule_scope_enabled on quality_rule(tenant_id, project_id, scope_type, enabled)");
        jdbcTemplate.execute("create index if not exists idx_quality_rule_dimension_enabled on quality_rule(rule_dimension, enabled)");

        jdbcTemplate.execute("create table if not exists quality_rule_input_param (" +
                "id integer primary key," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "rule_id integer not null," +
                "param_order integer not null," +
                "param_name text not null," +
                "param_type text not null," +
                "param_meaning text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_quality_rule_input_param_rule_order on quality_rule_input_param(rule_id, param_order)");
        jdbcTemplate.execute("create index if not exists idx_quality_rule_input_param_rule_name on quality_rule_input_param(rule_id, param_name)");

        jdbcTemplate.execute("create table if not exists quality_rule_output_param (" +
                "id integer primary key," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "rule_id integer not null," +
                "output_order integer not null," +
                "result_field text not null," +
                "output_type text not null," +
                "output_description text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_quality_rule_output_param_rule_order on quality_rule_output_param(rule_id, output_order)");
        jdbcTemplate.execute("create index if not exists idx_quality_rule_output_param_rule_field on quality_rule_output_param(rule_id, result_field)");

        jdbcTemplate.execute("create table if not exists quality_task_definition (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "created_by integer," +
                "task_name text not null," +
                "task_code text not null," +
                "status text," +
                "rule_id integer not null," +
                "rule_name_snapshot text," +
                "rule_dimension text," +
                "granularity text," +
                "datasource_id integer," +
                "datasource_name_snapshot text," +
                "datasource_type_code text," +
                "model_id integer," +
                "model_name_snapshot text," +
                "model_physical_locator text," +
                "column_name text," +
                "where_clause text," +
                "resolved_sql_preview text," +
                "parameter_bindings_json text," +
                "rule_snapshot_json text" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_quality_task_definition_project_code on quality_task_definition(project_id, task_code)");
        jdbcTemplate.execute("create unique index if not exists uk_quality_task_definition_project_name on quality_task_definition(project_id, task_name)");
        jdbcTemplate.execute("create index if not exists idx_quality_task_definition_project_status on quality_task_definition(project_id, status)");
        jdbcTemplate.execute("create index if not exists idx_quality_task_definition_rule_dimension on quality_task_definition(project_id, rule_dimension)");

        jdbcTemplate.execute("create table if not exists quality_task_schedule (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "quality_task_id integer," +
                "cron_expression text," +
                "enabled integer default 0," +
                "timezone text," +
                "last_triggered_at text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_quality_task_schedule_project on quality_task_schedule(project_id)");
        jdbcTemplate.execute("create index if not exists idx_quality_task_schedule_task on quality_task_schedule(quality_task_id)");

        jdbcTemplate.execute("create table if not exists quality_task_alert (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "quality_task_id integer not null," +
                "output_order integer not null," +
                "result_field text not null," +
                "output_type text not null," +
                "enabled integer default 0," +
                "operator text," +
                "expected_value text," +
                "min_value text," +
                "max_value text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_quality_task_alert_task on quality_task_alert(quality_task_id)");
        jdbcTemplate.execute("create index if not exists idx_quality_task_alert_task_order on quality_task_alert(quality_task_id, output_order)");

        jdbcTemplate.execute("create table if not exists quality_issue (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "issue_code text," +
                "signature text not null," +
                "issue_type text," +
                "quality_task_id integer," +
                "quality_task_name_snapshot text," +
                "rule_id integer," +
                "rule_name_snapshot text," +
                "rule_dimension text," +
                "datasource_id integer," +
                "datasource_name_snapshot text," +
                "datasource_type_code text," +
                "model_id integer," +
                "model_name_snapshot text," +
                "model_physical_locator text," +
                "column_name text," +
                "output_field text," +
                "granularity text," +
                "title text," +
                "latest_message text," +
                "severity text," +
                "system_severity text," +
                "manual_severity text," +
                "status text," +
                "assignee_user_id integer," +
                "assignee_name_snapshot text," +
                "first_seen_at text," +
                "last_seen_at text," +
                "last_recovery_at text," +
                "sla_due_at text," +
                "occurrence_count integer default 0," +
                "consecutive_failure_count integer default 0," +
                "reopen_count integer default 0," +
                "last_run_record_id integer," +
                "last_run_status text," +
                "current_evidence_json text" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_quality_issue_signature on quality_issue(tenant_id, project_id, signature)");
        jdbcTemplate.execute("create index if not exists idx_quality_issue_status_severity on quality_issue(project_id, status, severity)");
        jdbcTemplate.execute("create index if not exists idx_quality_issue_asset on quality_issue(project_id, datasource_id, model_id)");
        jdbcTemplate.execute("create index if not exists idx_quality_issue_task on quality_issue(project_id, quality_task_id)");
        jdbcTemplate.execute("create index if not exists idx_quality_issue_last_seen on quality_issue(project_id, last_seen_at)");

        jdbcTemplate.execute("create table if not exists quality_issue_comment (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "issue_id integer not null," +
                "author_user_id integer," +
                "author_name_snapshot text," +
                "content text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_quality_issue_comment_issue on quality_issue_comment(issue_id, created_at)");

        jdbcTemplate.execute("create table if not exists quality_issue_event (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "issue_id integer not null," +
                "event_type text," +
                "event_title text," +
                "event_message text," +
                "actor_user_id integer," +
                "actor_name_snapshot text," +
                "metadata_json text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_quality_issue_event_issue on quality_issue_event(issue_id, created_at)");
        jdbcTemplate.execute("create index if not exists idx_quality_issue_event_project_type on quality_issue_event(project_id, event_type, created_at)");

        jdbcTemplate.execute("create table if not exists quality_metric_snapshot (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "snapshot_date text," +
                "datasource_id integer," +
                "datasource_name_snapshot text," +
                "datasource_type_code text," +
                "model_id integer," +
                "model_name_snapshot text," +
                "model_physical_locator text," +
                "rule_dimension text," +
                "execution_health_score integer," +
                "governance_risk_score integer," +
                "active_issue_count integer," +
                "overdue_issue_count integer," +
                "coverage_rate integer," +
                "failure_rate integer," +
                "affected_asset_count integer," +
                "reopen_rate integer" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_quality_metric_snapshot_project_date on quality_metric_snapshot(project_id, snapshot_date)");
        jdbcTemplate.execute("create index if not exists idx_quality_metric_snapshot_asset on quality_metric_snapshot(project_id, datasource_id, model_id, snapshot_date)");
        jdbcTemplate.execute("create index if not exists idx_quality_metric_snapshot_dimension on quality_metric_snapshot(project_id, rule_dimension, snapshot_date)");
    }

    private void ensureDataServiceTablesMysql() {
        if (!tableExists("data_service_definition")) {
            jdbcTemplate.execute("create table data_service_definition (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "created_by bigint," +
                    "service_code varchar(128) not null," +
                    "service_name varchar(255) not null," +
                    "service_type varchar(64) not null," +
                    "status varchar(64) not null," +
                    "source_type varchar(64) not null," +
                    "datasource_id bigint," +
                    "datasource_name_snapshot varchar(255)," +
                    "datasource_type_code varchar(128)," +
                    "model_id bigint," +
                    "model_name_snapshot varchar(255)," +
                    "model_physical_locator varchar(1000)," +
                    "custom_sql longtext," +
                    "request_method varchar(32)," +
                    "response_type varchar(32)," +
                    "endpoint_path varchar(1000)," +
                    "service_key varchar(128)," +
                    "cache_enabled int default 0," +
                    "token_required int default 1," +
                    "default_subscription_name varchar(255)," +
                    "webservice_enabled int default 0," +
                    "webservice_config_json json" +
                    ")");
        }
        ensureColumn("data_service_definition", "token_required",
                "alter table data_service_definition add column token_required int default 1 after cache_enabled");
        ensureColumn("data_service_definition", "default_subscription_name",
                "alter table data_service_definition add column default_subscription_name varchar(255) after token_required");
        ensureColumn("data_service_definition", "webservice_enabled",
                "alter table data_service_definition add column webservice_enabled int default 0 after default_subscription_name");
        ensureColumn("data_service_definition", "webservice_config_json",
                "alter table data_service_definition add column webservice_config_json json after webservice_enabled");
        ensureIndex("data_service_definition", "uk_data_service_project_code",
                "alter table data_service_definition add unique key uk_data_service_project_code (tenant_id, project_id, service_code)");
        ensureIndex("data_service_definition", "idx_data_service_project_status",
                "alter table data_service_definition add key idx_data_service_project_status (project_id, status)");
        ensureIndex("data_service_definition", "idx_data_service_code_key",
                "alter table data_service_definition add key idx_data_service_code_key (service_code, service_key)");

        if (!tableExists("data_service_request_param")) {
            jdbcTemplate.execute("create table data_service_request_param (" +
                    "id bigint primary key," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint not null," +
                    "sort_order int," +
                    "param_name varchar(128) not null," +
                    "field_name varchar(255)," +
                    "value_type varchar(64)," +
                    "query_operator varchar(64)," +
                    "required int default 0," +
                    "description varchar(1000)," +
                    "fixed_param int default 0" +
                    ")");
        }
        ensureIndex("data_service_request_param", "idx_data_service_request_service_order",
                "alter table data_service_request_param add key idx_data_service_request_service_order (service_id, sort_order)");

        if (!tableExists("data_service_response_param")) {
            jdbcTemplate.execute("create table data_service_response_param (" +
                    "id bigint primary key," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint not null," +
                    "sort_order int," +
                    "enabled int default 1," +
                    "param_name varchar(128) not null," +
                    "field_name varchar(255) not null," +
                    "example_value varchar(1000)," +
                    "description varchar(1000)," +
                    "transformers_json json" +
                    ")");
        }
        ensureIndex("data_service_response_param", "idx_data_service_response_service_order",
                "alter table data_service_response_param add key idx_data_service_response_service_order (service_id, sort_order)");

        if (!tableExists("data_service_publish_param")) {
            jdbcTemplate.execute("create table data_service_publish_param (" +
                    "id bigint primary key," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint not null," +
                    "sort_order int," +
                    "frontend_param_name varchar(128) not null," +
                    "backend_param_name varchar(128) not null," +
                    "position varchar(32)," +
                    "value_type varchar(64)," +
                    "example_value varchar(1000)," +
                    "default_value varchar(1000)," +
                    "required int default 0," +
                    "description varchar(1000)" +
                    ")");
        }
        ensureIndex("data_service_publish_param", "idx_data_service_publish_service_order",
                "alter table data_service_publish_param add key idx_data_service_publish_service_order (service_id, sort_order)");

        if (!tableExists("data_service_subscription")) {
            jdbcTemplate.execute("create table data_service_subscription (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint not null," +
                    "subscription_name varchar(255) not null," +
                    "token_hash varchar(128) not null," +
                    "token_masked varchar(64)," +
                    "enabled int default 1," +
                    "created_by bigint," +
                    "last_used_at datetime," +
                    "rotated_at datetime," +
                    "rotated_by bigint" +
                    ")");
        }
        ensureColumn("data_service_subscription", "token_masked",
                "alter table data_service_subscription add column token_masked varchar(64)");
        ensureColumn("data_service_subscription", "rotated_at",
                "alter table data_service_subscription add column rotated_at datetime");
        ensureColumn("data_service_subscription", "rotated_by",
                "alter table data_service_subscription add column rotated_by bigint");
        ensureActiveSubscriptionUniquenessMysql("data_service_subscription", "uk_data_service_sub_active_name");
        ensureIndex("data_service_subscription", "idx_data_service_subscription_service_enabled",
                "alter table data_service_subscription add key idx_data_service_subscription_service_enabled (service_id, enabled)");
        ensureIndex("data_service_subscription", "idx_data_service_subscription_token",
                "alter table data_service_subscription add key idx_data_service_subscription_token (token_hash)");

        if (!tableExists("data_service_access_log")) {
            jdbcTemplate.execute("create table data_service_access_log (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint," +
                    "service_code_snapshot varchar(255)," +
                    "service_name_snapshot varchar(255)," +
                    "service_status_snapshot varchar(64)," +
                    "subscription_id bigint," +
                    "subscription_name_snapshot varchar(255)," +
                    "request_id varchar(128)," +
                    "request_method varchar(16)," +
                    "occurred_at datetime," +
                    "duration_ms bigint," +
                    "success int default 0," +
                    "http_status int," +
                    "error_code varchar(128)," +
                    "error_message varchar(1000)," +
                    "system_log mediumtext," +
                    "client_ip varchar(128)," +
                    "user_agent varchar(500)," +
                    "cache_enabled int default 0," +
                    "cache_hit int default 0," +
                    "row_count bigint default 0," +
                    "log_storage_type varchar(64)," +
                    "log_object_bucket varchar(255)," +
                    "log_object_key varchar(1000)," +
                    "log_size_bytes bigint," +
                    "log_charset varchar(64)," +
                    "log_archive_status varchar(64)," +
                    "log_archive_error varchar(1000)" +
                    ")");
        }
        ensureColumn("data_service_access_log", "request_id",
                "alter table data_service_access_log add column request_id varchar(128) after subscription_name_snapshot");
        ensureColumn("data_service_access_log", "system_log",
                "alter table data_service_access_log add column system_log mediumtext after error_message");
        jdbcTemplate.execute("alter table data_service_access_log modify column system_log mediumtext");
        ensureColumn("data_service_access_log", "cache_enabled",
                "alter table data_service_access_log add column cache_enabled int default 0 after user_agent");
        ensureInvocationLogArchiveColumnsMysql("data_service_access_log");
        jdbcTemplate.execute("update data_service_access_log set cache_enabled = 1 where cache_hit = 1 and (cache_enabled is null or cache_enabled <> 1)");
        ensureIndex("data_service_access_log", "idx_data_service_access_project_time",
                "alter table data_service_access_log add key idx_data_service_access_project_time (tenant_id, project_id, occurred_at)");
        ensureIndex("data_service_access_log", "idx_data_service_access_service_time",
                "alter table data_service_access_log add key idx_data_service_access_service_time (service_id, occurred_at)");
        ensureIndex("data_service_access_log", "idx_data_service_access_subscription_time",
                "alter table data_service_access_log add key idx_data_service_access_subscription_time (subscription_id, occurred_at)");
        ensureIndex("data_service_access_log", "idx_data_service_access_success",
                "alter table data_service_access_log add key idx_data_service_access_success (project_id, success, occurred_at)");
        ensureIndex("data_service_access_log", "idx_data_service_access_cache",
                "alter table data_service_access_log add key idx_data_service_access_cache (project_id, cache_hit, occurred_at)");
        if (!tableExists("data_service_access_counter")) {
            jdbcTemplate.execute("create table data_service_access_counter (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint not null default 0," +
                    "subscription_id bigint not null default 0," +
                    "bucket_start datetime not null," +
                    "success int not null default 0," +
                    "cache_enabled int not null default 0," +
                    "cache_hit int not null default 0," +
                    "access_count bigint default 0," +
                    "row_count bigint default 0" +
                    ")");
        }
        ensureIndex("data_service_access_counter", "uk_data_service_access_counter",
                "alter table data_service_access_counter add unique key uk_data_service_access_counter (tenant_id, project_id, service_id, subscription_id, bucket_start, success, cache_enabled, cache_hit)");
        ensureIndex("data_service_access_counter", "idx_data_service_counter_project_bucket",
                "alter table data_service_access_counter add key idx_data_service_counter_project_bucket (tenant_id, project_id, bucket_start)");
        ensureIndex("data_service_access_counter", "idx_data_service_counter_service_bucket",
                "alter table data_service_access_counter add key idx_data_service_counter_service_bucket (service_id, bucket_start)");
        backfillDataServiceAccessCounterMysql();
    }

    private void backfillDataServiceAccessCounterMysql() {
        jdbcTemplate.execute("insert ignore into data_service_access_counter (" +
                "id, tenant_id, project_id, deleted, created_at, updated_at, service_id, subscription_id, bucket_start, success, cache_enabled, cache_hit, access_count, row_count" +
                ") select " +
                "cast(conv(substr(md5(concat(t.tenant_id, '|', t.project_id, '|', t.service_id, '|', t.subscription_id, '|', date_format(t.bucket_start, '%Y-%m-%d %H:%i:%s'), '|', t.success, '|', t.cache_enabled, '|', t.cache_hit)), 1, 15), 16, 10) as unsigned) as id," +
                "t.tenant_id, t.project_id, 0, current_timestamp, current_timestamp, t.service_id, t.subscription_id, t.bucket_start, t.success, t.cache_enabled, t.cache_hit, t.access_count, t.row_count " +
                "from (" +
                "select tenant_id, project_id, coalesce(service_id, 0) as service_id, coalesce(subscription_id, 0) as subscription_id, " +
                "str_to_date(date_format(coalesce(occurred_at, created_at), '%Y-%m-%d %H:00:00'), '%Y-%m-%d %H:%i:%s') as bucket_start, " +
                "case when success = 1 then 1 else 0 end as success, " +
                "case when cache_enabled = 1 then 1 else 0 end as cache_enabled, " +
                "case when cache_enabled = 1 and cache_hit = 1 then 1 else 0 end as cache_hit, " +
                "count(*) as access_count, coalesce(sum(row_count), 0) as row_count " +
                "from data_service_access_log " +
                "where deleted = 0 and project_id is not null and service_id is not null and coalesce(occurred_at, created_at) is not null " +
                "group by tenant_id, project_id, coalesce(service_id, 0), coalesce(subscription_id, 0), bucket_start, success, cache_enabled, cache_hit" +
                ") t");
    }

    private void ensureDataServiceTablesSqlite() {
        jdbcTemplate.execute("create table if not exists data_service_definition (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "created_by integer," +
                "service_code text not null," +
                "service_name text not null," +
                "service_type text not null," +
                "status text not null," +
                "source_type text not null," +
                "datasource_id integer," +
                "datasource_name_snapshot text," +
                "datasource_type_code text," +
                "model_id integer," +
                "model_name_snapshot text," +
                "model_physical_locator text," +
                "custom_sql text," +
                "request_method text," +
                "response_type text," +
                "endpoint_path text," +
                "service_key text," +
                "cache_enabled integer default 0," +
                "token_required integer default 1," +
                "default_subscription_name text," +
                "webservice_enabled integer default 0," +
                "webservice_config_json text" +
                ")");
        ensureColumn("data_service_definition", "token_required",
                "alter table data_service_definition add column token_required integer default 1");
        ensureColumn("data_service_definition", "default_subscription_name",
                "alter table data_service_definition add column default_subscription_name text");
        ensureColumn("data_service_definition", "webservice_enabled",
                "alter table data_service_definition add column webservice_enabled integer default 0");
        ensureColumn("data_service_definition", "webservice_config_json",
                "alter table data_service_definition add column webservice_config_json text");
        jdbcTemplate.execute("create unique index if not exists uk_data_service_project_code on data_service_definition(tenant_id, project_id, service_code)");
        jdbcTemplate.execute("create index if not exists idx_data_service_project_status on data_service_definition(project_id, status)");
        jdbcTemplate.execute("create index if not exists idx_data_service_code_key on data_service_definition(service_code, service_key)");

        jdbcTemplate.execute("create table if not exists data_service_request_param (" +
                "id integer primary key," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer not null," +
                "sort_order integer," +
                "param_name text not null," +
                "field_name text," +
                "value_type text," +
                "query_operator text," +
                "required integer default 0," +
                "description text," +
                "fixed_param integer default 0" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_data_service_request_service_order on data_service_request_param(service_id, sort_order)");

        jdbcTemplate.execute("create table if not exists data_service_response_param (" +
                "id integer primary key," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer not null," +
                "sort_order integer," +
                "enabled integer default 1," +
                "param_name text not null," +
                "field_name text not null," +
                "example_value text," +
                "description text," +
                "transformers_json text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_data_service_response_service_order on data_service_response_param(service_id, sort_order)");

        jdbcTemplate.execute("create table if not exists data_service_publish_param (" +
                "id integer primary key," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer not null," +
                "sort_order integer," +
                "frontend_param_name text not null," +
                "backend_param_name text not null," +
                "position text," +
                "value_type text," +
                "example_value text," +
                "default_value text," +
                "required integer default 0," +
                "description text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_data_service_publish_service_order on data_service_publish_param(service_id, sort_order)");

        jdbcTemplate.execute("create table if not exists data_service_subscription (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer not null," +
                "subscription_name text not null," +
                "token_hash text not null," +
                "token_masked text," +
                "enabled integer default 1," +
                "created_by integer," +
                "last_used_at text," +
                "rotated_at text," +
                "rotated_by integer" +
                ")");
        ensureColumn("data_service_subscription", "token_masked",
                "alter table data_service_subscription add column token_masked text");
        ensureColumn("data_service_subscription", "rotated_at",
                "alter table data_service_subscription add column rotated_at text");
        ensureColumn("data_service_subscription", "rotated_by",
                "alter table data_service_subscription add column rotated_by integer");
        ensureActiveSubscriptionUniquenessSqlite("data_service_subscription", "uk_data_service_sub_active_name");
        jdbcTemplate.execute("create index if not exists idx_data_service_subscription_service_enabled on data_service_subscription(service_id, enabled)");
        jdbcTemplate.execute("create index if not exists idx_data_service_subscription_token on data_service_subscription(token_hash)");

        jdbcTemplate.execute("create table if not exists data_service_access_log (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer," +
                "service_code_snapshot text," +
                "service_name_snapshot text," +
                "service_status_snapshot text," +
                "subscription_id integer," +
                "subscription_name_snapshot text," +
                "request_id text," +
                "request_method text," +
                "occurred_at text," +
                "duration_ms integer," +
                "success integer default 0," +
                "http_status integer," +
                "error_code text," +
                "error_message text," +
                "system_log text," +
                "client_ip text," +
                "user_agent text," +
                "cache_enabled integer default 0," +
                "cache_hit integer default 0," +
                "row_count integer default 0," +
                "log_storage_type text," +
                "log_object_bucket text," +
                "log_object_key text," +
                "log_size_bytes integer," +
                "log_charset text," +
                "log_archive_status text," +
                "log_archive_error text" +
                ")");
        ensureColumn("data_service_access_log", "request_id",
                "alter table data_service_access_log add column request_id text");
        ensureColumn("data_service_access_log", "system_log",
                "alter table data_service_access_log add column system_log text");
        ensureColumn("data_service_access_log", "cache_enabled",
                "alter table data_service_access_log add column cache_enabled integer default 0");
        ensureInvocationLogArchiveColumnsSqlite("data_service_access_log");
        jdbcTemplate.execute("update data_service_access_log set cache_enabled = 1 where cache_hit = 1 and (cache_enabled is null or cache_enabled <> 1)");
        jdbcTemplate.execute("create index if not exists idx_data_service_access_project_time on data_service_access_log(tenant_id, project_id, occurred_at)");
        jdbcTemplate.execute("create index if not exists idx_data_service_access_service_time on data_service_access_log(service_id, occurred_at)");
        jdbcTemplate.execute("create index if not exists idx_data_service_access_subscription_time on data_service_access_log(subscription_id, occurred_at)");
        jdbcTemplate.execute("create index if not exists idx_data_service_access_success on data_service_access_log(project_id, success, occurred_at)");
        jdbcTemplate.execute("create index if not exists idx_data_service_access_cache on data_service_access_log(project_id, cache_hit, occurred_at)");

        jdbcTemplate.execute("create table if not exists data_service_access_counter (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer not null default 0," +
                "subscription_id integer not null default 0," +
                "bucket_start text not null," +
                "success integer not null default 0," +
                "cache_enabled integer not null default 0," +
                "cache_hit integer not null default 0," +
                "access_count integer default 0," +
                "row_count integer default 0" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_data_service_access_counter on data_service_access_counter(tenant_id, project_id, service_id, subscription_id, bucket_start, success, cache_enabled, cache_hit)");
        jdbcTemplate.execute("create index if not exists idx_data_service_counter_project_bucket on data_service_access_counter(tenant_id, project_id, bucket_start)");
        jdbcTemplate.execute("create index if not exists idx_data_service_counter_service_bucket on data_service_access_counter(service_id, bucket_start)");
        backfillDataServiceAccessCounterSqlite();
    }

    private void backfillDataServiceAccessCounterSqlite() {
        jdbcTemplate.execute("insert or ignore into data_service_access_counter (" +
                "id, tenant_id, project_id, deleted, created_at, updated_at, service_id, subscription_id, bucket_start, success, cache_enabled, cache_hit, access_count, row_count" +
                ") select " +
                "abs(random()) as id, t.tenant_id, t.project_id, 0, datetime('now'), datetime('now'), t.service_id, t.subscription_id, t.bucket_start, t.success, t.cache_enabled, t.cache_hit, t.access_count, t.row_count " +
                "from (" +
                "select tenant_id, project_id, coalesce(service_id, 0) as service_id, coalesce(subscription_id, 0) as subscription_id, " +
                "strftime('%Y-%m-%d %H:00:00', coalesce(occurred_at, created_at)) as bucket_start, " +
                "case when success = 1 then 1 else 0 end as success, " +
                "case when cache_enabled = 1 then 1 else 0 end as cache_enabled, " +
                "case when cache_enabled = 1 and cache_hit = 1 then 1 else 0 end as cache_hit, " +
                "count(*) as access_count, coalesce(sum(row_count), 0) as row_count " +
                "from data_service_access_log " +
                "where deleted = 0 and project_id is not null and service_id is not null and coalesce(occurred_at, created_at) is not null " +
                "group by tenant_id, project_id, coalesce(service_id, 0), coalesce(subscription_id, 0), bucket_start, success, cache_enabled, cache_hit" +
                ") t");
    }

    private void ensureDataIngestionTablesMysql() {
        if (!tableExists("data_ingestion_service")) {
            jdbcTemplate.execute("create table data_ingestion_service (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "created_by bigint," +
                    "service_code varchar(128) not null," +
                    "service_name varchar(255) not null," +
                    "status varchar(64) not null," +
                    "request_format varchar(32) not null," +
                    "payload_mode varchar(32)," +
                    "data_node_path varchar(500)," +
                    "target_type varchar(32) not null," +
                    "datasource_id bigint," +
                    "datasource_name_snapshot varchar(255)," +
                    "datasource_type_code varchar(128)," +
                    "model_id bigint," +
                    "model_name_snapshot varchar(255)," +
                    "model_physical_locator varchar(1000)," +
                    "endpoint_path varchar(1000)," +
                    "service_key varchar(128)," +
                    "max_batch_size int default 500," +
                    "token_required int default 1," +
                    "default_subscription_name varchar(255)," +
                    "webservice_enabled int default 0," +
                    "webservice_config_json json," +
                    "writer_options_json json," +
                    "field_mappings_json json" +
                    ")");
        }
        ensureColumn("data_ingestion_service", "token_required",
                "alter table data_ingestion_service add column token_required int default 1 after max_batch_size");
        ensureColumn("data_ingestion_service", "default_subscription_name",
                "alter table data_ingestion_service add column default_subscription_name varchar(255) after token_required");
        ensureColumn("data_ingestion_service", "webservice_enabled",
                "alter table data_ingestion_service add column webservice_enabled int default 0 after default_subscription_name");
        ensureColumn("data_ingestion_service", "webservice_config_json",
                "alter table data_ingestion_service add column webservice_config_json json after webservice_enabled");
        ensureIndex("data_ingestion_service", "uk_data_ingestion_project_code",
                "alter table data_ingestion_service add unique key uk_data_ingestion_project_code (tenant_id, project_id, service_code)");
        ensureIndex("data_ingestion_service", "idx_data_ingestion_project_status",
                "alter table data_ingestion_service add key idx_data_ingestion_project_status (project_id, status)");
        ensureIndex("data_ingestion_service", "idx_data_ingestion_code_key",
                "alter table data_ingestion_service add key idx_data_ingestion_code_key (service_code, service_key)");

        if (!tableExists("data_ingestion_subscription")) {
            jdbcTemplate.execute("create table data_ingestion_subscription (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint not null," +
                    "subscription_name varchar(255) not null," +
                    "token_hash varchar(128) not null," +
                    "token_masked varchar(64)," +
                    "enabled int default 1," +
                    "created_by bigint," +
                    "last_used_at datetime," +
                    "rotated_at datetime," +
                    "rotated_by bigint" +
                    ")");
        }
        ensureColumn("data_ingestion_subscription", "token_masked",
                "alter table data_ingestion_subscription add column token_masked varchar(64)");
        ensureColumn("data_ingestion_subscription", "rotated_at",
                "alter table data_ingestion_subscription add column rotated_at datetime");
        ensureColumn("data_ingestion_subscription", "rotated_by",
                "alter table data_ingestion_subscription add column rotated_by bigint");
        ensureActiveSubscriptionUniquenessMysql("data_ingestion_subscription", "uk_data_ingestion_sub_active_name");
        ensureIndex("data_ingestion_subscription", "idx_data_ingestion_sub_service_enabled",
                "alter table data_ingestion_subscription add key idx_data_ingestion_sub_service_enabled (service_id, enabled)");
        ensureIndex("data_ingestion_subscription", "idx_data_ingestion_sub_token",
                "alter table data_ingestion_subscription add key idx_data_ingestion_sub_token (token_hash)");

        if (!tableExists("data_ingestion_access_log")) {
            jdbcTemplate.execute("create table data_ingestion_access_log (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint," +
                    "service_code_snapshot varchar(255)," +
                    "service_name_snapshot varchar(255)," +
                    "service_status_snapshot varchar(64)," +
                    "subscription_id bigint," +
                    "subscription_name_snapshot varchar(255)," +
                    "request_id varchar(128)," +
                    "request_method varchar(16)," +
                    "occurred_at datetime," +
                    "duration_ms bigint," +
                    "success int default 0," +
                    "http_status int," +
                    "error_code varchar(128)," +
                    "error_message varchar(1000)," +
                    "system_log mediumtext," +
                    "client_ip varchar(128)," +
                    "user_agent varchar(500)," +
                    "received_count bigint default 0," +
                    "success_count bigint default 0," +
                    "failed_count bigint default 0," +
                    "log_storage_type varchar(64)," +
                    "log_object_bucket varchar(255)," +
                    "log_object_key varchar(1000)," +
                    "log_size_bytes bigint," +
                    "log_charset varchar(64)," +
                    "log_archive_status varchar(64)," +
                    "log_archive_error varchar(1000)" +
                    ")");
        }
        ensureColumn("data_ingestion_access_log", "request_id",
                "alter table data_ingestion_access_log add column request_id varchar(128) after subscription_name_snapshot");
        ensureColumn("data_ingestion_access_log", "system_log",
                "alter table data_ingestion_access_log add column system_log mediumtext after error_message");
        jdbcTemplate.execute("alter table data_ingestion_access_log modify column system_log mediumtext");
        ensureInvocationLogArchiveColumnsMysql("data_ingestion_access_log");
        ensureIndex("data_ingestion_access_log", "idx_data_ingestion_access_project_time",
                "alter table data_ingestion_access_log add key idx_data_ingestion_access_project_time (tenant_id, project_id, occurred_at)");
        ensureIndex("data_ingestion_access_log", "idx_data_ingestion_access_service_time",
                "alter table data_ingestion_access_log add key idx_data_ingestion_access_service_time (service_id, occurred_at)");
        ensureIndex("data_ingestion_access_log", "idx_data_ingestion_access_subscription_time",
                "alter table data_ingestion_access_log add key idx_data_ingestion_access_subscription_time (subscription_id, occurred_at)");
        ensureIndex("data_ingestion_access_log", "idx_data_ingestion_access_success",
                "alter table data_ingestion_access_log add key idx_data_ingestion_access_success (project_id, success, occurred_at)");

        if (!tableExists("data_ingestion_access_counter")) {
            jdbcTemplate.execute("create table data_ingestion_access_counter (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint not null default 0," +
                    "subscription_id bigint not null default 0," +
                    "bucket_start datetime not null," +
                    "success int not null default 0," +
                    "access_count bigint default 0," +
                    "received_count bigint default 0," +
                    "success_count bigint default 0," +
                    "failed_count bigint default 0" +
                    ")");
        }
        ensureIndex("data_ingestion_access_counter", "uk_data_ingestion_access_counter",
                "alter table data_ingestion_access_counter add unique key uk_data_ingestion_access_counter (tenant_id, project_id, service_id, subscription_id, bucket_start, success)");
        ensureIndex("data_ingestion_access_counter", "idx_data_ingestion_counter_project_bucket",
                "alter table data_ingestion_access_counter add key idx_data_ingestion_counter_project_bucket (tenant_id, project_id, bucket_start)");
        ensureIndex("data_ingestion_access_counter", "idx_data_ingestion_counter_service_bucket",
                "alter table data_ingestion_access_counter add key idx_data_ingestion_counter_service_bucket (service_id, bucket_start)");
    }

    private void ensureDataIngestionTablesSqlite() {
        jdbcTemplate.execute("create table if not exists data_ingestion_service (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "created_by integer," +
                "service_code text not null," +
                "service_name text not null," +
                "status text not null," +
                "request_format text not null," +
                "payload_mode text," +
                "data_node_path text," +
                "target_type text not null," +
                "datasource_id integer," +
                "datasource_name_snapshot text," +
                "datasource_type_code text," +
                "model_id integer," +
                "model_name_snapshot text," +
                "model_physical_locator text," +
                "endpoint_path text," +
                "service_key text," +
                "max_batch_size integer default 500," +
                "token_required integer default 1," +
                "default_subscription_name text," +
                "webservice_enabled integer default 0," +
                "webservice_config_json text," +
                "writer_options_json text," +
                "field_mappings_json text" +
                ")");
        ensureColumn("data_ingestion_service", "token_required",
                "alter table data_ingestion_service add column token_required integer default 1");
        ensureColumn("data_ingestion_service", "default_subscription_name",
                "alter table data_ingestion_service add column default_subscription_name text");
        ensureColumn("data_ingestion_service", "webservice_enabled",
                "alter table data_ingestion_service add column webservice_enabled integer default 0");
        ensureColumn("data_ingestion_service", "webservice_config_json",
                "alter table data_ingestion_service add column webservice_config_json text");
        jdbcTemplate.execute("create unique index if not exists uk_data_ingestion_project_code on data_ingestion_service(tenant_id, project_id, service_code)");
        jdbcTemplate.execute("create index if not exists idx_data_ingestion_project_status on data_ingestion_service(project_id, status)");
        jdbcTemplate.execute("create index if not exists idx_data_ingestion_code_key on data_ingestion_service(service_code, service_key)");

        jdbcTemplate.execute("create table if not exists data_ingestion_subscription (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer not null," +
                "subscription_name text not null," +
                "token_hash text not null," +
                "token_masked text," +
                "enabled integer default 1," +
                "created_by integer," +
                "last_used_at text," +
                "rotated_at text," +
                "rotated_by integer" +
                ")");
        ensureColumn("data_ingestion_subscription", "token_masked",
                "alter table data_ingestion_subscription add column token_masked text");
        ensureColumn("data_ingestion_subscription", "rotated_at",
                "alter table data_ingestion_subscription add column rotated_at text");
        ensureColumn("data_ingestion_subscription", "rotated_by",
                "alter table data_ingestion_subscription add column rotated_by integer");
        ensureActiveSubscriptionUniquenessSqlite("data_ingestion_subscription", "uk_data_ingestion_sub_active_name");
        jdbcTemplate.execute("create index if not exists idx_data_ingestion_sub_service_enabled on data_ingestion_subscription(service_id, enabled)");
        jdbcTemplate.execute("create index if not exists idx_data_ingestion_sub_token on data_ingestion_subscription(token_hash)");

        jdbcTemplate.execute("create table if not exists data_ingestion_access_log (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer," +
                "service_code_snapshot text," +
                "service_name_snapshot text," +
                "service_status_snapshot text," +
                "subscription_id integer," +
                "subscription_name_snapshot text," +
                "request_id text," +
                "request_method text," +
                "occurred_at text," +
                "duration_ms integer," +
                "success integer default 0," +
                "http_status integer," +
                "error_code text," +
                "error_message text," +
                "system_log text," +
                "client_ip text," +
                "user_agent text," +
                "received_count integer default 0," +
                "success_count integer default 0," +
                "failed_count integer default 0," +
                "log_storage_type text," +
                "log_object_bucket text," +
                "log_object_key text," +
                "log_size_bytes integer," +
                "log_charset text," +
                "log_archive_status text," +
                "log_archive_error text" +
                ")");
        ensureColumn("data_ingestion_access_log", "request_id",
                "alter table data_ingestion_access_log add column request_id text");
        ensureColumn("data_ingestion_access_log", "system_log",
                "alter table data_ingestion_access_log add column system_log text");
        ensureInvocationLogArchiveColumnsSqlite("data_ingestion_access_log");
        jdbcTemplate.execute("create index if not exists idx_data_ingestion_access_project_time on data_ingestion_access_log(tenant_id, project_id, occurred_at)");
        jdbcTemplate.execute("create index if not exists idx_data_ingestion_access_service_time on data_ingestion_access_log(service_id, occurred_at)");
        jdbcTemplate.execute("create index if not exists idx_data_ingestion_access_subscription_time on data_ingestion_access_log(subscription_id, occurred_at)");
        jdbcTemplate.execute("create index if not exists idx_data_ingestion_access_success on data_ingestion_access_log(project_id, success, occurred_at)");

        jdbcTemplate.execute("create table if not exists data_ingestion_access_counter (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer not null default 0," +
                "subscription_id integer not null default 0," +
                "bucket_start text not null," +
                "success integer not null default 0," +
                "access_count integer default 0," +
                "received_count integer default 0," +
                "success_count integer default 0," +
                "failed_count integer default 0" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_data_ingestion_access_counter on data_ingestion_access_counter(tenant_id, project_id, service_id, subscription_id, bucket_start, success)");
        jdbcTemplate.execute("create index if not exists idx_data_ingestion_counter_project_bucket on data_ingestion_access_counter(tenant_id, project_id, bucket_start)");
        jdbcTemplate.execute("create index if not exists idx_data_ingestion_counter_service_bucket on data_ingestion_access_counter(service_id, bucket_start)");
    }

    private void ensureProtocolConversionTablesMysql() {
        if (!tableExists("protocol_conversion_service")) {
            jdbcTemplate.execute("create table protocol_conversion_service (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "created_by bigint," +
                    "service_code varchar(128) not null," +
                    "service_name varchar(255) not null," +
                    "status varchar(64) not null," +
                    "endpoint_path varchar(1000)," +
                    "webservice_endpoint_path varchar(1000)," +
                    "service_key varchar(128)," +
                    "token_required int default 1," +
                    "default_subscription_name varchar(255)," +
                    "source_protocol varchar(32) not null," +
                    "source_method varchar(16)," +
                    "source_data_node_path varchar(500)," +
                    "webservice_config_json json," +
                    "conversion_mode varchar(32) not null," +
                    "field_mappings_json json," +
                    "raw_transformers_json json," +
                    "fixed_fields_json json," +
                    "body_bridge_options_json json," +
                    "request_passthrough_json json," +
                    "target_datasource_id bigint," +
                    "target_datasource_name_snapshot varchar(255)," +
                    "target_path varchar(1000)," +
                    "target_protocol varchar(32) not null," +
                    "target_method varchar(16)," +
                    "target_headers_json json," +
                    "target_query_json json," +
                    "target_webservice_config_json json," +
                    "target_body_template mediumtext," +
                    "target_data_node_path varchar(500)," +
                    "payload_mode varchar(32)," +
                    "batch_size int default 1," +
                    "response_status_json json" +
                    ")");
        }
        ensureColumn("protocol_conversion_service", "webservice_endpoint_path",
                "alter table protocol_conversion_service add column webservice_endpoint_path varchar(1000)");
        ensureColumn("protocol_conversion_service", "token_required",
                "alter table protocol_conversion_service add column token_required int default 1");
        ensureColumn("protocol_conversion_service", "default_subscription_name",
                "alter table protocol_conversion_service add column default_subscription_name varchar(255)");
        ensureColumn("protocol_conversion_service", "source_method",
                "alter table protocol_conversion_service add column source_method varchar(16)");
        ensureColumn("protocol_conversion_service", "source_data_node_path",
                "alter table protocol_conversion_service add column source_data_node_path varchar(500)");
        ensureColumn("protocol_conversion_service", "webservice_config_json",
                "alter table protocol_conversion_service add column webservice_config_json json");
        ensureColumn("protocol_conversion_service", "raw_transformers_json",
                "alter table protocol_conversion_service add column raw_transformers_json json");
        ensureColumn("protocol_conversion_service", "fixed_fields_json",
                "alter table protocol_conversion_service add column fixed_fields_json json");
        ensureColumn("protocol_conversion_service", "body_bridge_options_json",
                "alter table protocol_conversion_service add column body_bridge_options_json json");
        ensureColumn("protocol_conversion_service", "request_passthrough_json",
                "alter table protocol_conversion_service add column request_passthrough_json json");
        ensureColumn("protocol_conversion_service", "target_query_json",
                "alter table protocol_conversion_service add column target_query_json json");
        ensureColumn("protocol_conversion_service", "target_webservice_config_json",
                "alter table protocol_conversion_service add column target_webservice_config_json json");
        ensureColumn("protocol_conversion_service", "target_data_node_path",
                "alter table protocol_conversion_service add column target_data_node_path varchar(500)");
        ensureColumn("protocol_conversion_service", "payload_mode",
                "alter table protocol_conversion_service add column payload_mode varchar(32)");
        ensureColumn("protocol_conversion_service", "batch_size",
                "alter table protocol_conversion_service add column batch_size int default 1");
        ensureColumn("protocol_conversion_service", "response_status_json",
                "alter table protocol_conversion_service add column response_status_json json");
        ensureIndex("protocol_conversion_service", "uk_protocol_conversion_project_code",
                "alter table protocol_conversion_service add unique key uk_protocol_conversion_project_code (tenant_id, project_id, service_code)");
        ensureIndex("protocol_conversion_service", "idx_protocol_conversion_project_status",
                "alter table protocol_conversion_service add key idx_protocol_conversion_project_status (project_id, status)");
        ensureIndex("protocol_conversion_service", "idx_protocol_conversion_code_key",
                "alter table protocol_conversion_service add key idx_protocol_conversion_code_key (service_code, service_key)");

        if (!tableExists("protocol_conversion_subscription")) {
            jdbcTemplate.execute("create table protocol_conversion_subscription (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint not null," +
                    "subscription_name varchar(255) not null," +
                    "token_hash varchar(128) not null," +
                    "token_masked varchar(64)," +
                    "enabled int default 1," +
                    "created_by bigint," +
                    "last_used_at datetime," +
                    "rotated_at datetime," +
                    "rotated_by bigint" +
                    ")");
        }
        ensureColumn("protocol_conversion_subscription", "last_used_at",
                "alter table protocol_conversion_subscription add column last_used_at datetime");
        ensureColumn("protocol_conversion_subscription", "rotated_at",
                "alter table protocol_conversion_subscription add column rotated_at datetime");
        ensureColumn("protocol_conversion_subscription", "rotated_by",
                "alter table protocol_conversion_subscription add column rotated_by bigint");
        ensureActiveSubscriptionUniquenessMysql("protocol_conversion_subscription", "uk_protocol_conversion_sub_active_name");
        ensureIndex("protocol_conversion_subscription", "idx_protocol_conversion_sub_service_enabled",
                "alter table protocol_conversion_subscription add key idx_protocol_conversion_sub_service_enabled (service_id, enabled)");
        ensureIndex("protocol_conversion_subscription", "idx_protocol_conversion_sub_token",
                "alter table protocol_conversion_subscription add key idx_protocol_conversion_sub_token (token_hash)");

        if (!tableExists("protocol_conversion_access_log")) {
            jdbcTemplate.execute("create table protocol_conversion_access_log (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint," +
                    "service_code_snapshot varchar(255)," +
                    "service_name_snapshot varchar(255)," +
                    "service_status_snapshot varchar(64)," +
                    "subscription_id bigint," +
                    "subscription_name_snapshot varchar(255)," +
                    "request_id varchar(128)," +
                    "request_method varchar(16)," +
                    "source_protocol_snapshot varchar(32)," +
                    "target_protocol_snapshot varchar(32)," +
                    "occurred_at datetime," +
                    "duration_ms bigint," +
                    "success int default 0," +
                    "http_status int," +
                    "target_http_status int," +
                    "error_code varchar(128)," +
                    "error_message varchar(1000)," +
                    "system_log mediumtext," +
                    "client_ip varchar(128)," +
                    "user_agent varchar(500)," +
                    "received_count bigint default 0," +
                    "success_count bigint default 0," +
                    "failed_count bigint default 0," +
                    "log_storage_type varchar(64)," +
                    "log_object_bucket varchar(255)," +
                    "log_object_key varchar(1000)," +
                    "log_size_bytes bigint," +
                    "log_charset varchar(64)," +
                    "log_archive_status varchar(64)," +
                    "log_archive_error varchar(1000)" +
                    ")");
        }
        ensureColumn("protocol_conversion_access_log", "request_id",
                "alter table protocol_conversion_access_log add column request_id varchar(128) after subscription_name_snapshot");
        ensureColumn("protocol_conversion_access_log", "source_protocol_snapshot",
                "alter table protocol_conversion_access_log add column source_protocol_snapshot varchar(32)");
        ensureColumn("protocol_conversion_access_log", "target_protocol_snapshot",
                "alter table protocol_conversion_access_log add column target_protocol_snapshot varchar(32)");
        ensureColumn("protocol_conversion_access_log", "target_http_status",
                "alter table protocol_conversion_access_log add column target_http_status int");
        ensureColumn("protocol_conversion_access_log", "system_log",
                "alter table protocol_conversion_access_log add column system_log mediumtext after error_message");
        ensureColumn("protocol_conversion_access_log", "received_count",
                "alter table protocol_conversion_access_log add column received_count bigint default 0");
        ensureColumn("protocol_conversion_access_log", "success_count",
                "alter table protocol_conversion_access_log add column success_count bigint default 0");
        ensureColumn("protocol_conversion_access_log", "failed_count",
                "alter table protocol_conversion_access_log add column failed_count bigint default 0");
        ensureInvocationLogArchiveColumnsMysql("protocol_conversion_access_log");
        ensureIndex("protocol_conversion_access_log", "idx_protocol_conversion_access_project_time",
                "alter table protocol_conversion_access_log add key idx_protocol_conversion_access_project_time (tenant_id, project_id, occurred_at)");
        ensureIndex("protocol_conversion_access_log", "idx_protocol_conversion_access_service_time",
                "alter table protocol_conversion_access_log add key idx_protocol_conversion_access_service_time (service_id, occurred_at)");
        ensureIndex("protocol_conversion_access_log", "idx_protocol_conversion_access_subscription_time",
                "alter table protocol_conversion_access_log add key idx_protocol_conversion_access_subscription_time (subscription_id, occurred_at)");
        ensureIndex("protocol_conversion_access_log", "idx_protocol_conversion_access_success",
                "alter table protocol_conversion_access_log add key idx_protocol_conversion_access_success (project_id, success, occurred_at)");

        if (!tableExists("protocol_conversion_access_counter")) {
            jdbcTemplate.execute("create table protocol_conversion_access_counter (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "project_id bigint," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "service_id bigint not null default 0," +
                    "subscription_id bigint not null default 0," +
                    "bucket_start datetime not null," +
                    "success int not null default 0," +
                    "access_count bigint default 0," +
                    "received_count bigint default 0," +
                    "success_count bigint default 0," +
                    "failed_count bigint default 0" +
                    ")");
        }
        ensureIndex("protocol_conversion_access_counter", "uk_protocol_conversion_access_counter",
                "alter table protocol_conversion_access_counter add unique key uk_protocol_conversion_access_counter (tenant_id, project_id, service_id, subscription_id, bucket_start, success)");
        ensureIndex("protocol_conversion_access_counter", "idx_protocol_conversion_counter_project_bucket",
                "alter table protocol_conversion_access_counter add key idx_protocol_conversion_counter_project_bucket (tenant_id, project_id, bucket_start)");
        ensureIndex("protocol_conversion_access_counter", "idx_protocol_conversion_counter_service_bucket",
                "alter table protocol_conversion_access_counter add key idx_protocol_conversion_counter_service_bucket (service_id, bucket_start)");
    }

    private void ensureProtocolConversionTablesSqlite() {
        jdbcTemplate.execute("create table if not exists protocol_conversion_service (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "created_by integer," +
                "service_code text not null," +
                "service_name text not null," +
                "status text not null," +
                "endpoint_path text," +
                "webservice_endpoint_path text," +
                "service_key text," +
                "token_required integer default 1," +
                "default_subscription_name text," +
                "source_protocol text not null," +
                "source_method text," +
                "source_data_node_path text," +
                "webservice_config_json text," +
                "conversion_mode text not null," +
                "field_mappings_json text," +
                "raw_transformers_json text," +
                "fixed_fields_json text," +
                "body_bridge_options_json text," +
                "request_passthrough_json text," +
                "target_datasource_id integer," +
                "target_datasource_name_snapshot text," +
                "target_path text," +
                "target_protocol text not null," +
                "target_method text," +
                "target_headers_json text," +
                "target_query_json text," +
                "target_webservice_config_json text," +
                "target_body_template text," +
                "target_data_node_path text," +
                "payload_mode text," +
                "batch_size integer default 1," +
                "response_status_json text" +
                ")");
        ensureColumn("protocol_conversion_service", "webservice_endpoint_path",
                "alter table protocol_conversion_service add column webservice_endpoint_path text");
        ensureColumn("protocol_conversion_service", "token_required",
                "alter table protocol_conversion_service add column token_required integer default 1");
        ensureColumn("protocol_conversion_service", "default_subscription_name",
                "alter table protocol_conversion_service add column default_subscription_name text");
        ensureColumn("protocol_conversion_service", "source_method",
                "alter table protocol_conversion_service add column source_method text");
        ensureColumn("protocol_conversion_service", "source_data_node_path",
                "alter table protocol_conversion_service add column source_data_node_path text");
        ensureColumn("protocol_conversion_service", "webservice_config_json",
                "alter table protocol_conversion_service add column webservice_config_json text");
        ensureColumn("protocol_conversion_service", "raw_transformers_json",
                "alter table protocol_conversion_service add column raw_transformers_json text");
        ensureColumn("protocol_conversion_service", "fixed_fields_json",
                "alter table protocol_conversion_service add column fixed_fields_json text");
        ensureColumn("protocol_conversion_service", "body_bridge_options_json",
                "alter table protocol_conversion_service add column body_bridge_options_json text");
        ensureColumn("protocol_conversion_service", "request_passthrough_json",
                "alter table protocol_conversion_service add column request_passthrough_json text");
        ensureColumn("protocol_conversion_service", "target_query_json",
                "alter table protocol_conversion_service add column target_query_json text");
        ensureColumn("protocol_conversion_service", "target_webservice_config_json",
                "alter table protocol_conversion_service add column target_webservice_config_json text");
        ensureColumn("protocol_conversion_service", "target_data_node_path",
                "alter table protocol_conversion_service add column target_data_node_path text");
        ensureColumn("protocol_conversion_service", "payload_mode",
                "alter table protocol_conversion_service add column payload_mode text");
        ensureColumn("protocol_conversion_service", "batch_size",
                "alter table protocol_conversion_service add column batch_size integer default 1");
        ensureColumn("protocol_conversion_service", "response_status_json",
                "alter table protocol_conversion_service add column response_status_json text");
        jdbcTemplate.execute("create unique index if not exists uk_protocol_conversion_project_code on protocol_conversion_service(tenant_id, project_id, service_code)");
        jdbcTemplate.execute("create index if not exists idx_protocol_conversion_project_status on protocol_conversion_service(project_id, status)");
        jdbcTemplate.execute("create index if not exists idx_protocol_conversion_code_key on protocol_conversion_service(service_code, service_key)");

        jdbcTemplate.execute("create table if not exists protocol_conversion_subscription (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer not null," +
                "subscription_name text not null," +
                "token_hash text not null," +
                "token_masked text," +
                "enabled integer default 1," +
                "created_by integer," +
                "last_used_at text," +
                "rotated_at text," +
                "rotated_by integer" +
                ")");
        ensureColumn("protocol_conversion_subscription", "last_used_at",
                "alter table protocol_conversion_subscription add column last_used_at text");
        ensureColumn("protocol_conversion_subscription", "rotated_at",
                "alter table protocol_conversion_subscription add column rotated_at text");
        ensureColumn("protocol_conversion_subscription", "rotated_by",
                "alter table protocol_conversion_subscription add column rotated_by integer");
        ensureActiveSubscriptionUniquenessSqlite("protocol_conversion_subscription", "uk_protocol_conversion_sub_active_name");
        jdbcTemplate.execute("create index if not exists idx_protocol_conversion_sub_service_enabled on protocol_conversion_subscription(service_id, enabled)");
        jdbcTemplate.execute("create index if not exists idx_protocol_conversion_sub_token on protocol_conversion_subscription(token_hash)");

        jdbcTemplate.execute("create table if not exists protocol_conversion_access_log (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer," +
                "service_code_snapshot text," +
                "service_name_snapshot text," +
                "service_status_snapshot text," +
                "subscription_id integer," +
                "subscription_name_snapshot text," +
                "request_id text," +
                "request_method text," +
                "source_protocol_snapshot text," +
                "target_protocol_snapshot text," +
                "occurred_at text," +
                "duration_ms integer," +
                "success integer default 0," +
                "http_status integer," +
                "target_http_status integer," +
                "error_code text," +
                "error_message text," +
                "system_log text," +
                "client_ip text," +
                "user_agent text," +
                "received_count integer default 0," +
                "success_count integer default 0," +
                "failed_count integer default 0," +
                "log_storage_type text," +
                "log_object_bucket text," +
                "log_object_key text," +
                "log_size_bytes integer," +
                "log_charset text," +
                "log_archive_status text," +
                "log_archive_error text" +
                ")");
        ensureColumn("protocol_conversion_access_log", "request_id",
                "alter table protocol_conversion_access_log add column request_id text");
        ensureColumn("protocol_conversion_access_log", "source_protocol_snapshot",
                "alter table protocol_conversion_access_log add column source_protocol_snapshot text");
        ensureColumn("protocol_conversion_access_log", "target_protocol_snapshot",
                "alter table protocol_conversion_access_log add column target_protocol_snapshot text");
        ensureColumn("protocol_conversion_access_log", "target_http_status",
                "alter table protocol_conversion_access_log add column target_http_status integer");
        ensureColumn("protocol_conversion_access_log", "system_log",
                "alter table protocol_conversion_access_log add column system_log text");
        ensureColumn("protocol_conversion_access_log", "received_count",
                "alter table protocol_conversion_access_log add column received_count integer default 0");
        ensureColumn("protocol_conversion_access_log", "success_count",
                "alter table protocol_conversion_access_log add column success_count integer default 0");
        ensureColumn("protocol_conversion_access_log", "failed_count",
                "alter table protocol_conversion_access_log add column failed_count integer default 0");
        ensureInvocationLogArchiveColumnsSqlite("protocol_conversion_access_log");
        jdbcTemplate.execute("create index if not exists idx_protocol_conversion_access_project_time on protocol_conversion_access_log(tenant_id, project_id, occurred_at)");
        jdbcTemplate.execute("create index if not exists idx_protocol_conversion_access_service_time on protocol_conversion_access_log(service_id, occurred_at)");
        jdbcTemplate.execute("create index if not exists idx_protocol_conversion_access_subscription_time on protocol_conversion_access_log(subscription_id, occurred_at)");
        jdbcTemplate.execute("create index if not exists idx_protocol_conversion_access_success on protocol_conversion_access_log(project_id, success, occurred_at)");

        jdbcTemplate.execute("create table if not exists protocol_conversion_access_counter (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "project_id integer," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "service_id integer not null default 0," +
                "subscription_id integer not null default 0," +
                "bucket_start text not null," +
                "success integer not null default 0," +
                "access_count integer default 0," +
                "received_count integer default 0," +
                "success_count integer default 0," +
                "failed_count integer default 0" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_protocol_conversion_access_counter on protocol_conversion_access_counter(tenant_id, project_id, service_id, subscription_id, bucket_start, success)");
        jdbcTemplate.execute("create index if not exists idx_protocol_conversion_counter_project_bucket on protocol_conversion_access_counter(tenant_id, project_id, bucket_start)");
        jdbcTemplate.execute("create index if not exists idx_protocol_conversion_counter_service_bucket on protocol_conversion_access_counter(service_id, bucket_start)");
    }

    private void ensureScriptEnvironmentTablesMysql() {
        if (!tableExists("so_pf_env_dep")) {
            jdbcTemplate.execute("create table so_pf_env_dep (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "name varchar(255) not null," +
                    "version varchar(128)," +
                    "script_type varchar(32) default 'JAVA'," +
                    "artifact_url text," +
                    "artifact_type varchar(32)," +
                    "checksum varchar(128)," +
                    "enabled int default 1," +
                    "description text" +
                    ")");
        }
        ensureColumn("so_pf_env_dep", "script_type",
                "alter table so_pf_env_dep add column script_type varchar(32) default 'JAVA' after version");
        jdbcTemplate.execute("update so_pf_env_dep set script_type = 'JAVA' where script_type is null or script_type = ''");
        if (!tableExists("so_pf_env_dep_file")) {
            jdbcTemplate.execute("create table so_pf_env_dep_file (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "dependency_id bigint not null," +
                    "original_file_name varchar(512) not null," +
                    "artifact_type varchar(32) not null," +
                    "object_key text," +
                    "object_url text," +
                    "checksum varchar(128)," +
                    "size_bytes bigint," +
                    "visible int default 1," +
                    "runtime_artifact int default 0," +
                    "source_file_id bigint," +
                    "enabled int default 1" +
                    ")");
        }
        if (!tableExists("so_pf_script_env")) {
            jdbcTemplate.execute("create table so_pf_script_env (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "environment_name varchar(255) not null," +
                    "environment_code varchar(128) not null," +
                    "enabled int default 1," +
                    "use_application_parent int default 1," +
                    "environment_version bigint default 1," +
                    "description text" +
                    ")");
        }
        if (!tableExists("so_pf_env_dep_rel")) {
            jdbcTemplate.execute("create table so_pf_env_dep_rel (" +
                    "id bigint primary key," +
                    "tenant_id varchar(64) default 'default'," +
                    "deleted int default 0," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "environment_id bigint not null," +
                    "dependency_id bigint not null," +
                    "sort_order int default 0" +
                    ")");
        }
        ensureIndex("so_pf_env_dep", "idx_so_pf_env_dep_tenant_enabled",
                "alter table so_pf_env_dep add key idx_so_pf_env_dep_tenant_enabled (tenant_id, enabled)");
        ensureIndex("so_pf_env_dep", "uk_so_pf_env_dep_name_ver",
                "alter table so_pf_env_dep add unique key uk_so_pf_env_dep_name_ver (tenant_id, name, version)");
        ensureIndex("so_pf_env_dep_file", "idx_so_pf_env_dep_file_dep",
                "alter table so_pf_env_dep_file add key idx_so_pf_env_dep_file_dep (tenant_id, dependency_id, visible)");
        ensureIndex("so_pf_env_dep_file", "idx_so_pf_env_dep_file_runtime",
                "alter table so_pf_env_dep_file add key idx_so_pf_env_dep_file_runtime (tenant_id, dependency_id, runtime_artifact)");
        ensureIndex("so_pf_script_env", "uk_so_pf_script_env_code",
                "alter table so_pf_script_env add unique key uk_so_pf_script_env_code (tenant_id, environment_code)");
        ensureIndex("so_pf_script_env", "idx_so_pf_script_env_enabled",
                "alter table so_pf_script_env add key idx_so_pf_script_env_enabled (tenant_id, enabled)");
        ensureIndex("so_pf_env_dep_rel", "idx_so_pf_env_dep_rel_env",
                "alter table so_pf_env_dep_rel add key idx_so_pf_env_dep_rel_env (environment_id, sort_order)");
        ensureIndex("so_pf_env_dep_rel", "uk_so_pf_env_dep_rel",
                "alter table so_pf_env_dep_rel add unique key uk_so_pf_env_dep_rel (environment_id, dependency_id)");
    }

    private void ensureScriptEnvironmentTablesSqlite() {
        jdbcTemplate.execute("create table if not exists so_pf_env_dep (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "name text not null," +
                "version text," +
                "script_type text default 'JAVA'," +
                "artifact_url text," +
                "artifact_type text," +
                "checksum text," +
                "enabled integer default 1," +
                "description text" +
                ")");
        ensureColumn("so_pf_env_dep", "script_type",
                "alter table so_pf_env_dep add column script_type text default 'JAVA'");
        jdbcTemplate.execute("update so_pf_env_dep set script_type = 'JAVA' where script_type is null or script_type = ''");
        jdbcTemplate.execute("create table if not exists so_pf_env_dep_file (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "dependency_id integer not null," +
                "original_file_name text not null," +
                "artifact_type text not null," +
                "object_key text," +
                "object_url text," +
                "checksum text," +
                "size_bytes integer," +
                "visible integer default 1," +
                "runtime_artifact integer default 0," +
                "source_file_id integer," +
                "enabled integer default 1" +
                ")");
        jdbcTemplate.execute("create table if not exists so_pf_script_env (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "environment_name text not null," +
                "environment_code text not null," +
                "enabled integer default 1," +
                "use_application_parent integer default 1," +
                "environment_version integer default 1," +
                "description text" +
                ")");
        jdbcTemplate.execute("create table if not exists so_pf_env_dep_rel (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "environment_id integer not null," +
                "dependency_id integer not null," +
                "sort_order integer default 0" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_so_pf_env_dep_tenant_enabled on so_pf_env_dep(tenant_id, enabled)");
        jdbcTemplate.execute("create unique index if not exists uk_so_pf_env_dep_name_ver on so_pf_env_dep(tenant_id, name, version)");
        jdbcTemplate.execute("create index if not exists idx_so_pf_env_dep_file_dep on so_pf_env_dep_file(tenant_id, dependency_id, visible)");
        jdbcTemplate.execute("create index if not exists idx_so_pf_env_dep_file_runtime on so_pf_env_dep_file(tenant_id, dependency_id, runtime_artifact)");
        jdbcTemplate.execute("create unique index if not exists uk_so_pf_script_env_code on so_pf_script_env(tenant_id, environment_code)");
        jdbcTemplate.execute("create index if not exists idx_so_pf_script_env_enabled on so_pf_script_env(tenant_id, enabled)");
        jdbcTemplate.execute("create index if not exists idx_so_pf_env_dep_rel_env on so_pf_env_dep_rel(environment_id, sort_order)");
        jdbcTemplate.execute("create unique index if not exists uk_so_pf_env_dep_rel on so_pf_env_dep_rel(environment_id, dependency_id)");
    }

    private void ensureClusterLockTableMysql() {
        if (!tableExists("studio_cluster_lock")) {
            jdbcTemplate.execute("create table studio_cluster_lock (" +
                    "id bigint primary key," +
                    "lock_name varchar(255) not null," +
                    "owner_id varchar(255)," +
                    "locked_until datetime," +
                    "last_acquired_at datetime," +
                    "created_at datetime default current_timestamp," +
                    "updated_at datetime default current_timestamp," +
                    "unique key uk_studio_cluster_lock_name (lock_name)," +
                    "key idx_studio_cluster_lock_until (locked_until)" +
                    ")");
        }
        ensureIndex("studio_cluster_lock", "uk_studio_cluster_lock_name",
                "alter table studio_cluster_lock add unique key uk_studio_cluster_lock_name (lock_name)");
        ensureIndex("studio_cluster_lock", "idx_studio_cluster_lock_until",
                "alter table studio_cluster_lock add key idx_studio_cluster_lock_until (locked_until)");
    }

    private void ensureClusterLockTableSqlite() {
        jdbcTemplate.execute("create table if not exists studio_cluster_lock (" +
                "id integer primary key," +
                "lock_name text not null," +
                "owner_id text," +
                "locked_until text," +
                "last_acquired_at text," +
                "created_at text," +
                "updated_at text" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_studio_cluster_lock_name on studio_cluster_lock(lock_name)");
        jdbcTemplate.execute("create index if not exists idx_studio_cluster_lock_until on studio_cluster_lock(locked_until)");
    }

    private void ensureDataModelNameUniqueIndexMysql() {
        if (!tableExists("data_model")) {
            return;
        }
        if (indexExists("data_model", "uk_data_model_project_name")) {
            jdbcTemplate.execute("alter table data_model drop index uk_data_model_project_name");
        }
        if (!indexMatchesColumns("data_model", "uk_data_model_project_datasource_name",
                "project_id", "datasource_id", "name")) {
            if (indexExists("data_model", "uk_data_model_project_datasource_name")) {
                jdbcTemplate.execute("alter table data_model drop index uk_data_model_project_datasource_name");
            }
            jdbcTemplate.execute("alter table data_model add unique key uk_data_model_project_datasource_name (project_id, datasource_id, name)");
        }
    }

    private void ensureDataModelNameUniqueIndexSqlite() {
        if (!tableExists("data_model")) {
            return;
        }
        jdbcTemplate.execute("drop index if exists uk_data_model_project_name");
        if (!indexMatchesColumns("data_model", "uk_data_model_project_datasource_name",
                "project_id", "datasource_id", "name")) {
            jdbcTemplate.execute("drop index if exists uk_data_model_project_datasource_name");
            jdbcTemplate.execute("create unique index if not exists uk_data_model_project_datasource_name on data_model(project_id, datasource_id, name)");
        }
    }

    private void ensureActiveSubscriptionUniquenessMysql(String tableName, String indexName) {
        if (!tableExists(tableName)) {
            return;
        }
        deduplicateEnabledSubscriptions(tableName);
        ensureColumn(tableName, "active_subscription_name",
                "alter table " + tableName + " add column active_subscription_name varchar(255) generated always as " +
                        "(case when enabled = 1 then subscription_name else null end) stored");
        ensureIndex(tableName, indexName,
                "alter table " + tableName + " add unique key " + indexName + " (service_id, active_subscription_name)");
    }

    private void ensureActiveSubscriptionUniquenessSqlite(String tableName, String indexName) {
        if (!tableExists(tableName)) {
            return;
        }
        deduplicateEnabledSubscriptions(tableName);
        jdbcTemplate.execute("create unique index if not exists " + indexName + " on " + tableName +
                "(service_id, subscription_name) where enabled = 1");
    }

    private void deduplicateEnabledSubscriptions(String tableName) {
        jdbcTemplate.update("update " + tableName + " set enabled=0 where enabled=1 and id not in (" +
                "select keep_id from (select max(id) as keep_id from " + tableName +
                " where enabled=1 group by service_id, subscription_name) subscription_keep)");
    }

    private boolean tableExists(String tableName) {
        return schemaIntrospector.tableExists(tableName);
    }

    private boolean columnExists(String tableName, String columnName) {
        return schemaIntrospector.columnExists(tableName, columnName);
    }

    private void ensureIndex(String tableName, String indexName, String ddl) {
        schemaIntrospector.ensureIndex(tableName, indexName, ddl);
    }

    private boolean indexMatchesColumns(String tableName, String indexName, String... expectedColumns) {
        return schemaIntrospector.indexMatchesColumns(tableName, indexName, expectedColumns);
    }

    private boolean indexExists(String tableName, String indexName) {
        return schemaIntrospector.indexExists(tableName, indexName);
    }
}
