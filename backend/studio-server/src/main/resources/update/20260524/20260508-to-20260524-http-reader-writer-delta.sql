-- Data Aggregation Studio 增量数据脚本
-- 基准：2026-05-08 最近生产版本。
-- 目标：同步 HTTP Reader / HTTP Writer Studio 接入所需内置数据。
-- 范围：数据源能力、HTTP 技术元模型、reader:http / writer:http 运行参数模型。
-- 说明：本脚本不包含本地联调测试任务、测试数据源、测试模型或接收入口业务数据。

START TRANSACTION;

-- HTTP 数据源能力：可读、可写、可执行采集任务，不支持 SQL 执行。
insert into datasource_type_capability (id, tenant_id, deleted, created_at, updated_at, type_code, type_name, enabled, readable, writable, executable, sql_executable, source_category, source_plugin, reader_plugins_json, writer_plugins_json, sort_order, description) values
(2047489207961673736, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'http', 'HTTP', 1, 1, 1, 1, 0, 'HTTP_API', 'http', '["httpreader"]', '["httpwriter"]', 95, 'HTTP 接口数据源')
on duplicate key update
  tenant_id = VALUES(tenant_id),
  deleted = VALUES(deleted),
  created_at = VALUES(created_at),
  updated_at = VALUES(updated_at),
  type_code = VALUES(type_code),
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

-- 兼容已经存在 HTTP Reader 能力但尚未开放 Writer 的环境。
update datasource_type_capability
set writable = 1,
    executable = 1,
    sql_executable = 0,
    source_category = 'HTTP_API',
    source_plugin = 'http',
    reader_plugins_json = '["httpreader"]',
    writer_plugins_json = '["httpwriter"]',
    sort_order = 95,
    description = 'HTTP 接口数据源'
where tenant_id = 'default'
  and type_code = 'http';

