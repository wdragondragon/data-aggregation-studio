alter table meta_field_definition add column if not exists searchable_flag int default 0;
alter table meta_field_definition add column if not exists sortable_flag int default 0;
alter table meta_field_definition add column if not exists query_operators json;
alter table meta_field_definition add column if not exists query_default_operator varchar(64);

create table if not exists data_model_attr_index (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    model_id bigint,
    datasource_id bigint,
    meta_schema_version_id bigint,
    meta_schema_code varchar(255),
    scope varchar(64),
    meta_model_code varchar(128),
    item_key varchar(255),
    field_key varchar(255),
    value_type varchar(64),
    keyword_value varchar(1000),
    text_value text,
    number_value decimal(38, 10),
    bool_value int,
    raw_value text
);

alter table data_model_attr_index add column if not exists project_id bigint;
alter table data_model_attr_index add key idx_model_attr_index_project (project_id);
alter table data_model_attr_index add key idx_model_attr_index_model (model_id);
alter table data_model_attr_index add key idx_model_attr_index_tenant_model_item (tenant_id, model_id, item_key);
alter table data_model_attr_index add key idx_model_attr_index_datasource (datasource_id);
alter table data_model_attr_index add key idx_model_attr_index_lookup (meta_schema_code(128), scope, field_key(128), keyword_value(128));
alter table data_model_attr_index add key idx_model_attr_index_number (meta_schema_code, scope, field_key, number_value);
alter table data_model_attr_index add key idx_model_attr_index_tenant_lookup (tenant_id, meta_schema_code(128), scope, field_key(128), keyword_value(128));
alter table data_model_attr_index add key idx_model_attr_index_tenant_number (tenant_id, meta_schema_code(128), scope, field_key(128), number_value);
alter table data_model drop index uk_data_model_project_name;
alter table data_model add unique key uk_data_model_project_datasource_name (project_id, datasource_id, name);
alter table data_model add key idx_data_model_tenant_project_created (tenant_id, project_id, created_at);
alter table data_model add key idx_data_model_tenant_datasource_created (tenant_id, datasource_id, created_at);

create table if not exists model_sync_task (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    datasource_id bigint not null,
    datasource_type varchar(128),
    datasource_name_snapshot varchar(255),
    batch_no int not null,
    name varchar(255) not null,
    source varchar(64),
    status varchar(64),
    total_count int default 0,
    success_count int default 0,
    failed_count int default 0,
    stopped_count int default 0,
    progress_percent int default 0,
    stop_requested int default 0,
    created_by bigint,
    started_at datetime,
    finished_at datetime,
    duration_ms bigint,
    last_error varchar(2000)
);
alter table model_sync_task add key idx_model_sync_task_project_created (project_id, created_at);
alter table model_sync_task add key idx_model_sync_task_project_status (project_id, status);
alter table model_sync_task add unique key uk_model_sync_task_project_datasource_batch (project_id, datasource_id, batch_no);

create table if not exists model_sync_task_item (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    task_id bigint not null,
    seq_no int not null,
    physical_locator varchar(1000),
    model_name_snapshot varchar(255),
    status varchar(64),
    message varchar(2000),
    started_at datetime,
    finished_at datetime,
    duration_ms bigint
);
alter table model_sync_task_item add key idx_model_sync_task_item_task_seq (task_id, seq_no);
alter table model_sync_task_item add key idx_model_sync_task_item_task_status (task_id, status);

create table if not exists field_mapping_rule (
    id bigint primary key,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    mapping_name varchar(255) not null,
    mapping_type varchar(255) not null,
    mapping_code varchar(255) not null,
    enabled int default 1,
    description varchar(1000),
    created_by bigint
);
alter table field_mapping_rule add key idx_field_mapping_rule_type_enabled (mapping_type, enabled);
alter table field_mapping_rule add key idx_field_mapping_rule_created_at (created_at);

create table if not exists field_mapping_rule_param (
    id bigint primary key,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    rule_id bigint not null,
    param_name varchar(255) not null,
    param_order int not null,
    component_type varchar(64) not null,
    param_value_json text,
    description varchar(1000)
);
alter table field_mapping_rule_param add key idx_field_mapping_rule_param_rule_order (rule_id, param_order);
alter table field_mapping_rule_param add key idx_field_mapping_rule_param_rule_name (rule_id, param_name);
