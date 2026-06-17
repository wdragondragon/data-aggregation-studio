set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'datasource_definition' and column_name = 'connection_status') = 0,
  'alter table datasource_definition add column connection_status varchar(32) default ''UNKNOWN'' after executable',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'datasource_definition' and column_name = 'last_connection_test_at') = 0,
  'alter table datasource_definition add column last_connection_test_at datetime after connection_status',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'datasource_definition' and column_name = 'last_connection_test_message') = 0,
  'alter table datasource_definition add column last_connection_test_message varchar(1000) after last_connection_test_at',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'datasource_definition' and column_name = 'last_connection_test_duration_ms') = 0,
  'alter table datasource_definition add column last_connection_test_duration_ms bigint after last_connection_test_message',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'datasource_definition' and column_name = 'connection_fingerprint') = 0,
  'alter table datasource_definition add column connection_fingerprint varchar(128) after executable',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'datasource_definition' and column_name = 'manual_connection_test_timeout_seconds') = 0,
  'alter table datasource_definition add column manual_connection_test_timeout_seconds int after last_connection_test_duration_ms',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'datasource_definition' and column_name = 'scheduled_connection_test_timeout_seconds') = 0,
  'alter table datasource_definition add column scheduled_connection_test_timeout_seconds int after manual_connection_test_timeout_seconds',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.statistics where table_schema = @schema_name and table_name = 'datasource_definition' and index_name = 'idx_datasource_definition_connection') = 0,
  'alter table datasource_definition add index idx_datasource_definition_connection (tenant_id, connection_fingerprint)',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

create table if not exists datasource_connection_health (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    connection_fingerprint varchar(128) not null,
    connection_status varchar(32) default 'UNKNOWN',
    last_connection_test_at datetime,
    last_connection_test_message varchar(1000),
    last_connection_test_duration_ms bigint,
    probe_state varchar(32) default 'IDLE',
    probe_owner varchar(128),
    probe_run_id varchar(64),
    probe_started_at datetime,
    probe_lease_until datetime,
    failure_count int default 0,
    next_probe_at datetime,
    unique key uk_ds_conn_health_fp (tenant_id, connection_fingerprint),
    key idx_ds_conn_health_next (next_probe_at),
    key idx_ds_conn_health_probe (probe_state, probe_lease_until)
);

create table if not exists datasource_connection_test_record (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    connection_fingerprint varchar(128) not null,
    datasource_id bigint,
    datasource_name varchar(255),
    type_code varchar(128),
    probe_run_id varchar(64) not null,
    probe_mode varchar(32),
    connection_status varchar(32) default 'UNKNOWN',
    started_at datetime,
    ended_at datetime,
    duration_ms bigint,
    timeout_seconds int,
    message varchar(1000),
    unique key uk_ds_conn_record_run (tenant_id, probe_run_id),
    key idx_ds_conn_record_lookup (tenant_id, connection_fingerprint, ended_at),
    key idx_ds_conn_record_cleanup (ended_at)
);
