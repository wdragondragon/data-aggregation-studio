create table if not exists sys_user (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    username text not null unique,
    password_hash text not null,
    display_name text,
    enabled integer default 1
);

create table if not exists sys_role (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    code text not null unique,
    name text not null,
    description text
);

create table if not exists sys_permission (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    code text not null unique,
    name text not null,
    http_method text,
    path_pattern text
);

create table if not exists sys_user_role (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    user_id integer,
    role_id integer
);

create table if not exists sys_role_permission (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    role_id integer,
    permission_id integer
);

create table if not exists studio_tenant (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    tenant_code text not null,
    tenant_name text not null,
    description text,
    enabled integer default 1
);

create unique index if not exists uk_studio_tenant_code on studio_tenant(tenant_code);

create table if not exists studio_project (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    project_code text not null,
    project_name text not null,
    description text,
    enabled integer default 1,
    default_project integer default 0
);

create unique index if not exists uk_studio_project_code on studio_project(tenant_id, project_code);
create unique index if not exists uk_studio_project_name on studio_project(tenant_id, project_name);

create table if not exists studio_tenant_member (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    user_id integer not null,
    role_code text not null,
    status text not null
);

create unique index if not exists uk_studio_tenant_member_user on studio_tenant_member(tenant_id, user_id);

create table if not exists studio_project_member (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    project_id integer not null,
    user_id integer not null,
    role_code text not null,
    status text not null
);

create unique index if not exists uk_studio_project_member_user on studio_project_member(project_id, user_id);

create table if not exists studio_project_member_request (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    project_id integer not null,
    user_id integer not null,
    request_type text not null,
    status text not null,
    inviter_user_id integer,
    reviewer_user_id integer,
    reason text,
    review_comment text
);

create index if not exists idx_studio_project_member_request_lookup on studio_project_member_request(project_id, user_id, status);

create table if not exists studio_project_worker_binding (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    project_id integer not null,
    worker_code text not null,
    enabled integer default 1
);

create unique index if not exists uk_studio_project_worker_binding on studio_project_worker_binding(project_id, worker_code);

create table if not exists studio_resource_share (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    source_project_id integer not null,
    target_project_id integer not null,
    resource_type text not null,
    resource_id integer not null,
    shared_by_user_id integer,
    enabled integer default 1
);

create unique index if not exists uk_studio_resource_share_target on studio_resource_share(resource_type, resource_id, target_project_id);
create index if not exists idx_studio_resource_share_project on studio_resource_share(target_project_id);

create table if not exists catalog_plugin (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    plugin_name text,
    plugin_category text,
    asset_type text,
    asset_path text,
    executable integer default 0,
    metadata text,
    template text
);

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
create unique index if not exists uk_field_mapping_rule_code on field_mapping_rule(mapping_code);
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

create table if not exists user_registration_request (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    status text not null,
    username text not null,
    password_hash text not null,
    display_name text,
    reason text,
    review_comment text,
    reviewer_user_id integer,
    approved_user_id integer,
    reviewed_at text
);
create index if not exists idx_user_registration_request_status_created on user_registration_request(status, created_at);
create unique index if not exists uk_user_registration_request_username_status on user_registration_request(username, status);

create table if not exists studio_notification (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    recipient_user_id integer not null,
    tenant_id text,
    project_id integer,
    category text,
    title text,
    content text,
    target_type text,
    target_id integer,
    target_path text,
    target_tenant_id text,
    target_project_id integer,
    dedupe_key text,
    read_at text,
    archived_at text,
    payload_json text
);
create index if not exists idx_studio_notification_recipient_created on studio_notification(recipient_user_id, created_at);
create index if not exists idx_studio_notification_recipient_unread on studio_notification(recipient_user_id, read_at, archived_at);
create unique index if not exists uk_studio_notification_recipient_dedupe on studio_notification(recipient_user_id, dedupe_key);

create table if not exists studio_follow_subscription (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    tenant_id text default 'default',
    project_id integer,
    user_id integer not null,
    target_type text not null,
    target_id integer not null,
    enabled integer default 1
);
create unique index if not exists uk_studio_follow_subscription_target on studio_follow_subscription(tenant_id, project_id, user_id, target_type, target_id);
create index if not exists idx_studio_follow_subscription_lookup on studio_follow_subscription(target_type, target_id, enabled);

create table if not exists meta_schema (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    schema_code text,
    schema_name text,
    object_type text,
    type_code text,
    current_version_id integer,
    status text,
    description text
);

create table if not exists meta_schema_version (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    schema_id integer,
    version_number integer,
    status text,
    description text
);

create table if not exists meta_field_definition (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    schema_version_id integer,
    field_key text,
    field_name text,
    description text,
    scope text,
    value_type text,
    component_type text,
    required_flag integer default 0,
    sensitive_flag integer default 0,
    sort_order integer default 0,
    validation_rule text,
    placeholder text,
    default_value text,
    searchable_flag integer default 0,
    sortable_flag integer default 0,
    query_operators text,
    query_default_operator text,
    options text
);

create table if not exists datasource_definition (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    name text,
    type_code text,
    schema_version_id integer,
    enabled integer default 1,
    executable integer default 0,
    technical_metadata text,
    business_metadata text
);
create unique index if not exists uk_datasource_definition_project_name on datasource_definition(project_id, name);
create index if not exists idx_datasource_definition_project on datasource_definition(project_id);

