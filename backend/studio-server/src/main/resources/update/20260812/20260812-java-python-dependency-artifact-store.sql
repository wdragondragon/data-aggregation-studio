-- Java/Python dependency and artifact repository management delta.
-- Mirrors the schema changes introduced by commit 4bbe04fc.

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

set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns
    where table_schema = @schema_name
      and table_name = 'so_pf_env_dep'
      and column_name = 'artifact_store_id') = 0,
  'alter table so_pf_env_dep add column artifact_store_id bigint after script_type',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns
    where table_schema = @schema_name
      and table_name = 'so_pf_script_env'
      and column_name = 'python_install_mode') = 0,
  'alter table so_pf_script_env add column python_install_mode varchar(32) default ''LOCAL_ARTIFACT'' after environment_version',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns
    where table_schema = @schema_name
      and table_name = 'so_pf_script_env'
      and column_name = 'python_repository_id') = 0,
  'alter table so_pf_script_env add column python_repository_id bigint after python_install_mode',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
