-- Data Development 模型 Flink SQL 脚本配置增量脚本

set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_dev_script' and column_name = 'execution_config_json') = 0,
  'alter table data_dev_script add column execution_config_json json after description',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
