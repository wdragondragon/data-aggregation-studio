-- Data Aggregation Studio 增量数据脚本
-- 目标：接入 ODPS 数据源、模型同步和采集任务读写运行参数。
-- 范围：数据源能力、reader:odps / writer:odps 运行参数模型、ODPS 字段分区标记。

START TRANSACTION;

insert into datasource_type_capability (id, tenant_id, deleted, created_at, updated_at, type_code, type_name, enabled, readable, writable, executable, sql_executable, source_category, source_plugin, reader_plugins_json, writer_plugins_json, sort_order, description) values
(2047489208028782594, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'odps', 'ODPS', 1, 1, 1, 1, 1, 'DATABASE', 'odps', '["odpsreader"]', '["odpswriter"]', 110, 'ODPS / MaxCompute 数据源')
on duplicate key update
  tenant_id = VALUES(tenant_id),
  deleted = VALUES(deleted),
  updated_at = VALUES(updated_at),
  type_name = VALUES(type_name),
  enabled = VALUES(enabled),
  readable = VALUES(readable),
  writable = VALUES(writable),
  executable = VALUES(executable),
  sql_executable = VALUES(sql_executable),
  source_category = VALUES(source_category),
  source_plugin = VALUES(source_plugin),
  reader_plugins_json = VALUES(reader_plugins_json),
  writer_plugins_json = VALUES(writer_plugins_json),
  sort_order = VALUES(sort_order),
  description = VALUES(description);

update datasource_type_capability
set readable = 1,
    writable = 1,
    executable = 1,
    sql_executable = 1,
    source_category = 'DATABASE',
    source_plugin = 'odps',
    reader_plugins_json = '["odpsreader"]',
    writer_plugins_json = '["odpswriter"]',
    sort_order = 110,
    description = 'ODPS / MaxCompute 数据源'
where tenant_id = 'default'
  and type_code = 'odps';

insert into meta_schema (id, tenant_id, deleted, created_at, updated_at, schema_code, schema_name, object_type, type_code, current_version_id, status, description) values
(2047489290000000211, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'runtime:reader:odps', 'ODPS Reader 参数', 'collection-runtime-option', 'reader:odps', 2047489290000000212, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"reader","pluginType":"odps","metaModelCode":"reader","metaModelName":"ODPS Reader 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nODPS Reader 参数 runtime options.'),
(2047489290000000221, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'runtime:writer:odps', 'ODPS Writer 参数', 'collection-runtime-option', 'writer:odps', 2047489290000000222, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"writer","pluginType":"odps","metaModelCode":"writer","metaModelName":"ODPS Writer 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nODPS Writer 参数 runtime options.')
on duplicate key update
  tenant_id = VALUES(tenant_id),
  deleted = VALUES(deleted),
  updated_at = VALUES(updated_at),
  schema_code = VALUES(schema_code),
  schema_name = VALUES(schema_name),
  object_type = VALUES(object_type),
  type_code = VALUES(type_code),
  current_version_id = VALUES(current_version_id),
  status = VALUES(status),
  description = VALUES(description);

insert into meta_schema_version (id, tenant_id, deleted, created_at, updated_at, schema_id, version_number, status, description) values
(2047489290000000212, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000211, 1, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"reader","pluginType":"odps","metaModelCode":"reader","metaModelName":"ODPS Reader 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nODPS Reader 参数 runtime options.'),
(2047489290000000222, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000221, 1, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"writer","pluginType":"odps","metaModelCode":"writer","metaModelName":"ODPS Writer 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nODPS Writer 参数 runtime options.')
on duplicate key update
  tenant_id = VALUES(tenant_id),
  deleted = VALUES(deleted),
  updated_at = VALUES(updated_at),
  schema_id = VALUES(schema_id),
  version_number = VALUES(version_number),
  status = VALUES(status),
  description = VALUES(description);

delete from meta_field_definition
where schema_version_id = 2047489211925291010
  and field_key = 'sourceType';

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options) values
(2047489211925291020, 'default', 0, '2026-04-24 09:34:03', '2026-04-24 09:34:03', 2047489211925291010, 'partitionColumn', '是否分区字段', '是否分区字段', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 100, null, null, 'false', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489290000010221, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000212, 'readMode', '读取模式', '读取模式', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 10, null, null, 'auto', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["auto", "tunnel", "sql"]'),
(2047489290000010222, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000212, 'selectSql', '自定义查询 SQL', '自定义查询 SQL', 'TECHNICAL', 'STRING', 'SQL_EDITOR', 0, 0, 20, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010223, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000212, 'partitionSpec', '分区条件', '分区条件', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 30, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010224, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000212, 'includePartitionColumns', '读取分区字段', '读取分区字段', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 40, null, null, 'false', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489290000010225, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000212, 'offset', '起始偏移', '起始偏移', 'TECHNICAL', 'LONG', 'NUMBER', 0, 0, 50, null, null, '0', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010226, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000212, 'maxRows', '最大读取行数', '最大读取行数', 'TECHNICAL', 'LONG', 'NUMBER', 0, 0, 60, null, null, '0', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010231, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000222, 'writeMode', '写入模式', '写入模式', 'TECHNICAL', 'STRING', 'SELECT', 1, 0, 10, null, null, 'append', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["append", "overwrite"]'),
(2047489290000010232, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000222, 'partitionSpec', '静态分区', '静态分区', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 20, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010233, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000222, 'partitionColumns', '动态分区字段', '动态分区字段', 'TECHNICAL', 'ARRAY', 'SELECT', 0, 0, 30, null, null, '[]', 0, 0, '[]', null, '[]'),
(2047489290000010234, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000222, 'batchSize', '批量写入大小', '批量写入大小', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 40, null, null, '1000', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010235, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000222, 'emptyAsNull', '空字符串写入 NULL', '空字符串写入 NULL', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 50, null, null, 'false', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489290000010236, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000222, 'autoCreatePartition', '自动创建分区', '自动创建分区', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 60, null, null, 'true', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489290000010237, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000222, 'preSql', '写入前 SQL', '写入前 SQL', 'TECHNICAL', 'STRING', 'SQL_EDITOR', 0, 0, 70, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010238, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000222, 'postSql', '写入后 SQL', '写入后 SQL', 'TECHNICAL', 'STRING', 'SQL_EDITOR', 0, 0, 80, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]')
on duplicate key update
  tenant_id = VALUES(tenant_id),
  deleted = VALUES(deleted),
  updated_at = VALUES(updated_at),
  schema_version_id = VALUES(schema_version_id),
  field_key = VALUES(field_key),
  field_name = VALUES(field_name),
  description = VALUES(description),
  scope = VALUES(scope),
  value_type = VALUES(value_type),
  component_type = VALUES(component_type),
  required_flag = VALUES(required_flag),
  sensitive_flag = VALUES(sensitive_flag),
  sort_order = VALUES(sort_order),
  validation_rule = VALUES(validation_rule),
  placeholder = VALUES(placeholder),
  default_value = VALUES(default_value),
  searchable_flag = VALUES(searchable_flag),
  sortable_flag = VALUES(sortable_flag),
  query_operators = VALUES(query_operators),
  query_default_operator = VALUES(query_default_operator),
  options = VALUES(options);

COMMIT;
