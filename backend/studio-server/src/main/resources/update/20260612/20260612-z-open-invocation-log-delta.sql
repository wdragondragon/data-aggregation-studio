-- Data Aggregation Studio 增量数据脚本
-- 目标：为同步开放调用类访问日志补充统一日志归档字段。
-- 范围：数据服务、数据接入服务、协议转换服务的 access_log 表。

set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_access_log' and column_name = 'request_id') = 0,
  'alter table data_service_access_log add column request_id varchar(128) after subscription_name_snapshot',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_access_log' and column_name = 'system_log') = 0,
  'alter table data_service_access_log add column system_log mediumtext after error_message',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_access_log' and column_name = 'log_storage_type') = 0,
  'alter table data_service_access_log add column log_storage_type varchar(64)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_access_log' and column_name = 'log_object_bucket') = 0,
  'alter table data_service_access_log add column log_object_bucket varchar(255)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_access_log' and column_name = 'log_object_key') = 0,
  'alter table data_service_access_log add column log_object_key varchar(1000)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_access_log' and column_name = 'log_size_bytes') = 0,
  'alter table data_service_access_log add column log_size_bytes bigint',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_access_log' and column_name = 'log_charset') = 0,
  'alter table data_service_access_log add column log_charset varchar(64)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_access_log' and column_name = 'log_archive_status') = 0,
  'alter table data_service_access_log add column log_archive_status varchar(64)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_service_access_log' and column_name = 'log_archive_error') = 0,
  'alter table data_service_access_log add column log_archive_error varchar(1000)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_access_log' and column_name = 'request_id') = 0,
  'alter table data_ingestion_access_log add column request_id varchar(128) after subscription_name_snapshot',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_access_log' and column_name = 'log_storage_type') = 0,
  'alter table data_ingestion_access_log add column log_storage_type varchar(64)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_access_log' and column_name = 'log_object_bucket') = 0,
  'alter table data_ingestion_access_log add column log_object_bucket varchar(255)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_access_log' and column_name = 'log_object_key') = 0,
  'alter table data_ingestion_access_log add column log_object_key varchar(1000)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_access_log' and column_name = 'log_size_bytes') = 0,
  'alter table data_ingestion_access_log add column log_size_bytes bigint',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_access_log' and column_name = 'log_charset') = 0,
  'alter table data_ingestion_access_log add column log_charset varchar(64)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_access_log' and column_name = 'log_archive_status') = 0,
  'alter table data_ingestion_access_log add column log_archive_status varchar(64)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_access_log' and column_name = 'log_archive_error') = 0,
  'alter table data_ingestion_access_log add column log_archive_error varchar(1000)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_access_log' and column_name = 'log_storage_type') = 0,
  'alter table protocol_conversion_access_log add column log_storage_type varchar(64)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_access_log' and column_name = 'log_object_bucket') = 0,
  'alter table protocol_conversion_access_log add column log_object_bucket varchar(255)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_access_log' and column_name = 'log_object_key') = 0,
  'alter table protocol_conversion_access_log add column log_object_key varchar(1000)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_access_log' and column_name = 'log_size_bytes') = 0,
  'alter table protocol_conversion_access_log add column log_size_bytes bigint',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_access_log' and column_name = 'log_charset') = 0,
  'alter table protocol_conversion_access_log add column log_charset varchar(64)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_access_log' and column_name = 'log_archive_status') = 0,
  'alter table protocol_conversion_access_log add column log_archive_status varchar(64)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_access_log' and column_name = 'log_archive_error') = 0,
  'alter table protocol_conversion_access_log add column log_archive_error varchar(1000)',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
