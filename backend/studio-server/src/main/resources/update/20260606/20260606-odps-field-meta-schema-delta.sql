-- Data Aggregation Studio 增量数据脚本
-- 目标：修复已升级环境中 ODPS 字段信息元模型字段定义不完整的问题。
-- 范围：technical:odps:field 字段信息表头，补齐普通字段定义并保留分区字段标记。

START TRANSACTION;

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options) values
(2047489211925291011, 'default', 0, '2026-04-24 09:34:03', '2026-04-24 09:34:03', 2047489211925291010, 'name', '字段名', '字段名', 'TECHNICAL', 'STRING', 'INPUT', 1, 0, 10, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489211925291012, 'default', 0, '2026-04-24 09:34:03', '2026-04-24 09:34:03', 2047489211925291010, 'type', '字段类型', '字段类型', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 20, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489211925291013, 'default', 0, '2026-04-24 09:34:03', '2026-04-24 09:34:03', 2047489211925291010, 'size', '长度', '长度', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 30, null, null, null, 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489211925291014, 'default', 0, '2026-04-24 09:34:03', '2026-04-24 09:34:03', 2047489211925291010, 'scale', '精度', '精度', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 40, null, null, null, 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489211925291015, 'default', 0, '2026-04-24 09:34:03', '2026-04-24 09:34:03', 2047489211925291010, 'nullable', '是否可空', '是否可空', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 50, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489211925291016, 'default', 0, '2026-04-24 09:34:03', '2026-04-24 09:34:03', 2047489211925291010, 'primaryKey', '是否主键', '是否主键', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 60, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489211925291017, 'default', 0, '2026-04-24 09:34:03', '2026-04-24 09:34:03', 2047489211925291010, 'autoIncrement', '是否自增', '是否自增', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 70, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489211925291018, 'default', 0, '2026-04-24 09:34:03', '2026-04-24 09:34:03', 2047489211925291010, 'remarks', '备注', '备注', 'TECHNICAL', 'STRING', 'TEXTAREA', 0, 0, 80, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489211925291019, 'default', 0, '2026-04-24 09:34:03', '2026-04-24 09:34:03', 2047489211925291010, 'defaultValue', '默认值', '默认值', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 90, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489211925291020, 'default', 0, '2026-04-24 09:34:03', '2026-04-24 09:34:03', 2047489211925291010, 'partitionColumn', '是否分区字段', '是否分区字段', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 100, null, null, 'false', 1, 1, '["EQ"]', 'EQ', '[]')
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

update meta_field_definition source_field
join meta_schema odps_field_schema
  on odps_field_schema.current_version_id = source_field.schema_version_id
  and odps_field_schema.schema_code = 'technical:odps:field'
left join meta_field_definition partition_field
  on partition_field.schema_version_id = source_field.schema_version_id
  and partition_field.field_key = 'partitionColumn'
set source_field.deleted = 0,
    source_field.updated_at = current_timestamp,
    source_field.field_key = 'partitionColumn',
    source_field.field_name = '是否分区字段',
    source_field.description = '是否分区字段',
    source_field.scope = 'TECHNICAL',
    source_field.value_type = 'BOOLEAN',
    source_field.component_type = 'SWITCH',
    source_field.required_flag = 0,
    source_field.sensitive_flag = 0,
    source_field.sort_order = 100,
    source_field.validation_rule = null,
    source_field.placeholder = null,
    source_field.default_value = 'false',
    source_field.searchable_flag = 1,
    source_field.sortable_flag = 1,
    source_field.query_operators = '["EQ"]',
    source_field.query_default_operator = 'EQ',
    source_field.options = '[]'
where source_field.field_key = 'sourceType'
  and partition_field.id is null;

delete source_field
from meta_field_definition source_field
join meta_schema odps_field_schema
  on odps_field_schema.current_version_id = source_field.schema_version_id
  and odps_field_schema.schema_code = 'technical:odps:field'
where source_field.field_key = 'sourceType';

COMMIT;
