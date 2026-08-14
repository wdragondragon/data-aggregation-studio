create table if not exists file_transfer_event_outbox (
    id bigint primary key,
    tenant_id varchar(64) not null,
    project_id bigint not null,
    deleted int not null default 0,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    event_type varchar(32) not null,
    run_id bigint not null,
    item_id bigint,
    occurred_at datetime not null,
    payload_version int not null default 1,
    payload_json json,
    key idx_ft_outbox_scope_id (tenant_id, project_id, id),
    key idx_ft_outbox_run_id (run_id, id),
    key idx_ft_outbox_created (created_at, id),
    key idx_ft_outbox_event_type (event_type, created_at, id)
);

create table if not exists file_transfer_event_consumer_cursor (
    id bigint primary key,
    instance_id varchar(255) not null,
    tenant_id varchar(64) not null,
    project_id bigint not null,
    last_event_id bigint not null default 0,
    last_seen_at datetime not null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    unique key uk_ft_event_cursor_scope (instance_id, tenant_id, project_id),
    key idx_ft_event_cursor_seen (instance_id, last_seen_at),
    key idx_ft_event_cursor_position (tenant_id, project_id, last_event_id)
);

set @schema_name = database();

set @outbox_required_columns = (select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox'
      and column_name in ('id', 'tenant_id', 'project_id', 'event_type', 'run_id', 'occurred_at'));
