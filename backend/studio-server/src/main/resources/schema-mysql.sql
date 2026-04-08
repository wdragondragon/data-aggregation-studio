create table if not exists sys_user (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    username varchar(128) not null,
    password_hash varchar(255) not null,
    display_name varchar(255),
    enabled int default 1,
    unique key uk_sys_user_username (username)
);

create table if not exists sys_role (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    code varchar(128) not null,
    name varchar(255) not null,
    description varchar(500),
    unique key uk_sys_role_code (code)
);

create table if not exists sys_permission (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    code varchar(128) not null,
    name varchar(255) not null,
    http_method varchar(32),
    path_pattern varchar(255),
    unique key uk_sys_permission_code (code)
);

create table if not exists sys_user_role (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    user_id bigint not null,
    role_id bigint not null
);

create table if not exists sys_role_permission (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    role_id bigint not null,
    permission_id bigint not null
);

create table if not exists studio_tenant (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    tenant_code varchar(64) not null,
    tenant_name varchar(255) not null,
    description varchar(1000),
    enabled int default 1,
    unique key uk_studio_tenant_code (tenant_code)
);

create table if not exists studio_project (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    project_code varchar(128) not null,
    project_name varchar(255) not null,
    description varchar(1000),
    enabled int default 1,
    default_project int default 0,
    unique key uk_studio_project_code (tenant_id, project_code),
    unique key uk_studio_project_name (tenant_id, project_name)
);

create table if not exists studio_tenant_member (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    user_id bigint not null,
    role_code varchar(128) not null,
    status varchar(64) not null,
    unique key uk_studio_tenant_member_user (tenant_id, user_id)
);

create table if not exists studio_project_member (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    project_id bigint not null,
    user_id bigint not null,
    role_code varchar(128) not null,
    status varchar(64) not null,
    unique key uk_studio_project_member_user (project_id, user_id)
);

create table if not exists studio_project_member_request (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    project_id bigint not null,
    user_id bigint not null,
    request_type varchar(64) not null,
    status varchar(64) not null,
    inviter_user_id bigint,
    reviewer_user_id bigint,
    reason varchar(1000),
    review_comment varchar(1000),
    key idx_studio_project_member_request_lookup (project_id, user_id, status)
);

create table if not exists studio_project_worker_binding (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    project_id bigint not null,
    worker_code varchar(255) not null,
    enabled int default 1,
    unique key uk_studio_project_worker_binding (project_id, worker_code)
);

create table if not exists studio_resource_share (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    source_project_id bigint not null,
    target_project_id bigint not null,
    resource_type varchar(128) not null,
    resource_id bigint not null,
    shared_by_user_id bigint,
    enabled int default 1,
    unique key uk_studio_resource_share_target (resource_type, resource_id, target_project_id),
    key idx_studio_resource_share_project (target_project_id)
);

create table if not exists catalog_plugin (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    plugin_name varchar(255),
    plugin_category varchar(64),
    asset_type varchar(128),
    asset_path varchar(1024),
    executable int default 0,
    metadata json,
    template json
);

create table if not exists meta_schema (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    schema_code varchar(255),
    schema_name varchar(255),
    object_type varchar(128),
    type_code varchar(255),
    current_version_id bigint,
    status varchar(64),
    description varchar(1000)
);

create table if not exists meta_schema_version (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    schema_id bigint,
    version_number int,
    status varchar(64),
    description varchar(1000)
);

create table if not exists meta_field_definition (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    schema_version_id bigint,
    field_key varchar(255),
    field_name varchar(255),
    description varchar(1000),
    scope varchar(64),
    value_type varchar(64),
    component_type varchar(64),
    required_flag int default 0,
    sensitive_flag int default 0,
    sort_order int default 0,
    validation_rule varchar(1000),
    placeholder varchar(255),
    default_value varchar(1000),
    searchable_flag int default 0,
    sortable_flag int default 0,
    query_operators json,
    query_default_operator varchar(64),
    options json
);

create table if not exists datasource_definition (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    name varchar(255),
    type_code varchar(128),
    schema_version_id bigint,
    enabled int default 1,
    executable int default 0,
    technical_metadata json,
    business_metadata json,
    unique key uk_datasource_definition_project_name (project_id, name),
    key idx_datasource_definition_project (project_id)
);

