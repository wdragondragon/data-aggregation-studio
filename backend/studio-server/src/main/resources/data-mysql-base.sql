-- sys_user
insert into sys_user (id, tenant_id, deleted, created_at, updated_at, username, password_hash, display_name, enabled, auth_source) values
(2047489207831650305, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'admin', '$2a$10$bDWuQaKZnZ0BVS32si5aZ.WNqzqpnnmnYxi7XMtlt0LgCgN17l/di', 'Studio Admin', 1, 'LOCAL');

-- sys_role
insert into sys_role (id, tenant_id, deleted, created_at, updated_at, code, name, description) values
(2047489207831650307, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'ADMIN', 'Administrator', 'Legacy full access role'),
(2047489207831650308, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'SUPER_ADMIN', 'Super Administrator', 'Global studio administrator'),
(2047489207831650309, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'TENANT_ADMIN', 'Tenant Administrator', 'Manage users, workers and projects in a tenant'),
(2047489207831650310, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'PROJECT_ADMIN', 'Project Administrator', 'Manage project members and project resources'),
(2047489207831650311, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'PROJECT_MEMBER', 'Project Member', 'Use project scoped resources');

-- sys_permission
insert into sys_permission (id, tenant_id, deleted, created_at, updated_at, code, name, http_method, path_pattern) values
(2047489207831650306, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'studio:*', 'Studio All', '*', '/api/v1/**');

-- sys_user_role
insert into sys_user_role (id, tenant_id, deleted, created_at, updated_at, user_id, role_id) values
(2047489207831650314, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489207831650305, 2047489207831650307),
(2047489207831650315, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489207831650305, 2047489207831650308);

-- sys_role_permission
insert into sys_role_permission (id, tenant_id, deleted, created_at, updated_at, role_id, permission_id) values
(2047489207831650312, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489207831650307, 2047489207831650306),
(2047489207831650313, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489207831650308, 2047489207831650306);

-- studio_tenant
insert into studio_tenant (id, tenant_id, deleted, created_at, updated_at, tenant_code, tenant_name, description, enabled) values
(2047489207831650316, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'default', 'Default Tenant', 'Bootstrap tenant for existing online studio data', 1);

-- studio_project
insert into studio_project (id, tenant_id, deleted, created_at, updated_at, project_code, project_name, description, enabled, default_project) values
(2047489207831650317, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'default', 'Default Project', 'Bootstrap project for existing online studio data', 1, 1);

-- studio_tenant_member
insert into studio_tenant_member (id, tenant_id, deleted, created_at, updated_at, user_id, role_code, status) values
(2047489207831650318, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489207831650305, 'TENANT_ADMIN', 'ACTIVE');

-- studio_project_member
insert into studio_project_member (id, tenant_id, deleted, created_at, updated_at, project_id, user_id, role_code, status) values
(2047489207831650319, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 2047489207831650317, 2047489207831650305, 'PROJECT_ADMIN', 'ACTIVE');

-- datasource_type_capability
insert into datasource_type_capability (id, tenant_id, deleted, created_at, updated_at, type_code, type_name, enabled, readable, writable, executable, sql_executable, source_category, source_plugin, reader_plugins_json, writer_plugins_json, sort_order, description) values
(2047489207915536386, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'mysql8', 'MySQL 8', 1, 1, 1, 1, 1, 'DATABASE', 'mysql8', '["mysql8"]', '["mysql8"]', 10, 'MySQL 数据库'),
(2047489207915536387, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'oracle', 'Oracle', 1, 1, 0, 1, 1, 'DATABASE', 'oracle', '["oracle"]', '[]', 20, 'Oracle 数据库'),
(2047489207915536388, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'postgres', 'PostgreSQL', 1, 1, 1, 1, 1, 'DATABASE', 'postgres', '["postgresql"]', '["postgresql"]', 30, 'PostgreSQL 数据库'),
(2047489207915536389, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'dm', '达梦数据库', 1, 1, 1, 1, 1, 'DATABASE', 'dm', '["dm"]', '["dm"]', 40, '达梦数据库'),
(2047489207961673730, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'ftp', 'FTP', 1, 1, 1, 1, 0, 'FILE_SYSTEM', 'ftp', '["ftp"]', '["ftp"]', 50, 'FTP 文件数据源'),
(2047489207961673731, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'sftp', 'SFTP', 1, 1, 1, 1, 0, 'FILE_SYSTEM', 'sftp', '["sftp"]', '["sftp"]', 60, 'SFTP 文件数据源'),
(2047489207961673732, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'minio', 'MinIO', 1, 1, 1, 1, 0, 'FILE_SYSTEM', 'minio', '["minio"]', '["minio"]', 70, 'MinIO / OSS 对象存储'),
(2047489207961673733, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'kafka', 'Kafka', 1, 1, 1, 1, 0, 'MESSAGE_QUEUE', 'kafka', '["kafka"]', '["kafka"]', 80, 'Kafka 消息队列'),
(2047489207961673734, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'rocketmq', 'RocketMQ', 1, 1, 1, 1, 0, 'MESSAGE_QUEUE', 'rocketmq', '["rocketmq"]', '["rocketmq"]', 90, 'RocketMQ 消息队列'),
(2047489207961673736, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'http', 'HTTP', 1, 1, 1, 1, 0, 'HTTP_API', 'http', '["httpreader"]', '["httpwriter"]', 95, 'HTTP 接口数据源'),
(2047489207961673735, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'rabbitmq', 'RabbitMQ', 1, 0, 0, 1, 0, 'MESSAGE_QUEUE', 'rabbitmq', '[]', '[]', 100, 'RabbitMQ 消息队列'),
(2047489208028782594, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'odps', 'ODPS', 1, 1, 1, 1, 1, 'DATABASE', 'odps', '["odpsreader"]', '["odpswriter"]', 110, 'ODPS / MaxCompute 数据源'),
(2047489208028782595, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'tbds-hdfs', 'TBDS HDFS', 1, 0, 0, 1, 0, 'FILE_SYSTEM', 'tbds-hdfs', '[]', '[]', 120, 'TBDS HDFS 文件系统'),
(2047489208028782596, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'tbds-hdfs3', 'TBDS HDFS3', 1, 0, 0, 1, 0, 'FILE_SYSTEM', 'tbds-hdfs3', '[]', '[]', 130, 'TBDS HDFS3 文件系统'),
(2047489208028782597, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'tbds-hive2', 'TBDS Hive2', 1, 1, 0, 1, 1, 'DATABASE', 'tbds-hive2', '["tbds-hive2"]', '[]', 140, 'TBDS Hive2 数据源'),
(2047489208028782598, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'tbds-hive3', 'TBDS Hive3', 1, 0, 0, 1, 1, 'DATABASE', 'tbds-hive3', '[]', '[]', 150, 'TBDS Hive3 数据源'),
(2047489208028782599, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'influxdb', 'InfluxDB', 1, 0, 0, 1, 0, 'DATABASE', 'influxdb', '[]', '[]', 160, 'InfluxDB 数据源'),
(2047489208028782600, 'default', 0, '2026-04-24 09:34:02', '2026-04-24 09:34:02', 'influxdbv1', 'InfluxDB v1', 1, 1, 1, 1, 0, 'DATABASE', 'influxdbv1', '["influxdbv1"]', '["influxdbv1"]', 170, 'InfluxDB v1 数据源');