create table if not exists data_model (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    datasource_id integer,
    name text,
    model_kind text,
    physical_locator text,
    schema_version_id integer,
    technical_metadata text,
    business_metadata text
);
create unique index if not exists uk_data_model_project_datasource_name on data_model(project_id, datasource_id, name);
create index if not exists idx_data_model_project on data_model(project_id);
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

create index if not exists idx_model_attr_index_project on data_model_attr_index(project_id);
create index if not exists idx_model_attr_index_model on data_model_attr_index(model_id);
create index if not exists idx_model_attr_index_tenant_model_item on data_model_attr_index(tenant_id, model_id, item_key);
create index if not exists idx_model_attr_index_datasource on data_model_attr_index(datasource_id);
create index if not exists idx_model_attr_index_lookup on data_model_attr_index(meta_schema_code, scope, field_key, keyword_value);
create index if not exists idx_model_attr_index_number on data_model_attr_index(meta_schema_code, scope, field_key, number_value);
create index if not exists idx_model_attr_index_tenant_lookup on data_model_attr_index(tenant_id, meta_schema_code, scope, field_key, keyword_value);
create index if not exists idx_model_attr_index_tenant_number on data_model_attr_index(tenant_id, meta_schema_code, scope, field_key, number_value);

create table if not exists workflow_definition (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    created_by integer,
    code text,
    name text,
    current_version_id integer,
    published integer default 0
);
create unique index if not exists uk_workflow_definition_project_code on workflow_definition(project_id, code);
create unique index if not exists uk_workflow_definition_project_name on workflow_definition(project_id, name);
create index if not exists idx_workflow_definition_project on workflow_definition(project_id);

create table if not exists workflow_definition_version (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    definition_id integer,
    version_number integer,
    published integer default 0,
    graph_json text,
    schedule_json text
);
create index if not exists idx_workflow_definition_version_project on workflow_definition_version(project_id);

create table if not exists workflow_node (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    workflow_version_id integer,
    node_code text,
    node_name text,
    node_type text,
    config_json text,
    field_mappings_json text
);
create index if not exists idx_workflow_node_project on workflow_node(project_id);

create table if not exists workflow_edge (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    workflow_version_id integer,
    from_node_code text,
    to_node_code text,
    condition_type text
);
create index if not exists idx_workflow_edge_project on workflow_edge(project_id);

create table if not exists workflow_schedule (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    workflow_definition_id integer,
    cron_expression text,
    enabled integer default 0,
    timezone text,
    last_triggered_at text
);
create index if not exists idx_workflow_schedule_project on workflow_schedule(project_id);

create table if not exists collection_task_definition (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    created_by integer,
    name text,
    task_type text,
    status text,
    source_count integer default 1,
    source_bindings_json text,
    target_binding_json text,
    field_mappings_json text,
    execution_options_json text
);
create unique index if not exists uk_collection_task_definition_project_name on collection_task_definition(project_id, name);
create index if not exists idx_collection_task_definition_project on collection_task_definition(project_id);

create table if not exists collection_task_schedule (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    collection_task_id integer,
    cron_expression text,
    enabled integer default 0,
    timezone text,
    last_triggered_at text
);
create index if not exists idx_collection_task_schedule_project on collection_task_schedule(project_id);

create table if not exists data_dev_directory (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    parent_id integer,
    name text,
    permission_code text,
    description text
);

create table if not exists data_dev_script (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    directory_id integer,
    file_name text,
    script_type text,
    datasource_id integer,
    description text,
    content text
);

create index if not exists idx_data_dev_directory_project_parent on data_dev_directory(project_id, parent_id);
create index if not exists idx_data_dev_directory_parent on data_dev_directory(parent_id);
create index if not exists idx_data_dev_script_project_directory on data_dev_script(project_id, directory_id);
create index if not exists idx_data_dev_script_directory on data_dev_script(directory_id);
create index if not exists idx_data_dev_script_datasource on data_dev_script(datasource_id);

create table if not exists dispatch_task (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    execution_type text,
    workflow_run_id integer,
    workflow_definition_id integer,
    workflow_version_id integer,
    collection_task_id integer,
    triggered_by_user_id integer,
    run_record_id integer,
    node_code text,
    status text,
    lease_owner text,
    lease_expires_at text,
    attempts integer default 0,
    max_retries integer default 3,
    payload_json text
);
create index if not exists idx_dispatch_task_project_status on dispatch_task(project_id, status);
create index if not exists idx_dispatch_task_project_workflow_run on dispatch_task(project_id, workflow_run_id);

create table if not exists run_record (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    execution_type text,
    workflow_run_id integer,
    workflow_definition_id integer,
    workflow_version_id integer,
    collection_task_id integer,
    triggered_by_user_id integer,
    node_code text,
    status text,
    worker_code text,
    message text,
    started_at text,
    ended_at text,
    collected_records integer,
    read_succeed_records integer,
    read_failed_records integer,
    write_succeed_records integer,
    write_failed_records integer,
    failed_records integer,
    transformer_total_records integer,
    transformer_success_records integer,
    transformer_failed_records integer,
    transformer_filter_records integer,
    log_file_path text,
    log_size_bytes integer,
    log_charset text,
    payload_json text,
    result_json text
);
create index if not exists idx_run_record_project_created on run_record(project_id, created_at);
create index if not exists idx_run_record_project_workflow_run on run_record(project_id, workflow_run_id);
create index if not exists idx_run_record_project_collection_task_ended on run_record(project_id, collection_task_id, ended_at);

create table if not exists worker_lease (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    worker_code text,
    worker_kind text,
    host_name text,
    status text,
    last_heartbeat_at text,
    capabilities_json text
);
