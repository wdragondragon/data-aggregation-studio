-- Data Aggregation Studio 增量数据脚本
-- 目标：HTTP SOAP Writer array 模式不再默认猜测 records.record，由模型字段 parentNode 或用户显式配置决定目标数据节点。

START TRANSACTION;

update meta_field_definition
set default_value = null,
    placeholder = '例如：records.record',
    updated_at = '2026-06-10 10:00:00'
where schema_version_id = 2047489290000000242
  and field_key = 'dataNodePath'
  and tenant_id = 'default'
  and deleted = 0;

COMMIT;
