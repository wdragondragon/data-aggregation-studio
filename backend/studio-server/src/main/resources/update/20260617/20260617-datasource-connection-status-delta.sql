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