create table if not exists data_model (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    datasource_id bigint,
    name varchar(255),
    model_kind varchar(64),
    physical_locator varchar(1000),
    schema_version_id bigint,
    technical_metadata json,
    business_metadata json,
    unique key uk_data_model_project_name (project_id, name),
    key idx_data_model_project (project_id)
);

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
    raw_value text,
    key idx_model_attr_index_project (project_id),
    key idx_model_attr_index_model (model_id),
    key idx_model_attr_index_datasource (datasource_id),
    key idx_model_attr_index_lookup (meta_schema_code(128), scope, field_key(128), keyword_value(128)),
    key idx_model_attr_index_number (meta_schema_code, scope, field_key, number_value)
);

create table if not exists workflow_definition (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    code varchar(255),
    name varchar(255),
    current_version_id bigint,
    published int default 0,
    unique key uk_workflow_definition_project_code (project_id, code),
    unique key uk_workflow_definition_project_name (project_id, name),
    key idx_workflow_definition_project (project_id)
);

create table if not exists workflow_definition_version (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    definition_id bigint,
    version_number int,
    published int default 0,
    graph_json json,
    schedule_json json,
    key idx_workflow_definition_version_project (project_id)
);

create table if not exists workflow_node (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    workflow_version_id bigint,
    node_code varchar(255),
    node_name varchar(255),
    node_type varchar(128),
    config_json json,
    field_mappings_json json,
    key idx_workflow_node_project (project_id)
);

create table if not exists workflow_edge (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    workflow_version_id bigint,
    from_node_code varchar(255),
    to_node_code varchar(255),
    condition_type varchar(64),
    key idx_workflow_edge_project (project_id)
);

create table if not exists workflow_schedule (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    workflow_definition_id bigint,
    cron_expression varchar(255),
    enabled int default 0,
    timezone varchar(64),
    last_triggered_at datetime,
    key idx_workflow_schedule_project (project_id)
);

create table if not exists collection_task_definition (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    name varchar(255),
    task_type varchar(64),
    status varchar(64),
    source_count int default 1,
    source_bindings_json json,
    target_binding_json json,
    field_mappings_json json,
    execution_options_json json,
    unique key uk_collection_task_definition_project_name (project_id, name),
    key idx_collection_task_definition_project (project_id)
);

create table if not exists collection_task_schedule (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    collection_task_id bigint,
    cron_expression varchar(255),
    enabled int default 0,
    timezone varchar(64),
    last_triggered_at datetime,
    key idx_collection_task_schedule_project (project_id)
);

create table if not exists data_dev_directory (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    parent_id bigint,
    name varchar(255),
    permission_code varchar(255),
    description varchar(1000),
    key idx_data_dev_directory_parent (parent_id)
);

create table if not exists data_dev_script (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    directory_id bigint,
    file_name varchar(255),
    script_type varchar(64),
    datasource_id bigint,
    description varchar(1000),
    content longtext,
    key idx_data_dev_script_directory (directory_id),
    key idx_data_dev_script_datasource (datasource_id)
);

create table if not exists dispatch_task (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    execution_type varchar(64),
    workflow_run_id bigint,
    workflow_definition_id bigint,
    workflow_version_id bigint,
    collection_task_id bigint,
    run_record_id bigint,
    node_code varchar(255),
    status varchar(64),
    lease_owner varchar(255),
    lease_expires_at datetime,
    attempts int default 0,
    max_retries int default 3,
    payload_json json,
    key idx_dispatch_task_project_status (project_id, status),
    key idx_dispatch_task_project_workflow_run (project_id, workflow_run_id)
);

create table if not exists run_record (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    execution_type varchar(64),
    workflow_run_id bigint,
    workflow_definition_id bigint,
    workflow_version_id bigint,
    collection_task_id bigint,
    node_code varchar(255),
    status varchar(64),
    worker_code varchar(255),
    message varchar(2000),
    started_at datetime,
    ended_at datetime,
    log_file_path varchar(1000),
    log_size_bytes bigint,
    log_charset varchar(64),
    payload_json json,
    result_json json,
    key idx_run_record_project_created (project_id, created_at),
    key idx_run_record_project_workflow_run (project_id, workflow_run_id)
);

create table if not exists worker_lease (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    worker_code varchar(255),
    worker_kind varchar(64),
    host_name varchar(255),
    status varchar(64),
    last_heartbeat_at datetime,
    capabilities_json json
);
