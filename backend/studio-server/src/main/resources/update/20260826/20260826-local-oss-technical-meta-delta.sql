-- Data Aggregation Studio 增量数据脚本
-- 目标：为已初始化的 MySQL 环境补充 LOCAL 与 OSS 技术元模型。
-- 范围：仅内置元模型数据；不新增业务表，不改变开放接口。
-- 说明：按租户和 schema_code 收敛，兼容启动同步服务已经生成不同主键的环境。

START TRANSACTION;

-- 先恢复或更新已存在的内置 schema，避免同一 schema_code 被重复创建。
update meta_schema
set deleted = 0,
    updated_at = '2026-08-25 00:00:00',
    schema_name = 'LOCAL 数据源信息',
    object_type = 'datasource',
    type_code = 'local',
    status = 'DRAFT',
    description = 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"local","metaModelCode":"source","metaModelName":"数据源信息","displayMode":"SINGLE","required":true,"syncStrategy":"DATASOURCE_CONNECTION"}\n用于采集 LOCAL 数据源信息 的技术元模型定义。'
where tenant_id = 'default' and schema_code = 'technical:local:source';

update meta_schema
set deleted = 0,
    updated_at = '2026-08-25 00:00:00',
    schema_name = 'LOCAL 表信息',
    object_type = 'model',
    type_code = 'local.table',
    status = 'DRAFT',
    description = 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"local","metaModelCode":"table","metaModelName":"表信息","displayMode":"SINGLE","required":true,"syncStrategy":"OBJECT_DISCOVERY"}\n用于采集 LOCAL 表信息 的技术元模型定义。'
where tenant_id = 'default' and schema_code = 'technical:local:table';

update meta_schema
set deleted = 0,
    updated_at = '2026-08-25 00:00:00',
    schema_name = 'LOCAL 字段信息',
    object_type = 'model',
    type_code = 'local.field',
    status = 'DRAFT',
    description = 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"local","metaModelCode":"field","metaModelName":"字段信息","displayMode":"MULTIPLE","required":true,"syncStrategy":"COLUMN_DISCOVERY"}\n用于采集 LOCAL 字段信息 的技术元模型定义。'
where tenant_id = 'default' and schema_code = 'technical:local:field';

update meta_schema
set deleted = 0,
    updated_at = '2026-08-25 00:00:00',
    schema_name = 'OSS 数据源信息',
    object_type = 'datasource',
    type_code = 'oss',
    status = 'DRAFT',
    description = 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"oss","metaModelCode":"source","metaModelName":"数据源信息","displayMode":"SINGLE","required":true,"syncStrategy":"DATASOURCE_CONNECTION"}\n用于采集 OSS 数据源信息 的技术元模型定义。'
where tenant_id = 'default' and schema_code = 'technical:oss:source';

update meta_schema
set deleted = 0,
    updated_at = '2026-08-25 00:00:00',
    schema_name = 'OSS 表信息',
    object_type = 'model',
    type_code = 'oss.table',
    status = 'DRAFT',
    description = 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"oss","metaModelCode":"table","metaModelName":"表信息","displayMode":"SINGLE","required":true,"syncStrategy":"OBJECT_DISCOVERY"}\n用于采集 OSS 表信息 的技术元模型定义。'
where tenant_id = 'default' and schema_code = 'technical:oss:table';

update meta_schema
set deleted = 0,
    updated_at = '2026-08-25 00:00:00',
    schema_name = 'OSS 字段信息',
    object_type = 'model',
    type_code = 'oss.field',
    status = 'DRAFT',
    description = 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"oss","metaModelCode":"field","metaModelName":"字段信息","displayMode":"MULTIPLE","required":true,"syncStrategy":"COLUMN_DISCOVERY"}\n用于采集 OSS 字段信息 的技术元模型定义。'
where tenant_id = 'default' and schema_code = 'technical:oss:field';