set @outbox_rows = (select count(*) from file_transfer_event_outbox);
set @ddl = if(@outbox_rows > 0 and @outbox_required_columns < 6,
  'select FILE_TRANSFER_EVENT_OUTBOX_REQUIRES_MANUAL_BACKFILL from file_transfer_event_outbox limit 1',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
set @ddl = if(@outbox_rows = 0 and @outbox_required_columns < 6,
  'drop table file_transfer_event_outbox', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @cursor_required_columns = (select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_consumer_cursor'
      and column_name in ('id', 'instance_id', 'tenant_id', 'project_id', 'last_event_id', 'last_seen_at'));
set @cursor_rows = (select count(*) from file_transfer_event_consumer_cursor);
set @ddl = if(@cursor_rows > 0 and @cursor_required_columns < 6,
  'select FILE_TRANSFER_EVENT_CURSOR_REQUIRES_MANUAL_BACKFILL from file_transfer_event_consumer_cursor limit 1',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
set @ddl = if(@cursor_rows = 0 and @cursor_required_columns < 6,
  'drop table file_transfer_event_consumer_cursor', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

create table if not exists file_transfer_event_outbox (
    id bigint primary key,
    tenant_id varchar(64) not null,
    project_id bigint not null,
    deleted int not null default 0,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    event_type varchar(32) not null,
    run_id bigint not null,
    item_id bigint,
    occurred_at datetime not null,
    payload_version int not null default 1,
    payload_json json,
    key idx_ft_outbox_scope_id (tenant_id, project_id, id),
    key idx_ft_outbox_run_id (run_id, id),
    key idx_ft_outbox_created (created_at, id),
    key idx_ft_outbox_event_type (event_type, created_at, id)
);

create table if not exists file_transfer_event_consumer_cursor (
    id bigint primary key,
    instance_id varchar(255) not null,
    tenant_id varchar(64) not null,
    project_id bigint not null,
    last_event_id bigint not null default 0,
    last_seen_at datetime not null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    unique key uk_ft_event_cursor_scope (instance_id, tenant_id, project_id),
    key idx_ft_event_cursor_seen (instance_id, last_seen_at),
    key idx_ft_event_cursor_position (tenant_id, project_id, last_event_id)
);

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox' and column_name = 'tenant_id') = 0,
  'alter table file_transfer_event_outbox add column tenant_id varchar(64) not null', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox' and column_name = 'project_id') = 0,
  'alter table file_transfer_event_outbox add column project_id bigint not null', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox' and column_name = 'deleted') = 0,
  'alter table file_transfer_event_outbox add column deleted int not null default 0', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox' and column_name = 'created_at') = 0,
  'alter table file_transfer_event_outbox add column created_at datetime not null default current_timestamp', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox' and column_name = 'updated_at') = 0,
  'alter table file_transfer_event_outbox add column updated_at datetime not null default current_timestamp', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox' and column_name = 'event_type') = 0,
  'alter table file_transfer_event_outbox add column event_type varchar(32) not null', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox' and column_name = 'run_id') = 0,
  'alter table file_transfer_event_outbox add column run_id bigint not null', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox' and column_name = 'item_id') = 0,
  'alter table file_transfer_event_outbox add column item_id bigint', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox' and column_name = 'occurred_at') = 0,
  'alter table file_transfer_event_outbox add column occurred_at datetime not null', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox' and column_name = 'payload_version') = 0,
  'alter table file_transfer_event_outbox add column payload_version int not null default 1', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox' and column_name = 'payload_json') = 0,
  'alter table file_transfer_event_outbox add column payload_json json', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_consumer_cursor' and column_name = 'instance_id') = 0,
  'alter table file_transfer_event_consumer_cursor add column instance_id varchar(255) not null', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_consumer_cursor' and column_name = 'tenant_id') = 0,
  'alter table file_transfer_event_consumer_cursor add column tenant_id varchar(64) not null', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_consumer_cursor' and column_name = 'project_id') = 0,
  'alter table file_transfer_event_consumer_cursor add column project_id bigint not null', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_consumer_cursor' and column_name = 'last_event_id') = 0,
  'alter table file_transfer_event_consumer_cursor add column last_event_id bigint not null default 0', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_consumer_cursor' and column_name = 'last_seen_at') = 0,
  'alter table file_transfer_event_consumer_cursor add column last_seen_at datetime not null', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_consumer_cursor' and column_name = 'created_at') = 0,
  'alter table file_transfer_event_consumer_cursor add column created_at datetime not null default current_timestamp', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.columns
    where table_schema = @schema_name and table_name = 'file_transfer_event_consumer_cursor' and column_name = 'updated_at') = 0,
  'alter table file_transfer_event_consumer_cursor add column updated_at datetime not null default current_timestamp', 'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.statistics
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox'
      and index_name = 'idx_ft_outbox_scope_id') = 0,
  'alter table file_transfer_event_outbox add key idx_ft_outbox_scope_id (tenant_id, project_id, id)',
  'select 1');
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.statistics
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox'
      and index_name = 'idx_ft_outbox_run_id') = 0,
  'alter table file_transfer_event_outbox add key idx_ft_outbox_run_id (run_id, id)',
  'select 1');
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.statistics
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox'
      and index_name = 'idx_ft_outbox_created') = 0,
  'alter table file_transfer_event_outbox add key idx_ft_outbox_created (created_at, id)',
  'select 1');
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.statistics
    where table_schema = @schema_name and table_name = 'file_transfer_event_outbox'
      and index_name = 'idx_ft_outbox_event_type') = 0,
  'alter table file_transfer_event_outbox add key idx_ft_outbox_event_type (event_type, created_at, id)',
  'select 1');
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.statistics
    where table_schema = @schema_name and table_name = 'file_transfer_event_consumer_cursor'
      and index_name = 'uk_ft_event_cursor_scope') = 0,
  'alter table file_transfer_event_consumer_cursor add unique key uk_ft_event_cursor_scope (instance_id, tenant_id, project_id)',
  'select 1');
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.statistics
    where table_schema = @schema_name and table_name = 'file_transfer_event_consumer_cursor'
      and index_name = 'idx_ft_event_cursor_seen') = 0,
  'alter table file_transfer_event_consumer_cursor add key idx_ft_event_cursor_seen (instance_id, last_seen_at)',
  'select 1');
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = if((select count(*) from information_schema.statistics
    where table_schema = @schema_name and table_name = 'file_transfer_event_consumer_cursor'
      and index_name = 'idx_ft_event_cursor_position') = 0,
  'alter table file_transfer_event_consumer_cursor add key idx_ft_event_cursor_position (tenant_id, project_id, last_event_id)',
  'select 1');
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
