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
