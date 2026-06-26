-- Data Aggregation Studio Java 脚本运行环境增量脚本

set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_dev_script' and column_name = 'environment_id') = 0,
  'alter table data_dev_script add column environment_id bigint',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

create table if not exists so_pf_env_dep (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    name varchar(255) not null,
    version varchar(128),
    script_type varchar(32) default 'JAVA',
    artifact_url text,
    artifact_type varchar(32),
    checksum varchar(128),
    enabled int default 1,
    description text
);

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'so_pf_env_dep' and column_name = 'script_type') = 0,
  'alter table so_pf_env_dep add column script_type varchar(32) default ''JAVA'' after version',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

update so_pf_env_dep set script_type = 'JAVA' where script_type is null or script_type = '';

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
    description text
);

create table if not exists so_pf_env_dep_rel (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    environment_id bigint not null,
    dependency_id bigint not null,
    sort_order int default 0
);

set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'data_dev_script' and index_name = 'idx_data_dev_script_environment') = 0,
  'alter table data_dev_script add key idx_data_dev_script_environment (environment_id)',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'so_pf_env_dep' and index_name = 'idx_so_pf_env_dep_tenant_enabled') = 0,
  'alter table so_pf_env_dep add key idx_so_pf_env_dep_tenant_enabled (tenant_id, enabled)',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'so_pf_env_dep' and index_name = 'uk_so_pf_env_dep_name_ver') = 0,
  'alter table so_pf_env_dep add unique key uk_so_pf_env_dep_name_ver (tenant_id, name, version)',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'so_pf_script_env' and index_name = 'uk_so_pf_script_env_code') = 0,
  'alter table so_pf_script_env add unique key uk_so_pf_script_env_code (tenant_id, environment_code)',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'so_pf_script_env' and index_name = 'idx_so_pf_script_env_enabled') = 0,
  'alter table so_pf_script_env add key idx_so_pf_script_env_enabled (tenant_id, enabled)',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'so_pf_env_dep_rel' and index_name = 'idx_so_pf_env_dep_rel_env') = 0,
  'alter table so_pf_env_dep_rel add key idx_so_pf_env_dep_rel_env (environment_id, sort_order)',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'so_pf_env_dep_rel' and index_name = 'uk_so_pf_env_dep_rel') = 0,
  'alter table so_pf_env_dep_rel add unique key uk_so_pf_env_dep_rel (environment_id, dependency_id)',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
