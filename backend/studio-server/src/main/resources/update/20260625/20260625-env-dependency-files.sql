-- Script environment dependency multi-file delta.
-- Adds uploaded file details for dependency packages. Runtime uses runtime_artifact JAR rows first.

set @schema_name = database();

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
    enabled int default 1
);

set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'so_pf_env_dep_file' and index_name = 'idx_so_pf_env_dep_file_dep') = 0,
  'alter table so_pf_env_dep_file add key idx_so_pf_env_dep_file_dep (tenant_id, dependency_id, visible)',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'so_pf_env_dep_file' and index_name = 'idx_so_pf_env_dep_file_runtime') = 0,
  'alter table so_pf_env_dep_file add key idx_so_pf_env_dep_file_runtime (tenant_id, dependency_id, runtime_artifact)',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
