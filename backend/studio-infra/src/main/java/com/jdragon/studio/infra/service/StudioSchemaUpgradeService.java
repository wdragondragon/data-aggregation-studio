package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.Locale;

@Service
public class StudioSchemaUpgradeService {

    private final JdbcTemplate jdbcTemplate;

    public StudioSchemaUpgradeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upgrade() {
        String databaseProduct = jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        if (databaseProduct == null) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Unable to detect database product");
        }
        String normalized = databaseProduct.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("mysql")) {
            upgradeMysql();
            return;
        }
        if (normalized.contains("sqlite")) {
            upgradeSqlite();
            return;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported database product for schema upgrade: " + databaseProduct);
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
        ensureColumn("workflow_definition_version", "project_id", "alter table workflow_definition_version add column project_id bigint");
        ensureColumn("workflow_node", "project_id", "alter table workflow_node add column project_id bigint");
        ensureColumn("workflow_edge", "project_id", "alter table workflow_edge add column project_id bigint");
        ensureColumn("workflow_schedule", "project_id", "alter table workflow_schedule add column project_id bigint");
        ensureColumn("collection_task_definition", "project_id", "alter table collection_task_definition add column project_id bigint");
        ensureColumn("collection_task_schedule", "project_id", "alter table collection_task_schedule add column project_id bigint");
        ensureColumn("data_dev_directory", "project_id", "alter table data_dev_directory add column project_id bigint");
        ensureColumn("data_dev_script", "project_id", "alter table data_dev_script add column project_id bigint");
        ensureColumn("dispatch_task", "execution_type", "alter table dispatch_task add column execution_type varchar(64)");
        ensureColumn("dispatch_task", "workflow_run_id", "alter table dispatch_task add column workflow_run_id bigint");
        ensureColumn("dispatch_task", "collection_task_id", "alter table dispatch_task add column collection_task_id bigint");
        ensureColumn("dispatch_task", "run_record_id", "alter table dispatch_task add column run_record_id bigint");
        ensureColumn("dispatch_task", "project_id", "alter table dispatch_task add column project_id bigint");
        ensureColumn("workflow_schedule", "last_triggered_at", "alter table workflow_schedule add column last_triggered_at datetime");
        ensureColumn("run_record", "execution_type", "alter table run_record add column execution_type varchar(64)");
        ensureColumn("run_record", "workflow_run_id", "alter table run_record add column workflow_run_id bigint");
        ensureColumn("run_record", "collection_task_id", "alter table run_record add column collection_task_id bigint");
        ensureColumn("run_record", "project_id", "alter table run_record add column project_id bigint");
        ensureColumn("run_record", "log_file_path", "alter table run_record add column log_file_path varchar(1000)");
        ensureColumn("run_record", "log_size_bytes", "alter table run_record add column log_size_bytes bigint");
        ensureColumn("run_record", "log_charset", "alter table run_record add column log_charset varchar(64)");

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
                    "description varchar(1000)," +
                    "content longtext" +
                    ")");
        }

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
        ensureIndex("data_model", "uk_data_model_project_name",
                "alter table data_model add unique key uk_data_model_project_name (project_id, name)");
        ensureIndex("data_model", "idx_data_model_project",
                "alter table data_model add key idx_data_model_project (project_id)");
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
        ensureIndex("dispatch_task", "idx_dispatch_task_project_status",
                "alter table dispatch_task add key idx_dispatch_task_project_status (project_id, status)");
        ensureIndex("dispatch_task", "idx_dispatch_task_project_workflow_run",
                "alter table dispatch_task add key idx_dispatch_task_project_workflow_run (project_id, workflow_run_id)");
        ensureIndex("run_record", "idx_run_record_project_created",
                "alter table run_record add key idx_run_record_project_created (project_id, created_at)");
        ensureIndex("run_record", "idx_run_record_project_workflow_run",
                "alter table run_record add key idx_run_record_project_workflow_run (project_id, workflow_run_id)");
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
        ensureIndex("studio_resource_share", "uk_studio_resource_share_target",
                "alter table studio_resource_share add unique key uk_studio_resource_share_target (resource_type, resource_id, target_project_id)");
        ensureIndex("studio_resource_share", "idx_studio_resource_share_project",
                "alter table studio_resource_share add key idx_studio_resource_share_project (target_project_id)");

        backfillProjectIdsMysql();
    }

    private void upgradeSqlite() {
        ensureColumn("meta_field_definition", "searchable_flag", "alter table meta_field_definition add column searchable_flag integer default 0");
        ensureColumn("meta_field_definition", "sortable_flag", "alter table meta_field_definition add column sortable_flag integer default 0");
        ensureColumn("meta_field_definition", "query_operators", "alter table meta_field_definition add column query_operators text");
        ensureColumn("meta_field_definition", "query_default_operator", "alter table meta_field_definition add column query_default_operator text");
        ensureColumn("datasource_definition", "project_id", "alter table datasource_definition add column project_id integer");
        ensureColumn("data_model", "project_id", "alter table data_model add column project_id integer");
        ensureColumn("data_model_attr_index", "project_id", "alter table data_model_attr_index add column project_id integer");
        ensureColumn("workflow_definition", "project_id", "alter table workflow_definition add column project_id integer");
        ensureColumn("workflow_definition_version", "project_id", "alter table workflow_definition_version add column project_id integer");
        ensureColumn("workflow_node", "project_id", "alter table workflow_node add column project_id integer");
        ensureColumn("workflow_edge", "project_id", "alter table workflow_edge add column project_id integer");
        ensureColumn("workflow_schedule", "project_id", "alter table workflow_schedule add column project_id integer");
        ensureColumn("collection_task_definition", "project_id", "alter table collection_task_definition add column project_id integer");
        ensureColumn("collection_task_schedule", "project_id", "alter table collection_task_schedule add column project_id integer");
        ensureColumn("data_dev_directory", "project_id", "alter table data_dev_directory add column project_id integer");
        ensureColumn("data_dev_script", "project_id", "alter table data_dev_script add column project_id integer");
        ensureColumn("dispatch_task", "execution_type", "alter table dispatch_task add column execution_type text");
        ensureColumn("dispatch_task", "workflow_run_id", "alter table dispatch_task add column workflow_run_id integer");
        ensureColumn("dispatch_task", "collection_task_id", "alter table dispatch_task add column collection_task_id integer");
        ensureColumn("dispatch_task", "run_record_id", "alter table dispatch_task add column run_record_id integer");
        ensureColumn("dispatch_task", "project_id", "alter table dispatch_task add column project_id integer");
        ensureColumn("workflow_schedule", "last_triggered_at", "alter table workflow_schedule add column last_triggered_at text");
        ensureColumn("run_record", "execution_type", "alter table run_record add column execution_type text");
        ensureColumn("run_record", "workflow_run_id", "alter table run_record add column workflow_run_id integer");
        ensureColumn("run_record", "collection_task_id", "alter table run_record add column collection_task_id integer");
        ensureColumn("run_record", "project_id", "alter table run_record add column project_id integer");
        ensureColumn("run_record", "log_file_path", "alter table run_record add column log_file_path text");
        ensureColumn("run_record", "log_size_bytes", "alter table run_record add column log_size_bytes integer");
        ensureColumn("run_record", "log_charset", "alter table run_record add column log_charset text");

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
                "description text," +
                "content text" +
                ")");
        jdbcTemplate.execute("create index if not exists idx_data_dev_directory_project_parent on data_dev_directory(project_id, parent_id)");
        jdbcTemplate.execute("create index if not exists idx_data_dev_directory_parent on data_dev_directory(parent_id)");
        jdbcTemplate.execute("create index if not exists idx_data_dev_script_project_directory on data_dev_script(project_id, directory_id)");
        jdbcTemplate.execute("create index if not exists idx_data_dev_script_directory on data_dev_script(directory_id)");
        jdbcTemplate.execute("create index if not exists idx_data_dev_script_datasource on data_dev_script(datasource_id)");
        jdbcTemplate.execute("create unique index if not exists uk_datasource_definition_project_name on datasource_definition(project_id, name)");
        jdbcTemplate.execute("create index if not exists idx_datasource_definition_project on datasource_definition(project_id)");
        jdbcTemplate.execute("create unique index if not exists uk_data_model_project_name on data_model(project_id, name)");
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
        jdbcTemplate.execute("create index if not exists idx_run_record_project_created on run_record(project_id, created_at)");
        jdbcTemplate.execute("create index if not exists idx_run_record_project_workflow_run on run_record(project_id, workflow_run_id)");

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
                "worker_code text not null," +
                "enabled integer default 1" +
                ")");
        jdbcTemplate.execute("create unique index if not exists uk_studio_project_worker_binding on studio_project_worker_binding(project_id, worker_code)");

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
        jdbcTemplate.execute("create unique index if not exists uk_data_model_project_name on data_model(project_id, name)");
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
        jdbcTemplate.execute("create index if not exists idx_run_record_project_created on run_record(project_id, created_at)");
        jdbcTemplate.execute("create index if not exists idx_run_record_project_workflow_run on run_record(project_id, workflow_run_id)");

        backfillProjectIdsSqlite();
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

    private void ensureColumn(String tableName, String columnName, String ddl) {
        if (!columnExists(tableName, columnName)) {
            jdbcTemplate.execute(ddl);
        }
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet tables = metaData.getTables(connection.getCatalog(), null, tableName, new String[]{"TABLE"});
            try {
                if (tables.next()) {
                    return true;
                }
            } finally {
                tables.close();
            }
            tables = metaData.getTables(connection.getCatalog(), null, tableName.toUpperCase(Locale.ENGLISH), new String[]{"TABLE"});
            try {
                return tables.next();
            } finally {
                tables.close();
            }
        }));
    }

    private boolean columnExists(String tableName, String columnName) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName, columnName);
            try {
                if (columns.next()) {
                    return true;
                }
            } finally {
                columns.close();
            }
            columns = metaData.getColumns(connection.getCatalog(), null,
                    tableName.toUpperCase(Locale.ENGLISH), columnName.toUpperCase(Locale.ENGLISH));
            try {
                return columns.next();
            } finally {
                columns.close();
            }
        }));
    }

    private void ensureIndex(String tableName, String indexName, String ddl) {
        if (!indexExists(tableName, indexName)) {
            jdbcTemplate.execute(ddl);
        }
    }

    private boolean indexExists(String tableName, String indexName) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, tableName, false, false);
            try {
                while (indexes.next()) {
                    String current = indexes.getString("INDEX_NAME");
                    if (indexName.equalsIgnoreCase(current)) {
                        return true;
                    }
                }
            } finally {
                indexes.close();
            }
            indexes = metaData.getIndexInfo(connection.getCatalog(), null, tableName.toUpperCase(Locale.ENGLISH), false, false);
            try {
                while (indexes.next()) {
                    String current = indexes.getString("INDEX_NAME");
                    if (indexName.equalsIgnoreCase(current)) {
                        return true;
                    }
                }
            } finally {
                indexes.close();
            }
            return false;
        }));
    }
}
