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
    options text
);

create table if not exists datasource_definition (
    id integer primary key,
    tenant_id text default 'default',
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

create table if not exists data_model (
    id integer primary key,
    tenant_id text default 'default',
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

create table if not exists workflow_definition (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    code text,
    name text,
    current_version_id integer,
    published integer default 0
);

create table if not exists workflow_definition_version (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    definition_id integer,
    version_number integer,
    published integer default 0,
    graph_json text,
    schedule_json text
);

create table if not exists workflow_node (
    id integer primary key,
    tenant_id text default 'default',
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

create table if not exists workflow_edge (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    workflow_version_id integer,
    from_node_code text,
    to_node_code text,
    condition_type text
);

create table if not exists workflow_schedule (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    workflow_definition_id integer,
    cron_expression text,
    enabled integer default 0,
    timezone text
);

create table if not exists dispatch_task (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    workflow_definition_id integer,
    workflow_version_id integer,
    node_code text,
    status text,
    lease_owner text,
    lease_expires_at text,
    attempts integer default 0,
    max_retries integer default 3,
    payload_json text
);

create table if not exists run_record (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    workflow_definition_id integer,
    workflow_version_id integer,
    node_code text,
    status text,
    worker_code text,
    message text,
    started_at text,
    ended_at text,
    payload_json text,
    result_json text
);

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
