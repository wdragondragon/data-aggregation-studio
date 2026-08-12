-- Java/Python dependency and artifact repository management.
--
-- This migration mirrors the baseline definitions in schema-mysql.sql so that
-- existing Studio databases receive the tables during an incremental upgrade.
-- All statements are idempotent and do not create default repository records.

create table if not exists so_pf_artifact_store (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    store_name varchar(255) not null,
    store_code varchar(64) not null,
    provider varchar(32) not null,
    scope_type varchar(32) default 'TENANT',
    config_version bigint default 1,
    endpoint varchar(1000) not null,
    upload_url varchar(1000),
    simple_index_url varchar(1000),
    bucket varchar(255),
    region varchar(128),
    root_prefix varchar(512),
    username_ciphertext text,
    secret_ciphertext text,
    verify_ssl int default 1,
    enabled int default 1,
    description text,
    unique key uk_so_pf_artifact_store_code (tenant_id, store_code),
    key idx_so_pf_artifact_store_provider (tenant_id, scope_type, provider, enabled)
);

create table if not exists so_pf_env_dep (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    name varchar(255) not null,
    version varchar(128),
    script_type varchar(32) default 'JAVA',
    artifact_store_id bigint,
    artifact_url text,
    artifact_type varchar(32),
    checksum varchar(128),
    enabled int default 1,
    description text,
    key idx_so_pf_env_dep_tenant_enabled (tenant_id, enabled),
    unique key uk_so_pf_env_dep_name_ver (tenant_id, name, version)
);

create table if not exists so_pf_env_dep_file (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    dependency_id bigint not null,
    original_file_name varchar(512) not null,
    artifact_type varchar(32) not null,
    object_key text,
    object_url text,
    checksum varchar(128),
    size_bytes bigint,
    visible int default 1,
    runtime_artifact int default 0,
    source_file_id bigint,
    enabled int default 1,
    key idx_so_pf_env_dep_file_dep (tenant_id, dependency_id, visible),
    key idx_so_pf_env_dep_file_runtime (tenant_id, dependency_id, runtime_artifact)
);

create table if not exists so_pf_script_env (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    environment_name varchar(255) not null,
    environment_code varchar(128) not null,
    enabled int default 1,
    use_application_parent int default 1,
    environment_version bigint default 1,
    python_install_mode varchar(32) default 'LOCAL_ARTIFACT',
    python_repository_id bigint,
    description text,
    unique key uk_so_pf_script_env_code (tenant_id, environment_code),
    key idx_so_pf_script_env_enabled (tenant_id, enabled)
);

create table if not exists so_pf_env_dep_rel (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    environment_id bigint not null,
    dependency_id bigint not null,
    sort_order int default 0,
    key idx_so_pf_env_dep_rel_env (environment_id, sort_order),
    unique key uk_so_pf_env_dep_rel (environment_id, dependency_id)
);
