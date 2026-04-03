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
    options json
);

create table if not exists datasource_definition (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    name varchar(255),
    type_code varchar(128),
    schema_version_id bigint,
    enabled int default 1,
    executable int default 0,
    technical_metadata json,
    business_metadata json
);

create table if not exists data_model (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    datasource_id bigint,
    name varchar(255),
    model_kind varchar(64),
    physical_locator varchar(1000),
    schema_version_id bigint,
    technical_metadata json,
    business_metadata json
);

create table if not exists workflow_definition (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    code varchar(255),
    name varchar(255),
    current_version_id bigint,
    published int default 0
);

create table if not exists workflow_definition_version (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    definition_id bigint,
    version_number int,
    published int default 0,
    graph_json json,
    schedule_json json
);

create table if not exists workflow_node (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    workflow_version_id bigint,
    node_code varchar(255),
    node_name varchar(255),
    node_type varchar(128),
    config_json json,
    field_mappings_json json
);

create table if not exists workflow_edge (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    workflow_version_id bigint,
    from_node_code varchar(255),
    to_node_code varchar(255),
    condition_type varchar(64)
);

create table if not exists workflow_schedule (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    workflow_definition_id bigint,
    cron_expression varchar(255),
    enabled int default 0,
    timezone varchar(64)
);

create table if not exists dispatch_task (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    workflow_definition_id bigint,
    workflow_version_id bigint,
    node_code varchar(255),
    status varchar(64),
    lease_owner varchar(255),
    lease_expires_at datetime,
    attempts int default 0,
    max_retries int default 3,
    payload_json json
);

create table if not exists run_record (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    workflow_definition_id bigint,
    workflow_version_id bigint,
    node_code varchar(255),
    status varchar(64),
    worker_code varchar(255),
    message varchar(2000),
    started_at datetime,
    ended_at datetime,
    payload_json json,
    result_json json
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
