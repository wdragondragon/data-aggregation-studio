-- P0-MC-01: configurable runtime clusters and datasource applicability.
--
-- This script is intentionally idempotent. It can be run before or after the
-- application startup upgrader. It does not create default cluster records or
-- infer datasource reachability; those are an administrator-controlled rollout.
-- Apply it only after the baseline Studio schema exists; additive ALTER statements
-- deliberately fail instead of silently accepting a partial/corrupt baseline.

create table if not exists studio_runtime_cluster (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    code varchar(64) not null,
    name varchar(255) not null,
    enabled int default 1,
    status varchar(32) default 'UNKNOWN',
    version varchar(128),
    last_heartbeat_at datetime,
    instances_json json,
    unique key uk_runtime_cluster_tenant_code (tenant_id, code),
    key idx_runtime_cluster_tenant_enabled (tenant_id, enabled)
);

create table if not exists studio_runtime_endpoint (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    runtime_cluster_id bigint not null,
    mode varchar(32) not null,
    endpoint_ciphertext text,
    headers_ciphertext text,
    token_ciphertext text,
    connect_timeout_millis int default 3000,
    read_timeout_millis int default 5000,
    enabled int default 1,
    last_tested_at datetime,
    last_test_status varchar(32),
    last_test_message varchar(1000),
    key idx_runtime_endpoint_cluster (tenant_id, runtime_cluster_id, enabled)
);

create table if not exists studio_project_runtime_cluster (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint not null,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    runtime_cluster_id bigint not null,
    enabled int default 1,
    preferred int default 0,
    allow_manual_override int default 0,
    unique key uk_project_runtime_cluster (tenant_id, project_id, runtime_cluster_id),
    key idx_project_runtime_cluster_options (tenant_id, project_id, enabled, preferred)
);

create table if not exists datasource_cluster_binding (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    datasource_id bigint not null,
    runtime_cluster_id bigint not null,
    enabled int default 1,
    unique key uk_datasource_cluster_binding (tenant_id, datasource_id, runtime_cluster_id),
    key idx_datasource_cluster_options (tenant_id, runtime_cluster_id, enabled, datasource_id)
);

create table if not exists studio_runtime_validation (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint not null,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    resource_type varchar(64) not null,
    resource_id bigint not null,
    runtime_cluster_id bigint,
    valid int default 1,
    issue_code varchar(128),
    issue_message varchar(1000),
    details_json json,
    validated_at datetime,
    unique key uk_runtime_validation_resource (tenant_id, project_id, resource_type, resource_id),
    key idx_runtime_validation_invalid (tenant_id, project_id, valid, resource_type)
);

