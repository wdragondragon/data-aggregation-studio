-- Data Aggregation Studio 增量数据脚本
-- 目标：协议转换服务增加源请求 Header/Query/Body 显式透传配置。

set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_service' and column_name = 'request_passthrough_json') = 0,
  'alter table protocol_conversion_service add column request_passthrough_json json after body_bridge_options_json',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
