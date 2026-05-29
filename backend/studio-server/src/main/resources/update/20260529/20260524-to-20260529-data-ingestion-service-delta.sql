-- Data Aggregation Studio increment schema script
-- Base: 2026-05-24 production version.
-- Target: add data ingestion service storage and token-optional access settings.
-- Scope: table structures only. This script does not create test data or service definitions.

create table if not exists data_ingestion_service (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    created_by bigint,
    service_code varchar(128) not null,
    service_name varchar(255) not null,
    status varchar(64) not null,
    request_format varchar(32) not null,
    payload_mode varchar(32),
    data_node_path varchar(500),
    target_type varchar(32) not null,
    datasource_id bigint,
    datasource_name_snapshot varchar(255),
    datasource_type_code varchar(128),
    model_id bigint,
    model_name_snapshot varchar(255),
    model_physical_locator varchar(1000),
    endpoint_path varchar(1000),
    service_key varchar(128),
    max_batch_size int default 500,
    token_required int default 1,
    default_subscription_name varchar(255),
    writer_options_json json,
    field_mappings_json json,
    unique key uk_data_ingestion_project_code (tenant_id, project_id, service_code),
    key idx_data_ingestion_project_status (project_id, status),
    key idx_data_ingestion_code_key (service_code, service_key)
);

create table if not exists data_ingestion_subscription (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    service_id bigint not null,
    subscription_name varchar(255) not null,
    token_hash varchar(128) not null,
    enabled int default 1,
    created_by bigint,
    last_used_at datetime,
    key idx_data_ingestion_sub_service_enabled (service_id, enabled),
    key idx_data_ingestion_sub_token (token_hash)
);

create table if not exists data_ingestion_access_log (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    service_id bigint,
    service_code_snapshot varchar(255),
    service_name_snapshot varchar(255),
    service_status_snapshot varchar(64),
    subscription_id bigint,
    subscription_name_snapshot varchar(255),
    request_id varchar(128),
    request_method varchar(16),
    occurred_at datetime,
    duration_ms bigint,
    success int default 0,
    http_status int,
    error_code varchar(128),
    error_message varchar(1000),
    system_log mediumtext,
    client_ip varchar(128),
    user_agent varchar(500),
    received_count bigint default 0,
    success_count bigint default 0,
    failed_count bigint default 0,
    key idx_data_ingestion_access_project_time (tenant_id, project_id, occurred_at),
    key idx_data_ingestion_access_service_time (service_id, occurred_at),
    key idx_data_ingestion_access_subscription_time (subscription_id, occurred_at),
    key idx_data_ingestion_access_success (project_id, success, occurred_at)
);

create table if not exists data_ingestion_access_counter (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    service_id bigint not null default 0,
    subscription_id bigint not null default 0,
    bucket_start datetime not null,
    success int not null default 0,
    access_count bigint default 0,
    received_count bigint default 0,
    success_count bigint default 0,
    failed_count bigint default 0,
    unique key uk_data_ingestion_access_counter (tenant_id, project_id, service_id, subscription_id, bucket_start, success),
    key idx_data_ingestion_counter_project_bucket (tenant_id, project_id, bucket_start),
    key idx_data_ingestion_counter_service_bucket (service_id, bucket_start)
);

-- Data service token-optional access settings.
set @ddl = (
    select if(count(*) = 0,
        'alter table data_service_definition add column token_required int default 1 after cache_enabled',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_service_definition'
      and column_name = 'token_required'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_service_definition add column default_subscription_name varchar(255) after token_required',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_service_definition'
      and column_name = 'default_subscription_name'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

alter table data_service_definition modify column token_required int default 1;
update data_service_definition set token_required = 1 where token_required is null;

-- Compatibility for environments that already created data ingestion tables through automatic upgrade.
set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_service add column token_required int default 1 after max_batch_size',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_ingestion_service'
      and column_name = 'token_required'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_service add column default_subscription_name varchar(255) after token_required',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_ingestion_service'
      and column_name = 'default_subscription_name'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

alter table data_ingestion_service modify column token_required int default 1;
update data_ingestion_service set token_required = 1 where token_required is null;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_access_log add column system_log mediumtext after error_message',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_ingestion_access_log'
      and column_name = 'system_log'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

alter table data_ingestion_access_log modify column system_log mediumtext;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_service add unique key uk_data_ingestion_project_code (tenant_id, project_id, service_code)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_service'
      and index_name = 'uk_data_ingestion_project_code'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_service add key idx_data_ingestion_project_status (project_id, status)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_service'
      and index_name = 'idx_data_ingestion_project_status'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_service add key idx_data_ingestion_code_key (service_code, service_key)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_service'
      and index_name = 'idx_data_ingestion_code_key'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_subscription add key idx_data_ingestion_sub_service_enabled (service_id, enabled)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_subscription'
      and index_name = 'idx_data_ingestion_sub_service_enabled'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_subscription add key idx_data_ingestion_sub_token (token_hash)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_subscription'
      and index_name = 'idx_data_ingestion_sub_token'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_access_log add key idx_data_ingestion_access_project_time (tenant_id, project_id, occurred_at)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_access_log'
      and index_name = 'idx_data_ingestion_access_project_time'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_access_log add key idx_data_ingestion_access_service_time (service_id, occurred_at)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_access_log'
      and index_name = 'idx_data_ingestion_access_service_time'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_access_log add key idx_data_ingestion_access_subscription_time (subscription_id, occurred_at)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_access_log'
      and index_name = 'idx_data_ingestion_access_subscription_time'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_access_log add key idx_data_ingestion_access_success (project_id, success, occurred_at)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_access_log'
      and index_name = 'idx_data_ingestion_access_success'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_access_counter add unique key uk_data_ingestion_access_counter (tenant_id, project_id, service_id, subscription_id, bucket_start, success)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_access_counter'
      and index_name = 'uk_data_ingestion_access_counter'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_access_counter add key idx_data_ingestion_counter_project_bucket (tenant_id, project_id, bucket_start)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_access_counter'
      and index_name = 'idx_data_ingestion_counter_project_bucket'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_access_counter add key idx_data_ingestion_counter_service_bucket (service_id, bucket_start)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'data_ingestion_access_counter'
      and index_name = 'idx_data_ingestion_counter_service_bucket'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