insert into meta_schema (id, tenant_id, deleted, created_at, updated_at, schema_code, schema_name, object_type, type_code, current_version_id, status, description)
select 2047489300000000101, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', 'technical:local:source', 'LOCAL 数据源信息', 'datasource', 'local', 2047489300000000102, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"local","metaModelCode":"source","metaModelName":"数据源信息","displayMode":"SINGLE","required":true,"syncStrategy":"DATASOURCE_CONNECTION"}\n用于采集 LOCAL 数据源信息 的技术元模型定义。'
from dual
where not exists (select 1 from meta_schema where tenant_id = 'default' and schema_code = 'technical:local:source' and deleted = 0);

insert into meta_schema (id, tenant_id, deleted, created_at, updated_at, schema_code, schema_name, object_type, type_code, current_version_id, status, description)
select 2047489300000000111, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', 'technical:local:table', 'LOCAL 表信息', 'model', 'local.table', 2047489300000000112, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"local","metaModelCode":"table","metaModelName":"表信息","displayMode":"SINGLE","required":true,"syncStrategy":"OBJECT_DISCOVERY"}\n用于采集 LOCAL 表信息 的技术元模型定义。'
from dual
where not exists (select 1 from meta_schema where tenant_id = 'default' and schema_code = 'technical:local:table' and deleted = 0);

insert into meta_schema (id, tenant_id, deleted, created_at, updated_at, schema_code, schema_name, object_type, type_code, current_version_id, status, description)
select 2047489300000000121, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', 'technical:local:field', 'LOCAL 字段信息', 'model', 'local.field', 2047489300000000122, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"local","metaModelCode":"field","metaModelName":"字段信息","displayMode":"MULTIPLE","required":true,"syncStrategy":"COLUMN_DISCOVERY"}\n用于采集 LOCAL 字段信息 的技术元模型定义。'
from dual
where not exists (select 1 from meta_schema where tenant_id = 'default' and schema_code = 'technical:local:field' and deleted = 0);

insert into meta_schema (id, tenant_id, deleted, created_at, updated_at, schema_code, schema_name, object_type, type_code, current_version_id, status, description)
select 2047489300000000131, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', 'technical:oss:source', 'OSS 数据源信息', 'datasource', 'oss', 2047489300000000132, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"oss","metaModelCode":"source","metaModelName":"数据源信息","displayMode":"SINGLE","required":true,"syncStrategy":"DATASOURCE_CONNECTION"}\n用于采集 OSS 数据源信息 的技术元模型定义。'
from dual
where not exists (select 1 from meta_schema where tenant_id = 'default' and schema_code = 'technical:oss:source' and deleted = 0);

insert into meta_schema (id, tenant_id, deleted, created_at, updated_at, schema_code, schema_name, object_type, type_code, current_version_id, status, description)
select 2047489300000000141, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', 'technical:oss:table', 'OSS 表信息', 'model', 'oss.table', 2047489300000000142, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"oss","metaModelCode":"table","metaModelName":"表信息","displayMode":"SINGLE","required":true,"syncStrategy":"OBJECT_DISCOVERY"}\n用于采集 OSS 表信息 的技术元模型定义。'
from dual
where not exists (select 1 from meta_schema where tenant_id = 'default' and schema_code = 'technical:oss:table' and deleted = 0);

insert into meta_schema (id, tenant_id, deleted, created_at, updated_at, schema_code, schema_name, object_type, type_code, current_version_id, status, description)
select 2047489300000000151, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', 'technical:oss:field', 'OSS 字段信息', 'model', 'oss.field', 2047489300000000152, 'DRAFT', 'META_MODEL_CONFIG:{"domain":"TECHNICAL","datasourceType":"oss","metaModelCode":"field","metaModelName":"字段信息","displayMode":"MULTIPLE","required":true,"syncStrategy":"COLUMN_DISCOVERY"}\n用于采集 OSS 字段信息 的技术元模型定义。'
from dual
where not exists (select 1 from meta_schema where tenant_id = 'default' and schema_code = 'technical:oss:field' and deleted = 0);

