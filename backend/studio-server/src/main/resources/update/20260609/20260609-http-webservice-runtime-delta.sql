-- Data Aggregation Studio 增量数据脚本
-- 目标：补充 HTTP WebService/SOAP 采集所需 runtime profile 和 HTTP 模型协议字段。
-- 范围：仅内置元模型数据；不新增业务表，不改变开放接口。

START TRANSACTION;

insert into meta_schema (id, tenant_id, deleted, created_at, updated_at, schema_code, schema_name, object_type, type_code, current_version_id, status, description) values
(2047489290000000231, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 'runtime:reader:http-soap', 'HTTP SOAP Reader 参数', 'collection-runtime-option', 'reader:http-soap', 2047489290000000232, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"reader","pluginType":"http-soap","metaModelCode":"reader","metaModelName":"HTTP SOAP Reader 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nHTTP SOAP Reader 参数 runtime options.'),
(2047489290000000241, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 'runtime:writer:http-soap', 'HTTP SOAP Writer 参数', 'collection-runtime-option', 'writer:http-soap', 2047489290000000242, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"writer","pluginType":"http-soap","metaModelCode":"writer","metaModelName":"HTTP SOAP Writer 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nHTTP SOAP Writer 参数 runtime options.')
on duplicate key update
  tenant_id = VALUES(tenant_id),
  deleted = VALUES(deleted),
  updated_at = VALUES(updated_at),
  schema_name = VALUES(schema_name),
  object_type = VALUES(object_type),
  type_code = VALUES(type_code),
  current_version_id = VALUES(current_version_id),
  status = VALUES(status),
  description = VALUES(description);

insert into meta_schema_version (id, tenant_id, deleted, created_at, updated_at, schema_id, version_number, status, description) values
(2047489290000000232, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000231, 1, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"reader","pluginType":"http-soap","metaModelCode":"reader","metaModelName":"HTTP SOAP Reader 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nHTTP SOAP Reader 参数 runtime options.'),
(2047489290000000242, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000241, 1, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"writer","pluginType":"http-soap","metaModelCode":"writer","metaModelName":"HTTP SOAP Writer 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nHTTP SOAP Writer 参数 runtime options.')
on duplicate key update
  tenant_id = VALUES(tenant_id),
  deleted = VALUES(deleted),
  updated_at = VALUES(updated_at),
  schema_id = VALUES(schema_id),
  version_number = VALUES(version_number),
  status = VALUES(status),
  description = VALUES(description);

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options) values
(2047489290000010251, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000232, 'soapVersion', 'SOAP 版本', 'SOAP 版本', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 10, null, null, 'SOAP_11', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["SOAP_11", "SOAP_12"]'),
(2047489290000010252, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000232, 'soapAction', 'SOAPAction', 'SOAPAction', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 20, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010253, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000232, 'contentType', 'Content-Type', 'Content-Type', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 30, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010254, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000232, 'header', 'HTTP Headers', 'HTTP Headers', 'TECHNICAL', 'STRING', 'JSON_EDITOR', 0, 0, 40, null, null, '{}', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010255, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000232, 'params', 'Query 参数', 'Query 参数', 'TECHNICAL', 'STRING', 'JSON_EDITOR', 0, 0, 50, null, null, '{}', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010256, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000232, 'requestBody', 'SOAP Envelope XML', 'SOAP Envelope XML', 'TECHNICAL', 'STRING', 'CODE_EDITOR', 1, 0, 60, null, null, '', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010257, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000232, 'soapFaultFail', 'SOAP Fault 失败', 'SOAP Fault 失败', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 70, null, null, 'true', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489290000010258, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000232, 'pageRead', '启用分页读取', '启用分页读取', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 80, null, null, 'false', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489290000010259, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000232, 'pageSize', '分页大小', '分页大小', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 90, null, null, '500', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010261, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'soapVersion', 'SOAP 版本', 'SOAP 版本', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 10, null, null, 'SOAP_11', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["SOAP_11", "SOAP_12"]'),
(2047489290000010262, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'soapAction', 'SOAPAction', 'SOAPAction', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 20, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010263, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'contentType', 'Content-Type', 'Content-Type', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 30, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010264, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'header', 'HTTP Headers', 'HTTP Headers', 'TECHNICAL', 'STRING', 'JSON_EDITOR', 0, 0, 40, null, null, '{}', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010265, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'params', 'Query 参数', 'Query 参数', 'TECHNICAL', 'STRING', 'JSON_EDITOR', 0, 0, 50, null, null, '{}', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010266, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'requestBody', 'SOAP Envelope XML 模板', 'SOAP Envelope XML 模板', 'TECHNICAL', 'STRING', 'CODE_EDITOR', 1, 0, 60, null, null, '', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010267, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'soapFaultFail', 'SOAP Fault 失败', 'SOAP Fault 失败', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 70, null, null, 'true', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489290000010268, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'responseStatus.path', '业务状态节点', '业务状态节点', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 80, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010269, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'responseStatus.code', '业务成功状态码', '业务成功状态码', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 90, null, null, '200', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010270, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'retryTimes', '重试次数', '重试次数', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 100, null, null, '3', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010271, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'retryIntervalMs', '重试间隔(毫秒)', '重试间隔(毫秒)', 'TECHNICAL', 'LONG', 'NUMBER', 0, 0, 110, null, null, '1000', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010272, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'connectTimeoutMs', '连接超时(毫秒)', '连接超时(毫秒)', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 120, null, null, '3000', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010273, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489290000000242, 'socketTimeoutMs', '响应超时(毫秒)', '响应超时(毫秒)', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 130, null, null, '3000', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]')
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
  default_value = VALUES(default_value),
  searchable_flag = VALUES(searchable_flag),
  sortable_flag = VALUES(sortable_flag),
  query_operators = VALUES(query_operators),
  query_default_operator = VALUES(query_default_operator),
  options = VALUES(options);

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options) values
(2047489300000010030, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489300000000012, 'protocolMode', '协议模式', '协议模式', 'TECHNICAL', 'STRING', 'SELECT', 1, 0, 30, null, null, 'REST_JSON', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["REST_JSON", "REST_XML", "SOAP"]'),
(2047489300000010031, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489300000000012, 'soapVersion', 'SOAP 版本', 'SOAP 版本', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 60, null, null, 'SOAP_11', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["SOAP_11", "SOAP_12"]'),
(2047489300000010032, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489300000000012, 'namespaceUri', 'Namespace URI', 'Namespace URI', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 70, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010033, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489300000000012, 'operationName', 'Operation', 'Operation', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 80, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010034, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489300000000012, 'soapAction', 'SOAPAction', 'SOAPAction', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 90, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010035, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489300000000012, 'requestRootName', '请求根节点', '请求根节点', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 100, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010036, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489300000000012, 'responseRootName', '响应根节点', '响应根节点', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 110, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489300000010037, 'default', 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', 2047489300000000012, 'wsdlUrl', 'WSDL 地址', 'WSDL 地址', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 120, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]')
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
  default_value = VALUES(default_value),
  searchable_flag = VALUES(searchable_flag),
  sortable_flag = VALUES(sortable_flag),
  query_operators = VALUES(query_operators),
  query_default_operator = VALUES(query_default_operator),
  options = VALUES(options);

-- 兼容已经存在真实版本号的环境：按 schema_code 动态定位 HTTP 表信息当前版本，避免依赖初始化环境中的固定 ID。
update meta_field_definition f
join meta_schema s on s.current_version_id = f.schema_version_id and s.deleted = 0
set f.sort_order = case f.field_key
  when 'physicalName' then 10
  when 'description' then 20
  when 'protocolMode' then 30
  when 'mode' then 40
  when 'resultType' then 50
  when 'soapVersion' then 60
  when 'namespaceUri' then 70
  when 'operationName' then 80
  when 'soapAction' then 90
  when 'requestRootName' then 100
  when 'responseRootName' then 110
  when 'wsdlUrl' then 120
  when 'businessStatusPath' then 130
  when 'businessStatusCode' then 140
  when 'totalCodePath' then 150
  else f.sort_order
end,
f.updated_at = '2026-06-09 10:00:00'
where s.schema_code = 'technical:http:table'
  and f.deleted = 0
  and f.field_key in ('physicalName', 'description', 'protocolMode', 'mode', 'resultType', 'soapVersion',
                      'namespaceUri', 'operationName', 'soapAction', 'requestRootName', 'responseRootName',
                      'wsdlUrl', 'businessStatusPath', 'businessStatusCode', 'totalCodePath');

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options)
select 2064198000000010030, s.tenant_id, 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', s.current_version_id, 'protocolMode', '协议模式', '协议模式', 'TECHNICAL', 'STRING', 'SELECT', 1, 0, 30, null, null, 'REST_JSON', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["REST_JSON", "REST_XML", "SOAP"]'
from meta_schema s
where s.deleted = 0 and s.schema_code = 'technical:http:table'
  and not exists (select 1 from meta_field_definition f where f.schema_version_id = s.current_version_id and f.field_key = 'protocolMode' and f.deleted = 0);

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options)
select 2064198000000010031, s.tenant_id, 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', s.current_version_id, 'soapVersion', 'SOAP 版本', 'SOAP 版本', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 60, null, null, 'SOAP_11', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["SOAP_11", "SOAP_12"]'
from meta_schema s
where s.deleted = 0 and s.schema_code = 'technical:http:table'
  and not exists (select 1 from meta_field_definition f where f.schema_version_id = s.current_version_id and f.field_key = 'soapVersion' and f.deleted = 0);

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options)
select 2064198000000010032, s.tenant_id, 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', s.current_version_id, 'namespaceUri', 'Namespace URI', 'Namespace URI', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 70, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'
from meta_schema s
where s.deleted = 0 and s.schema_code = 'technical:http:table'
  and not exists (select 1 from meta_field_definition f where f.schema_version_id = s.current_version_id and f.field_key = 'namespaceUri' and f.deleted = 0);

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options)
select 2064198000000010033, s.tenant_id, 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', s.current_version_id, 'operationName', 'Operation', 'Operation', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 80, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'
from meta_schema s
where s.deleted = 0 and s.schema_code = 'technical:http:table'
  and not exists (select 1 from meta_field_definition f where f.schema_version_id = s.current_version_id and f.field_key = 'operationName' and f.deleted = 0);

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options)
select 2064198000000010034, s.tenant_id, 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', s.current_version_id, 'soapAction', 'SOAPAction', 'SOAPAction', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 90, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'
from meta_schema s
where s.deleted = 0 and s.schema_code = 'technical:http:table'
  and not exists (select 1 from meta_field_definition f where f.schema_version_id = s.current_version_id and f.field_key = 'soapAction' and f.deleted = 0);

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options)
select 2064198000000010035, s.tenant_id, 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', s.current_version_id, 'requestRootName', '请求根节点', '请求根节点', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 100, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'
from meta_schema s
where s.deleted = 0 and s.schema_code = 'technical:http:table'
  and not exists (select 1 from meta_field_definition f where f.schema_version_id = s.current_version_id and f.field_key = 'requestRootName' and f.deleted = 0);

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options)
select 2064198000000010036, s.tenant_id, 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', s.current_version_id, 'responseRootName', '响应根节点', '响应根节点', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 110, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'
from meta_schema s
where s.deleted = 0 and s.schema_code = 'technical:http:table'
  and not exists (select 1 from meta_field_definition f where f.schema_version_id = s.current_version_id and f.field_key = 'responseRootName' and f.deleted = 0);

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options)
select 2064198000000010037, s.tenant_id, 0, '2026-06-09 10:00:00', '2026-06-09 10:00:00', s.current_version_id, 'wsdlUrl', 'WSDL 地址', 'WSDL 地址', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 120, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'
from meta_schema s
where s.deleted = 0 and s.schema_code = 'technical:http:table'
  and not exists (select 1 from meta_field_definition f where f.schema_version_id = s.current_version_id and f.field_key = 'wsdlUrl' and f.deleted = 0);

COMMIT;
