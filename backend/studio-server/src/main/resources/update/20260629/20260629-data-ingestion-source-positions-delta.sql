set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_service' and column_name = 'source_positions_json') = 0,
  'alter table data_ingestion_service add column source_positions_json json after field_mappings_json',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