-- 恢复或创建每个 schema 的第一个版本，并把当前版本指向实际主键。
update meta_schema_version v
join meta_schema s on s.id = v.schema_id
set v.deleted = 0,
    v.updated_at = '2026-08-25 00:00:00',
    v.version_number = 1,
    v.status = 'DRAFT',
    v.description = s.description
where s.tenant_id = 'default'
  and s.deleted = 0
  and s.schema_code in ('technical:local:source', 'technical:local:table', 'technical:local:field',
                        'technical:oss:source', 'technical:oss:table', 'technical:oss:field')
  and v.version_number = 1;

insert into meta_schema_version (id, tenant_id, deleted, created_at, updated_at, schema_id, version_number, status, description)
select 2047489300000000102, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', s.id, 1, 'DRAFT', s.description
from meta_schema s
where s.tenant_id = 'default' and s.deleted = 0 and s.schema_code = 'technical:local:source'
  and not exists (select 1 from meta_schema_version v where v.schema_id = s.id and v.version_number = 1 and v.deleted = 0);

insert into meta_schema_version (id, tenant_id, deleted, created_at, updated_at, schema_id, version_number, status, description)
select 2047489300000000112, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', s.id, 1, 'DRAFT', s.description
from meta_schema s
where s.tenant_id = 'default' and s.deleted = 0 and s.schema_code = 'technical:local:table'
  and not exists (select 1 from meta_schema_version v where v.schema_id = s.id and v.version_number = 1 and v.deleted = 0);

insert into meta_schema_version (id, tenant_id, deleted, created_at, updated_at, schema_id, version_number, status, description)
select 2047489300000000122, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', s.id, 1, 'DRAFT', s.description
from meta_schema s
where s.tenant_id = 'default' and s.deleted = 0 and s.schema_code = 'technical:local:field'
  and not exists (select 1 from meta_schema_version v where v.schema_id = s.id and v.version_number = 1 and v.deleted = 0);

insert into meta_schema_version (id, tenant_id, deleted, created_at, updated_at, schema_id, version_number, status, description)
select 2047489300000000132, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', s.id, 1, 'DRAFT', s.description
from meta_schema s
where s.tenant_id = 'default' and s.deleted = 0 and s.schema_code = 'technical:oss:source'
  and not exists (select 1 from meta_schema_version v where v.schema_id = s.id and v.version_number = 1 and v.deleted = 0);

insert into meta_schema_version (id, tenant_id, deleted, created_at, updated_at, schema_id, version_number, status, description)
select 2047489300000000142, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', s.id, 1, 'DRAFT', s.description
from meta_schema s
where s.tenant_id = 'default' and s.deleted = 0 and s.schema_code = 'technical:oss:table'
  and not exists (select 1 from meta_schema_version v where v.schema_id = s.id and v.version_number = 1 and v.deleted = 0);

insert into meta_schema_version (id, tenant_id, deleted, created_at, updated_at, schema_id, version_number, status, description)
select 2047489300000000152, 'default', 0, '2026-08-25 00:00:00', '2026-08-25 00:00:00', s.id, 1, 'DRAFT', s.description
from meta_schema s
where s.tenant_id = 'default' and s.deleted = 0 and s.schema_code = 'technical:oss:field'
  and not exists (select 1 from meta_schema_version v where v.schema_id = s.id and v.version_number = 1 and v.deleted = 0);

update meta_schema s
join meta_schema_version v on v.schema_id = s.id and v.version_number = 1 and v.deleted = 0
set s.current_version_id = v.id,
    s.updated_at = '2026-08-25 00:00:00'
where s.tenant_id = 'default'
  and s.deleted = 0
  and s.schema_code in ('technical:local:source', 'technical:local:table', 'technical:local:field',
                        'technical:oss:source', 'technical:oss:table', 'technical:oss:field');

COMMIT;