-- HTTP 技术元模型与运行参数模型。
insert into meta_schema (id, tenant_id, deleted, created_at, updated_at, schema_code, schema_name, object_type, type_code, current_version_id, status, description) values
(2047489290000000161, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'runtime:reader:http', 'HTTP Reader 参数', 'collection-runtime-option', 'reader:http', 2047489290000000162, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"reader","pluginType":"http","metaModelCode":"reader","metaModelName":"HTTP Reader 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nHTTP Reader 参数 runtime options.'),
(2047489290000000201, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'runtime:writer:http', 'HTTP Writer 参数', 'collection-runtime-option', 'writer:http', 2047489290000000202, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"writer","pluginType":"http","metaModelCode":"writer","metaModelName":"HTTP Writer 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nHTTP Writer 参数 runtime options.'),
(2047489300000000001, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 'technical:http:source', 'HTTP 数据源信息', 'datasource', 'http', 2047489300000000002, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"http","metaModelCode":"source","metaModelName":"数据源信息","displayMode":"SINGLE","required":true,"syncStrategy":"DATASOURCE_CONNECTION"}\n用于采集 HTTP 数据源信息 的技术元模型定义。'),
(2047489300000000011, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 'technical:http:table', 'HTTP 表信息', 'model', 'http.table', 2047489300000000012, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"http","metaModelCode":"table","metaModelName":"表信息","displayMode":"SINGLE","required":true,"syncStrategy":"OBJECT_DISCOVERY"}\n用于采集 HTTP 表信息 的技术元模型定义。'),
(2047489300000000021, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 'technical:http:field', 'HTTP 字段信息', 'model', 'http.field', 2047489300000000022, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"http","metaModelCode":"field","metaModelName":"字段信息","displayMode":"MULTIPLE","required":true,"syncStrategy":"COLUMN_DISCOVERY"}\n用于采集 HTTP 字段信息 的技术元模型定义。')
on duplicate key update
  tenant_id = VALUES(tenant_id),
  deleted = VALUES(deleted),
  created_at = VALUES(created_at),
  updated_at = VALUES(updated_at),
  schema_code = VALUES(schema_code),
  schema_name = VALUES(schema_name),
  object_type = VALUES(object_type),
  type_code = VALUES(type_code),
  current_version_id = VALUES(current_version_id),
  status = VALUES(status),
  description = VALUES(description);

insert into meta_schema_version (id, tenant_id, deleted, created_at, updated_at, schema_id, version_number, status, description) values
(2047489290000000162, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000161, 1, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"reader","pluginType":"http","metaModelCode":"reader","metaModelName":"HTTP Reader 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nHTTP Reader 参数 runtime options.'),
(2047489290000000202, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000201, 1, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"writer","pluginType":"http","metaModelCode":"writer","metaModelName":"HTTP Writer 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nHTTP Writer 参数 runtime options.'),
(2047489300000000002, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000001, 1, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"http","metaModelCode":"source","metaModelName":"数据源信息","displayMode":"SINGLE","required":true,"syncStrategy":"DATASOURCE_CONNECTION"}\n用于采集 HTTP 数据源信息 的技术元模型定义。'),
(2047489300000000012, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000011, 1, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"http","metaModelCode":"table","metaModelName":"表信息","displayMode":"SINGLE","required":true,"syncStrategy":"OBJECT_DISCOVERY"}\n用于采集 HTTP 表信息 的技术元模型定义。'),
(2047489300000000022, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000021, 1, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"http","metaModelCode":"field","metaModelName":"字段信息","displayMode":"MULTIPLE","required":true,"syncStrategy":"COLUMN_DISCOVERY"}\n用于采集 HTTP 字段信息 的技术元模型定义。')
on duplicate key update
  tenant_id = VALUES(tenant_id),
  deleted = VALUES(deleted),
  created_at = VALUES(created_at),
  updated_at = VALUES(updated_at),
  schema_id = VALUES(schema_id),
  version_number = VALUES(version_number),
  status = VALUES(status),
  description = VALUES(description);

-- 清理早期 HTTP 表信息中不再展示的自动发现字段。
delete from meta_field_definition
where schema_version_id = 2047489300000000012
  and field_key in ('sourceType', 'discoveryMode');

-- HTTP Reader / Writer 运行参数字段，以及 HTTP 数据源 / 模型 / 字段技术元数据字段。
insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options) values
(2047489290000010191, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000162, 'contentType', 'Content-Type', 'Content-Type', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 10, null, null, 'application/json;charset=utf-8', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010192, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000162, 'header', '请求头', '请求头', 'TECHNICAL', 'STRING', 'JSON_EDITOR', 0, 0, 20, null, null, '{}', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010193, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000162, 'params', '请求参数', '请求参数', 'TECHNICAL', 'STRING', 'JSON_EDITOR', 0, 0, 30, null, null, '{}', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010194, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000162, 'requestBody', '请求体', '请求体', 'TECHNICAL', 'STRING', 'JSON_EDITOR', 0, 0, 40, null, null, '', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010195, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000162, 'pageRead', '启用分页读取', '启用分页读取', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 50, null, null, 'false', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489290000010196, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000162, 'pageSize', '分页大小', '分页大小', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 60, null, null, '500', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010201, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'contentType', 'Content-Type', 'Content-Type', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 10, null, null, 'application/json;charset=utf-8', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010202, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'header', '请求头', '请求头', 'TECHNICAL', 'STRING', 'JSON_EDITOR', 0, 0, 20, null, null, '{}', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010203, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'params', '请求参数', '请求参数', 'TECHNICAL', 'STRING', 'JSON_EDITOR', 0, 0, 30, null, null, '{}', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010204, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'requestBody', '请求体模板', '请求体模板', 'TECHNICAL', 'STRING', 'JSON_EDITOR', 0, 0, 40, null, null, '', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010205, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'payloadMode', '发送数据形态', '发送数据形态', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 50, null, null, 'object', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["object", "array"]'),
(2047489290000010206, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'dataNodePath', '发送数据节点', '发送数据节点', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 60, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010207, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'includeTotal', '携带发送总数', '携带发送总数', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 70, null, null, 'false', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489290000010208, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'totalNodePath', '发送总数节点', '发送总数节点', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 80, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010209, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'batchSize', '数组批量大小', '数组批量大小', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 90, null, null, '500', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010210, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'responseStatus.path', '业务状态节点', '业务状态节点', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 100, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010211, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'responseStatus.code', '业务成功状态码', '业务成功状态码', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 110, null, null, '200', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010212, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'retryTimes', '重试次数', '重试次数', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 120, null, null, '3', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010213, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'retryIntervalMs', '重试间隔(毫秒)', '重试间隔(毫秒)', 'TECHNICAL', 'LONG', 'NUMBER', 0, 0, 130, null, null, '1000', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010214, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'connectTimeoutMs', '连接超时(毫秒)', '连接超时(毫秒)', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 140, null, null, '3000', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010215, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489290000000202, 'socketTimeoutMs', '响应超时(毫秒)', '响应超时(毫秒)', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 150, null, null, '3000', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489300000010001, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000002, 'url', 'URL', 'URL', 'TECHNICAL', 'STRING', 'INPUT', 1, 0, 10, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010013, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000012, 'physicalName', '请求路径', '请求路径', 'TECHNICAL', 'STRING', 'INPUT', 1, 0, 10, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010014, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000012, 'description', '描述', '描述', 'TECHNICAL', 'STRING', 'TEXTAREA', 0, 0, 20, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010015, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000012, 'mode', '请求类型', '请求类型', 'TECHNICAL', 'STRING', 'SELECT', 1, 0, 30, null, null, 'GET', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["GET", "POST"]'),
(2047489300000010016, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000012, 'resultType', '返回数据类型', '返回数据类型', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 40, null, null, 'json', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["json", "xml", "soap"]'),
(2047489300000010017, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000012, 'businessStatusPath', '业务状态节点', '业务状态节点', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 50, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010018, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000012, 'businessStatusCode', '业务状态码', '业务状态码', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 60, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010019, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000012, 'totalCodePath', '总量节点', '总量节点', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 70, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010021, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000022, 'name', '字段名称', '字段名称', 'TECHNICAL', 'STRING', 'INPUT', 1, 0, 10, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010022, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000022, 'cnName', '字段中文名', '字段中文名', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 20, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010023, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000022, 'parentNode', '父节点名称', '父节点名称', 'TECHNICAL', 'STRING', 'INPUT', 1, 0, 30, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010024, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000022, 'remarks', '字段备注', '字段备注', 'TECHNICAL', 'STRING', 'TEXTAREA', 0, 0, 40, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010025, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000022, 'primaryKey', '是否主键', '是否主键', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 50, null, null, 'false', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489300000010026, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000022, 'nullable', '能否为空', '能否为空', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 60, null, null, 'true', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489300000010027, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000022, 'type', '类型', '类型', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 70, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["STRING", "TEXT", "LONG", "INT", "INTEGER", "NUMBER", "DOUBLE", "FLOAT", "BOOLEAN", "DATE", "DATETIME", "TIMESTAMP"]'),
(2047489300000010028, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000022, 'size', '长度', '长度', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 80, null, null, null, 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489300000010029, 'default', 0, '2026-04-24 09:34:04', '2026-04-24 09:34:04', 2047489300000000022, 'scale', '精度', '精度', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 90, null, null, null, 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]')
on duplicate key update
  tenant_id = VALUES(tenant_id),
  deleted = VALUES(deleted),
  created_at = VALUES(created_at),
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
