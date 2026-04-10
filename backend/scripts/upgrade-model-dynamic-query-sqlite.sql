alter table meta_field_definition add column searchable_flag integer default 0;
alter table meta_field_definition add column sortable_flag integer default 0;
alter table meta_field_definition add column query_operators text;
alter table meta_field_definition add column query_default_operator text;

create table if not exists data_model_attr_index (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    model_id integer,
    datasource_id integer,
    meta_schema_version_id integer,
    meta_schema_code text,
    scope text,
    meta_model_code text,
    item_key text,
    field_key text,
    value_type text,
    keyword_value text,
    text_value text,
    number_value numeric,
    bool_value integer,
    raw_value text
);

alter table data_model_attr_index add column project_id integer;
create index if not exists idx_model_attr_index_project on data_model_attr_index(project_id);
create index if not exists idx_model_attr_index_model on data_model_attr_index(model_id);
create index if not exists idx_model_attr_index_tenant_model_item on data_model_attr_index(tenant_id, model_id, item_key);
create index if not exists idx_model_attr_index_datasource on data_model_attr_index(datasource_id);
create index if not exists idx_model_attr_index_lookup on data_model_attr_index(meta_schema_code, scope, field_key, keyword_value);
create index if not exists idx_model_attr_index_number on data_model_attr_index(meta_schema_code, scope, field_key, number_value);
create index if not exists idx_model_attr_index_tenant_lookup on data_model_attr_index(tenant_id, meta_schema_code, scope, field_key, keyword_value);
create index if not exists idx_model_attr_index_tenant_number on data_model_attr_index(tenant_id, meta_schema_code, scope, field_key, number_value);
drop index if exists uk_data_model_project_name;
create unique index if not exists uk_data_model_project_datasource_name on data_model(project_id, datasource_id, name);
create index if not exists idx_data_model_tenant_project_created on data_model(tenant_id, project_id, created_at);
create index if not exists idx_data_model_tenant_datasource_created on data_model(tenant_id, datasource_id, created_at);

create table if not exists model_sync_task (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    datasource_id integer not null,
    datasource_type text,
    datasource_name_snapshot text,
    batch_no integer not null,
    name text not null,
    source text,
    status text,
    total_count integer default 0,
    success_count integer default 0,
    failed_count integer default 0,
    stopped_count integer default 0,
    progress_percent integer default 0,
    stop_requested integer default 0,
    created_by integer,
    started_at text,
    finished_at text,
    duration_ms integer,
    last_error text
);
create unique index if not exists uk_model_sync_task_project_datasource_batch on model_sync_task(project_id, datasource_id, batch_no);
create index if not exists idx_model_sync_task_project_created on model_sync_task(project_id, created_at);
create index if not exists idx_model_sync_task_project_status on model_sync_task(project_id, status);

create table if not exists model_sync_task_item (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    task_id integer not null,
    seq_no integer not null,
    physical_locator text,
    model_name_snapshot text,
    status text,
    message text,
    started_at text,
    finished_at text,
    duration_ms integer
);
create index if not exists idx_model_sync_task_item_task_seq on model_sync_task_item(task_id, seq_no);
create index if not exists idx_model_sync_task_item_task_status on model_sync_task_item(task_id, status);

create table if not exists field_mapping_rule (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    mapping_name text not null,
    mapping_type text not null,
    mapping_code text not null,
    enabled integer default 1,
    description text,
    created_by integer
);
create index if not exists idx_field_mapping_rule_type_enabled on field_mapping_rule(mapping_type, enabled);
create index if not exists idx_field_mapping_rule_created_at on field_mapping_rule(created_at);

create table if not exists field_mapping_rule_param (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    rule_id integer not null,
    param_name text not null,
    param_order integer not null,
    component_type text not null,
    param_value_json text,
    description text
);
create index if not exists idx_field_mapping_rule_param_rule_order on field_mapping_rule_param(rule_id, param_order);
create index if not exists idx_field_mapping_rule_param_rule_name on field_mapping_rule_param(rule_id, param_name);
