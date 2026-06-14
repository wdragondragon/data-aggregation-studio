-- Data Aggregation Studio 增量数据脚本
-- 目标：协议转换服务拆分源端 SOAP 开放合约与目标端 SOAP 调用合约。

set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'protocol_conversion_service' and column_name = 'target_webservice_config_json') = 0,
  'alter table protocol_conversion_service add column target_webservice_config_json json after target_query_json',
  'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

update protocol_conversion_service
set target_webservice_config_json = webservice_config_json,
    updated_at = current_timestamp
where target_protocol in ('SOAP_11', 'SOAP_12')
  and (target_webservice_config_json is null or json_length(target_webservice_config_json) = 0);
