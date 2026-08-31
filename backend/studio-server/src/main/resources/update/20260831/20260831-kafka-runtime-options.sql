-- Kafka Reader/Writer 运行参数统一到任务级配置。
-- Topic 由模型 physicalLocator 提供，Broker 与认证由数据源连接元数据提供。

START TRANSACTION;

insert into meta_schema (id, tenant_id, deleted, created_at, updated_at, schema_code, schema_name, object_type, type_code, current_version_id, status, description) values
(2047489290000000251, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 'runtime:reader:kafka', 'Kafka Reader 参数', 'collection-runtime-option', 'reader:kafka', 2047489290000000252, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"reader","pluginType":"kafka","metaModelCode":"reader","metaModelName":"Kafka Reader 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nKafka Reader runtime options.'),
(2047489290000000261, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 'runtime:writer:kafka', 'Kafka Writer 参数', 'collection-runtime-option', 'writer:kafka', 2047489290000000262, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"writer","pluginType":"kafka","metaModelCode":"writer","metaModelName":"Kafka Writer 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nKafka Writer runtime options.')
on duplicate key update
  schema_name = VALUES(schema_name), object_type = VALUES(object_type), type_code = VALUES(type_code),
  current_version_id = VALUES(current_version_id), status = VALUES(status), description = VALUES(description);

insert into meta_schema_version (id, tenant_id, deleted, created_at, updated_at, schema_id, version_number, status, description) values
(2047489290000000252, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000251, 1, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"reader","pluginType":"kafka","metaModelCode":"reader","metaModelName":"Kafka Reader 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nKafka Reader runtime options.'),
(2047489290000000262, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000261, 1, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"RUNTIME","role":"writer","pluginType":"kafka","metaModelCode":"writer","metaModelName":"Kafka Writer 参数","displayMode":"SINGLE","required":false,"syncStrategy":"RUNTIME_OPTION"}\nKafka Writer runtime options.')
on duplicate key update
  schema_id = VALUES(schema_id), version_number = VALUES(version_number), status = VALUES(status), description = VALUES(description);

insert into meta_field_definition (id, tenant_id, deleted, created_at, updated_at, schema_version_id, field_key, field_name, description, scope, value_type, component_type, required_flag, sensitive_flag, sort_order, validation_rule, placeholder, default_value, searchable_flag, sortable_flag, query_operators, query_default_operator, options) values
(2047489290000010281, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000252, 'groupId', '消费组 ID', '消费组 ID', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 10, null, null, null, 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010282, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000252, 'offsetReset', '无提交位点策略', '无提交位点策略', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 20, null, null, 'latest', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["earliest", "latest"]'),
(2047489290000010283, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000252, 'resetOffset', '运行时重置消费位点', '运行时重置消费位点', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 30, null, null, 'false', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489290000010284, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000252, 'pollTimeoutMs', 'Poll 超时(毫秒)', 'Poll 超时(毫秒)', 'TECHNICAL', 'LONG', 'NUMBER', 0, 0, 40, null, null, '1000', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010285, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000252, 'batchSize', '单次 Poll 最大记录数', '单次 Poll 最大记录数', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 50, null, null, '500', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010286, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000252, 'keepReadTime', '最大读取时长(毫秒)', '最大读取时长(毫秒)', 'TECHNICAL', 'LONG', 'NUMBER', 0, 0, 60, null, null, '3600000', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010287, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000252, 'retryPoll', '连续空 Poll 次数', '连续空 Poll 次数', 'TECHNICAL', 'LONG', 'NUMBER', 0, 0, 70, null, null, '0', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010288, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000252, 'parsingRules', '消息解析方式', '消息解析方式', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 80, null, null, 'json', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["json", "split"]'),
(2047489290000010289, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000252, 'fieldDelimiter', '消息字段分隔符', '消息字段分隔符', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 90, null, null, '\\t', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010290, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000252, 'otherProperties', '额外 Consumer 属性', '额外 Consumer 属性', 'TECHNICAL', 'JSON', 'JSON_EDITOR', 0, 0, 100, null, null, '{}', 0, 0, '[]', null, '[]'),
(2047489290000010291, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000262, 'ack', '确认级别', '确认级别', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 10, null, null, '0', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["0", "1", "all"]'),
(2047489290000010292, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000262, 'retries', '发送重试次数', '发送重试次数', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 20, null, null, '0', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010293, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000262, 'batchSize', '批量发送大小', '批量发送大小', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 30, null, null, '16384', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010294, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000262, 'fieldDelimiter', '消息字段分隔符', '消息字段分隔符', 'TECHNICAL', 'STRING', 'INPUT', 0, 0, 40, null, null, '\\t', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '[]'),
(2047489290000010295, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000262, 'autoCreateTopic', '自动创建 Topic', '自动创建 Topic', 'TECHNICAL', 'BOOLEAN', 'SWITCH', 0, 0, 50, null, null, 'true', 1, 1, '["EQ"]', 'EQ', '[]'),
(2047489290000010296, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000262, 'createTopicNumPartition', '新建 Topic 分区数', '新建 Topic 分区数', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 60, null, null, '1', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010297, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000262, 'createTopicReplicationFactor', '新建 Topic 副本数', '新建 Topic 副本数', 'TECHNICAL', 'INTEGER', 'NUMBER', 0, 0, 70, null, null, '1', 1, 1, '["EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN"]', 'EQ', '[]'),
(2047489290000010298, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000262, 'writeType', '写入格式', '写入格式', 'TECHNICAL', 'STRING', 'SELECT', 0, 0, 80, null, null, 'SPLIT', 1, 1, '["EQ", "LIKE", "IN"]', 'LIKE', '["SPLIT", "RAWDATA", "JSON"]'),
(2047489290000010299, 'default', 0, '2026-08-31 10:00:00', '2026-08-31 10:00:00', 2047489290000000262, 'otherProperties', '额外 Producer 属性', '额外 Producer 属性', 'TECHNICAL', 'JSON', 'JSON_EDITOR', 0, 0, 90, null, null, '{}', 0, 0, '[]', null, '[]')
on duplicate key update
  schema_version_id = VALUES(schema_version_id), field_key = VALUES(field_key), field_name = VALUES(field_name),
  description = VALUES(description), scope = VALUES(scope), value_type = VALUES(value_type), component_type = VALUES(component_type),
  required_flag = VALUES(required_flag), sensitive_flag = VALUES(sensitive_flag), sort_order = VALUES(sort_order),
  validation_rule = VALUES(validation_rule), placeholder = VALUES(placeholder), default_value = VALUES(default_value),
  searchable_flag = VALUES(searchable_flag), sortable_flag = VALUES(sortable_flag), query_operators = VALUES(query_operators),
  query_default_operator = VALUES(query_default_operator), options = VALUES(options);

-- Kafka Topic belongs to data_model.physical_locator. Reader/Writer behavior
-- belongs to task runtime options, so these historical model fields are no
-- longer valid configuration sources.
delete from meta_field_definition
where schema_version_id = 2047489209979133954
  and field_key in ('queueName', 'topic', 'queue', 'brokers', 'consumerGroup', 'tag');

-- Remove the same deprecated keys from existing Kafka model instances. Topic
-- remains available through data_model.physical_locator.
update data_model model
inner join datasource_definition datasource
  on datasource.id = model.datasource_id
  and datasource.deleted = 0
set model.technical_metadata = json_remove(
      model.technical_metadata,
      '$.queueName', '$.topic', '$.queue', '$.brokers', '$.consumerGroup', '$.tag'),
    model.updated_at = current_timestamp
where model.deleted = 0
  and lower(datasource.type_code) = 'kafka'
  and model.technical_metadata is not null;

delete model_attr
from data_model_attr_index model_attr
inner join data_model model on model.id = model_attr.model_id
inner join datasource_definition datasource
  on datasource.id = model.datasource_id
  and datasource.deleted = 0
where model_attr.deleted = 0
  and model.deleted = 0
  and lower(datasource.type_code) = 'kafka'
  and model_attr.field_key in ('queueName', 'topic', 'queue', 'brokers', 'consumerGroup', 'tag');

COMMIT;