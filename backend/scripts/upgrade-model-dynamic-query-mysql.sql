alter table meta_field_definition add column if not exists searchable_flag int default 0;
alter table meta_field_definition add column if not exists sortable_flag int default 0;
alter table meta_field_definition add column if not exists query_operators json;
alter table meta_field_definition add column if not exists query_default_operator varchar(64);

create table if not exists data_model_attr_index (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
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

alter table data_model_attr_index add key idx_model_attr_index_model (model_id);
alter table data_model_attr_index add key idx_model_attr_index_datasource (datasource_id);
alter table data_model_attr_index add key idx_model_attr_index_lookup (meta_schema_code(128), scope, field_key(128), keyword_value(128));
alter table data_model_attr_index add key idx_model_attr_index_number (meta_schema_code, scope, field_key, number_value);
