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
alter table field_mapping_rule add unique key uk_field_mapping_rule_code (mapping_code);
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

create table if not exists user_registration_request (
    id bigint primary key,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    status varchar(64) not null,
    username varchar(128) not null,
    password_hash varchar(255) not null,
    display_name varchar(255),
    reason varchar(1000),
    review_comment varchar(1000),
    reviewer_user_id bigint,
    approved_user_id bigint,
    reviewed_at datetime
);
alter table user_registration_request add key idx_user_registration_request_status_created (status, created_at);
alter table user_registration_request add unique key uk_user_registration_request_username_status (username, status);

create table if not exists studio_notification (
    id bigint primary key,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    recipient_user_id bigint not null,
    tenant_id varchar(64),
    project_id bigint,
    category varchar(128),
    title varchar(255),
    content varchar(2000),
    target_type varchar(128),
    target_id bigint,
    target_path varchar(1000),
    target_tenant_id varchar(64),
    target_project_id bigint,
    dedupe_key varchar(255),
    read_at datetime,
    archived_at datetime,
    payload_json json
);
alter table studio_notification add key idx_studio_notification_recipient_created (recipient_user_id, created_at);
alter table studio_notification add key idx_studio_notification_recipient_unread (recipient_user_id, read_at, archived_at);
alter table studio_notification add unique key uk_studio_notification_recipient_dedupe (recipient_user_id, dedupe_key);

create table if not exists studio_follow_subscription (
    id bigint primary key,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    user_id bigint not null,
    target_type varchar(128) not null,
    target_id bigint not null,
    enabled int default 1
);
alter table studio_follow_subscription add unique key uk_studio_follow_subscription_target (tenant_id, project_id, user_id, target_type, target_id);
alter table studio_follow_subscription add key idx_studio_follow_subscription_lookup (target_type, target_id, enabled);

alter table collection_task_definition add column if not exists created_by bigint;
alter table workflow_definition add column if not exists created_by bigint;
alter table dispatch_task add column if not exists triggered_by_user_id bigint;
alter table run_record add column if not exists triggered_by_user_id bigint;

alter table run_record add column if not exists collected_records bigint;
alter table run_record add column if not exists read_succeed_records bigint;
alter table run_record add column if not exists read_failed_records bigint;
alter table run_record add column if not exists write_succeed_records bigint;
alter table run_record add column if not exists write_failed_records bigint;
alter table run_record add column if not exists failed_records bigint;
alter table run_record add column if not exists transformer_total_records bigint;
alter table run_record add column if not exists transformer_success_records bigint;
alter table run_record add column if not exists transformer_failed_records bigint;
alter table run_record add column if not exists transformer_filter_records bigint;
alter table run_record add key idx_run_record_project_collection_task_ended (project_id, collection_task_id, ended_at);
