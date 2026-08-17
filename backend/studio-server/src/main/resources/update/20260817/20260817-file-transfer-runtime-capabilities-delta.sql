alter table datasource_type_capability
    add column runtime_capabilities_json json after writer_plugins_json;

insert ignore into datasource_type_capability (
    id, tenant_id, deleted, created_at, updated_at, type_code, type_name,
    enabled, readable, writable, executable, sql_executable, source_category,
    source_plugin, reader_plugins_json, writer_plugins_json,
    runtime_capabilities_json, sort_order, description
) values
(
    cast(conv(substr(md5('datasource_type_capability|default|local'), 1, 15), 16, 10) as unsigned),
    'default', 0, current_timestamp, current_timestamp, 'local', 'Local File',
    1, 0, 0, 1, 0, 'FILE_SYSTEM', 'local', '[]', '[]',
    '{"binaryFile":{"browse":true,"read":true,"write":true,"manage":true,"transferSource":true,"transferTarget":true}}',
    45, '本地文件系统'
),
(
    cast(conv(substr(md5('datasource_type_capability|default|oss'), 1, 15), 16, 10) as unsigned),
    'default', 0, current_timestamp, current_timestamp, 'oss', 'Aliyun OSS',
    1, 0, 0, 1, 0, 'FILE_SYSTEM', 'oss', '[]', '[]',
    '{"binaryFile":{"browse":true,"read":true,"write":true,"manage":true,"transferSource":true,"transferTarget":true}}',
    75, '阿里云 OSS 对象存储'
);

update datasource_type_capability
set runtime_capabilities_json = json_object()
where runtime_capabilities_json is null;

update datasource_type_capability
set runtime_capabilities_json = '{"binaryFile":{"browse":true,"read":true,"write":true,"manage":true,"transferSource":true,"transferTarget":true}}'
where type_code in ('local', 'ftp', 'sftp', 'minio', 'oss');