-- Additive fields remain nullable so the one-time migration can inspect and
-- backfill historical rows before unified explicit-cluster runtime starts.
set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'datasource_connection_health' and column_name = 'runtime_cluster_id') = 0,
  'alter table datasource_connection_health add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'datasource_connection_health' and column_name = 'legacy_connection_fingerprint') = 0,
  'alter table datasource_connection_health add column legacy_connection_fingerprint varchar(128) generated always as (case when runtime_cluster_id is null then connection_fingerprint else null end) stored', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'datasource_connection_test_record' and column_name = 'runtime_cluster_id') = 0,
  'alter table datasource_connection_test_record add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'model_sync_task' and column_name = 'runtime_cluster_id') = 0,
  'alter table model_sync_task add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'collection_task_definition' and column_name = 'runtime_cluster_id') = 0,
  'alter table collection_task_definition add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'quality_task_definition' and column_name = 'runtime_cluster_id') = 0,
  'alter table quality_task_definition add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'workflow_definition' and column_name = 'runtime_cluster_id') = 0,
  'alter table workflow_definition add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'workflow_definition_version' and column_name = 'runtime_cluster_id') = 0,
  'alter table workflow_definition_version add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_dev_script' and column_name = 'runtime_cluster_id') = 0,
  'alter table data_dev_script add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_definition' and column_name = 'runtime_cluster_id') = 0,
  'alter table data_service_definition add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_service' and column_name = 'runtime_cluster_id') = 0,
  'alter table data_ingestion_service add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_service' and column_name = 'runtime_cluster_id') = 0,
  'alter table protocol_conversion_service add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_access_log' and column_name = 'requested_cluster_id') = 0,
  'alter table data_service_access_log add column requested_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_access_log' and column_name = 'actual_cluster_id') = 0,
  'alter table data_service_access_log add column actual_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_access_log' and column_name = 'requested_cluster_id') = 0,
  'alter table data_ingestion_access_log add column requested_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_access_log' and column_name = 'actual_cluster_id') = 0,
  'alter table data_ingestion_access_log add column actual_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_access_log' and column_name = 'requested_cluster_id') = 0,
  'alter table protocol_conversion_access_log add column requested_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_access_log' and column_name = 'actual_cluster_id') = 0,
  'alter table protocol_conversion_access_log add column actual_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'studio_alert_incident' and column_name = 'requested_cluster_id') = 0,
  'alter table studio_alert_incident add column requested_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'studio_alert_incident' and column_name = 'actual_cluster_id') = 0,
  'alter table studio_alert_incident add column actual_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'dispatch_task' and column_name = 'target_cluster_id') = 0,
  'alter table dispatch_task add column target_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'dispatch_task' and column_name = 'resource_revision') = 0,
  'alter table dispatch_task add column resource_revision varchar(128)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'dispatch_task' and column_name = 'claim_token') = 0,
  'alter table dispatch_task add column claim_token varchar(64)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'dispatch_task' and column_name = 'worker_boot_id') = 0,
  'alter table dispatch_task add column worker_boot_id varchar(128)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'run_record' and column_name = 'requested_cluster_id') = 0,
  'alter table run_record add column requested_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'run_record' and column_name = 'actual_cluster_id') = 0,
  'alter table run_record add column actual_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'run_record' and column_name = 'actual_cluster_code') = 0,
  'alter table run_record add column actual_cluster_code varchar(64)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'run_record' and column_name = 'worker_boot_id') = 0,
  'alter table run_record add column worker_boot_id varchar(128)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'worker_lease' and column_name = 'runtime_cluster_id') = 0,
  'alter table worker_lease add column runtime_cluster_id bigint', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'worker_lease' and column_name = 'runtime_cluster_code') = 0,
  'alter table worker_lease add column runtime_cluster_code varchar(64)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'worker_lease' and column_name = 'boot_id') = 0,
  'alter table worker_lease add column boot_id varchar(128)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'worker_lease' and column_name = 'runtime_version') = 0,
  'alter table worker_lease add column runtime_version varchar(128)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'worker_lease' and column_name = 'plugin_fingerprint') = 0,
  'alter table worker_lease add column plugin_fingerprint varchar(128)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

-- The historical health key did not include the execution location. Keep a
-- generated compatibility key so pre-backfill nullable rows remain unique
-- while non-null cluster rows use the new three-column dimension.
set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'datasource_connection_health' and index_name = 'uk_ds_conn_health_legacy_fp') = 0,
  'alter table datasource_connection_health add unique key uk_ds_conn_health_legacy_fp (tenant_id,legacy_connection_fingerprint)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if(
  (select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'datasource_connection_health' and index_name = 'uk_ds_conn_health_fp') > 0
  and (select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'datasource_connection_health' and index_name = 'uk_ds_conn_health_fp' and column_name = 'runtime_cluster_id') = 0,
  'alter table datasource_connection_health drop index uk_ds_conn_health_fp', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'datasource_connection_health' and index_name = 'uk_ds_conn_health_fp') = 0,
  'alter table datasource_connection_health add unique key uk_ds_conn_health_fp (tenant_id,runtime_cluster_id,connection_fingerprint)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'datasource_connection_test_record' and index_name = 'idx_ds_conn_record_cluster_lookup') = 0,
  'alter table datasource_connection_test_record add key idx_ds_conn_record_cluster_lookup (tenant_id,runtime_cluster_id,connection_fingerprint,ended_at)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'model_sync_task' and index_name = 'idx_model_sync_task_project_cluster_status') = 0,
  'alter table model_sync_task add key idx_model_sync_task_project_cluster_status (project_id,runtime_cluster_id,status)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'dispatch_task' and index_name = 'idx_dispatch_task_cluster_status_created') = 0,
  'alter table dispatch_task add key idx_dispatch_task_cluster_status_created (target_cluster_id,status,created_at)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'run_record' and index_name = 'idx_run_record_project_cluster_created') = 0,
  'alter table run_record add key idx_run_record_project_cluster_created (project_id,requested_cluster_id,created_at)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'studio_alert_incident' and index_name = 'idx_alert_incident_cluster') = 0,
  'alter table studio_alert_incident add key idx_alert_incident_cluster (project_id,requested_cluster_id,actual_cluster_id,last_triggered_at)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'worker_lease' and index_name = 'idx_worker_lease_cluster_status') = 0,
  'alter table worker_lease add key idx_worker_lease_cluster_status (runtime_cluster_id,status,last_heartbeat_at)', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
