set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_service' and column_name = 'source_bindings_json') = 0,
  'alter table data_ingestion_service add column source_bindings_json json after source_positions_json',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_service' and column_name = 'source_count') = 0,
  'alter table data_ingestion_service add column source_count int default 1 after source_bindings_json',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'data_ingestion_service' and column_name = 'target_count') = 0,
  'alter table data_ingestion_service add column target_count int default 1 after source_count',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

update data_ingestion_service
set source_bindings_json = json_array(json_object(
  'sourceCode', 'source_1',
  'sourceName', '默认来源',
  'sourcePosition', 'BODY',
  'sourcePath', data_node_path,
  'payloadMode', coalesce(payload_mode, case when data_node_path is null or data_node_path = '' then 'OBJECT' else 'ARRAY' end),
  'targetType', coalesce(target_type, 'DATABASE'),
  'datasourceId', datasource_id,
  'datasourceName', datasource_name_snapshot,
  'datasourceTypeCode', datasource_type_code,
  'modelId', model_id,
  'modelName', model_name_snapshot,
  'modelPhysicalLocator', model_physical_locator,
  'writerOptions', coalesce(writer_options_json, json_object()),
  'fieldMappings', coalesce(field_mappings_json, json_array()),
  'sortOrder', 0,
  'enabled', true
))
where source_bindings_json is null or json_length(source_bindings_json) = 0;

update data_ingestion_service
set source_count = 1
where source_count is null or source_count < 1;

update data_ingestion_service
set target_count = 1
where target_count is null or target_count < 1;
