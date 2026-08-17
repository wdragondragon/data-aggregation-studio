package com.jdragon.studio.infra.service;

import org.springframework.jdbc.core.JdbcTemplate;

final class StudioDatasourceCapabilityUpgradeSupport {

    private final JdbcTemplate jdbcTemplate;
    private final StudioSchemaIntrospector schemaIntrospector;

    StudioDatasourceCapabilityUpgradeSupport(JdbcTemplate jdbcTemplate, StudioSchemaIntrospector schemaIntrospector) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaIntrospector = schemaIntrospector;
    }

    void ensureDatasourceTypeCapabilityTablesMysql() {
        jdbcTemplate.execute("create table if not exists datasource_type_capability (" +
                "id bigint primary key," +
                "tenant_id varchar(64) default 'default'," +
                "deleted int default 0," +
                "created_at datetime default current_timestamp," +
                "updated_at datetime default current_timestamp," +
                "type_code varchar(128) not null," +
                "type_name varchar(255) not null," +
                "enabled int default 1," +
                "readable int default 0," +
                "writable int default 0," +
                "executable int default 0," +
                "sql_executable int default 0," +
                "source_category varchar(32) not null default 'DATABASE'," +
                "source_plugin varchar(128)," +
                "reader_plugins_json json," +
                "writer_plugins_json json," +
                "runtime_capabilities_json json," +
                "sort_order int default 0," +
                "description varchar(1000)" +
                ")");
        ensureColumn("datasource_type_capability", "source_category",
                "alter table datasource_type_capability add column source_category varchar(32) not null default 'DATABASE' after sql_executable");
        ensureColumn("datasource_type_capability", "runtime_capabilities_json",
                "alter table datasource_type_capability add column runtime_capabilities_json json after writer_plugins_json");
        ensureIndex("datasource_type_capability", "uk_datasource_type_capability_code",
                "alter table datasource_type_capability add unique key uk_datasource_type_capability_code (tenant_id, type_code)");
        backfillDatasourceTypeCapabilityCategoriesMysql();
        seedDatasourceTypeCapabilitiesMysql();
        backfillDatasourceTypeRuntimeCapabilitiesMysql();
        normalizeDatasourceTypeCapabilityMetadataMysql();
    }

    private void seedDatasourceTypeCapabilitiesMysql() {
        insertDatasourceTypeCapabilityMysql("mysql8", "MySQL 8", "DATABASE", 1, 1, 1, 1, 1, "mysql8", "[\"mysql8\"]", "[\"mysql8\"]", 10, "MySQL 数据库");
        insertDatasourceTypeCapabilityMysql("oracle", "Oracle", "DATABASE", 1, 1, 0, 1, 1, "oracle", "[\"oracle\"]", "[]", 20, "Oracle 数据库");
        insertDatasourceTypeCapabilityMysql("postgres", "PostgreSQL", "DATABASE", 1, 1, 1, 1, 1, "postgres", "[\"postgresql\"]", "[\"postgresql\"]", 30, "PostgreSQL 数据库");
        insertDatasourceTypeCapabilityMysql("dm", "达梦数据库", "DATABASE", 1, 1, 1, 1, 1, "dm", "[\"dm\"]", "[\"dm\"]", 40, "达梦数据库");
        insertDatasourceTypeCapabilityMysql("local", "Local File", "FILE_SYSTEM", 1, 0, 0, 1, 0, "local", "[]", "[]", 45, "本地文件系统");
        insertDatasourceTypeCapabilityMysql("ftp", "FTP", "FILE_SYSTEM", 1, 0, 0, 1, 0, "ftp", "[]", "[]", 50, "FTP 文件数据源");
        insertDatasourceTypeCapabilityMysql("sftp", "SFTP", "FILE_SYSTEM", 1, 0, 0, 1, 0, "sftp", "[]", "[]", 60, "SFTP 文件数据源");
        insertDatasourceTypeCapabilityMysql("minio", "MinIO", "FILE_SYSTEM", 1, 0, 0, 1, 0, "minio", "[]", "[]", 70, "MinIO 对象存储");
        insertDatasourceTypeCapabilityMysql("oss", "Aliyun OSS", "FILE_SYSTEM", 1, 0, 0, 1, 0, "oss", "[]", "[]", 75, "阿里云 OSS 对象存储");
        insertDatasourceTypeCapabilityMysql("kafka", "Kafka", "MESSAGE_QUEUE", 1, 1, 1, 1, 0, "kafka", "[\"kafka\"]", "[\"kafka\"]", 80, "Kafka 消息队列");
        insertDatasourceTypeCapabilityMysql("rocketmq", "RocketMQ", "MESSAGE_QUEUE", 1, 1, 1, 1, 0, "rocketmq", "[\"rocketmq\"]", "[\"rocketmq\"]", 90, "RocketMQ 消息队列");
        insertDatasourceTypeCapabilityMysql("http", "HTTP", "HTTP_API", 1, 1, 1, 1, 0, "http", "[\"httpreader\"]", "[\"httpwriter\"]", 95, "HTTP 接口数据源");
        insertDatasourceTypeCapabilityMysql("rabbitmq", "RabbitMQ", "MESSAGE_QUEUE", 1, 0, 0, 1, 0, "rabbitmq", "[]", "[]", 100, "RabbitMQ 消息队列");
        insertDatasourceTypeCapabilityMysql("odps", "ODPS", "DATABASE", 1, 1, 1, 1, 1, "odps", "[\"odpsreader\"]", "[\"odpswriter\"]", 110, "ODPS / MaxCompute 数据源");
        insertDatasourceTypeCapabilityMysql("tbds-hdfs", "TBDS HDFS", "FILE_SYSTEM", 1, 0, 0, 1, 0, "tbds-hdfs", "[]", "[]", 120, "TBDS HDFS 文件系统");
        insertDatasourceTypeCapabilityMysql("tbds-hdfs3", "TBDS HDFS3", "FILE_SYSTEM", 1, 0, 0, 1, 0, "tbds-hdfs3", "[]", "[]", 130, "TBDS HDFS3 文件系统");
        insertDatasourceTypeCapabilityMysql("tbds-hive2", "TBDS Hive2", "DATABASE", 1, 1, 0, 1, 1, "tbds-hive2", "[\"tbds-hive2\"]", "[]", 140, "TBDS Hive2 数据源");
        insertDatasourceTypeCapabilityMysql("tbds-hive3", "TBDS Hive3", "DATABASE", 1, 0, 0, 1, 1, "tbds-hive3", "[]", "[]", 150, "TBDS Hive3 数据源");
        insertDatasourceTypeCapabilityMysql("influxdb", "InfluxDB", "DATABASE", 1, 0, 0, 1, 0, "influxdb", "[]", "[]", 160, "InfluxDB 数据源");
        insertDatasourceTypeCapabilityMysql("influxdbv1", "InfluxDB v1", "DATABASE", 1, 1, 1, 1, 0, "influxdbv1", "[\"influxdbv1\"]", "[\"influxdbv1\"]", 170, "InfluxDB v1 数据源");
    }

    private void insertDatasourceTypeCapabilityMysql(String typeCode,
                                                     String typeName,
                                                     String sourceCategory,
                                                     int enabled,
                                                     int readable,
                                                     int writable,
                                                     int executable,
                                                     int sqlExecutable,
                                                     String sourcePlugin,
                                                     String readerPluginsJson,
                                                     String writerPluginsJson,
                                                     int sortOrder,
                                                     String description) {
        jdbcTemplate.update("insert ignore into datasource_type_capability (" +
                        "id, tenant_id, deleted, created_at, updated_at, type_code, type_name, source_category, enabled, readable, writable, executable, sql_executable, source_plugin, reader_plugins_json, writer_plugins_json, sort_order, description" +
                        ") values (cast(conv(substr(md5(?), 1, 15), 16, 10) as unsigned), 'default', 0, current_timestamp, current_timestamp, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "datasource_type_capability|default|" + typeCode,
                typeCode,
                typeName,
                sourceCategory,
                Integer.valueOf(enabled),
                Integer.valueOf(readable),
                Integer.valueOf(writable),
                Integer.valueOf(executable),
                Integer.valueOf(sqlExecutable),
                sourcePlugin,
                readerPluginsJson,
                writerPluginsJson,
                Integer.valueOf(sortOrder),
                description);
    }

    private void backfillDatasourceTypeCapabilityCategoriesMysql() {
        jdbcTemplate.update("update datasource_type_capability set source_category = 'FILE_SYSTEM' where type_code in ('local', 'ftp', 'sftp', 'minio', 'oss', 'tbds-hdfs', 'tbds-hdfs3')");
        jdbcTemplate.update("update datasource_type_capability set source_category = 'MESSAGE_QUEUE' where type_code in ('kafka', 'rocketmq', 'rabbitmq')");
        jdbcTemplate.update("update datasource_type_capability set source_category = 'HTTP_API' where type_code = 'http'");
        jdbcTemplate.update("update datasource_type_capability set writable = 1, writer_plugins_json = '[\"httpwriter\"]' where type_code = 'http'");
        jdbcTemplate.update("update datasource_type_capability set source_category = 'DATABASE' where source_category is null or source_category = ''");
    }

    private void backfillDatasourceTypeRuntimeCapabilitiesMysql() {
        jdbcTemplate.update("update datasource_type_capability set runtime_capabilities_json = json_object() where runtime_capabilities_json is null");
        jdbcTemplate.update("update datasource_type_capability set runtime_capabilities_json = '{\"binaryFile\":{\"browse\":true,\"read\":true,\"write\":true,\"manage\":true,\"transferSource\":true,\"transferTarget\":true}}' where type_code in ('local', 'ftp', 'sftp', 'minio', 'oss')");
    }

    private void normalizeDatasourceTypeCapabilityMetadataMysql() {
        normalizeDatasourceTypeCapabilityMetadataMysql("mysql8", "MySQL 8", "DATABASE", "MySQL 数据库");
        normalizeDatasourceTypeCapabilityMetadataMysql("oracle", "Oracle", "DATABASE", "Oracle 数据库");
        normalizeDatasourceTypeCapabilityMetadataMysql("postgres", "PostgreSQL", "DATABASE", "PostgreSQL 数据库");
        normalizeDatasourceTypeCapabilityMetadataMysql("dm", "达梦数据库", "DATABASE", "达梦数据库");
        normalizeDatasourceTypeCapabilityMetadataMysql("local", "Local File", "FILE_SYSTEM", "本地文件系统");
        normalizeDatasourceTypeCapabilityMetadataMysql("ftp", "FTP", "FILE_SYSTEM", "FTP 文件数据源");
        normalizeDatasourceTypeCapabilityMetadataMysql("sftp", "SFTP", "FILE_SYSTEM", "SFTP 文件数据源");
        normalizeDatasourceTypeCapabilityMetadataMysql("minio", "MinIO", "FILE_SYSTEM", "MinIO 对象存储");
        normalizeDatasourceTypeCapabilityMetadataMysql("oss", "Aliyun OSS", "FILE_SYSTEM", "阿里云 OSS 对象存储");
        normalizeDatasourceTypeCapabilityMetadataMysql("kafka", "Kafka", "MESSAGE_QUEUE", "Kafka 消息队列");
        normalizeDatasourceTypeCapabilityMetadataMysql("rocketmq", "RocketMQ", "MESSAGE_QUEUE", "RocketMQ 消息队列");
        normalizeDatasourceTypeCapabilityMetadataMysql("http", "HTTP", "HTTP_API", "HTTP 接口数据源");
        normalizeDatasourceTypeCapabilityMetadataMysql("rabbitmq", "RabbitMQ", "MESSAGE_QUEUE", "RabbitMQ 消息队列");
        normalizeDatasourceTypeCapabilityMetadataMysql("odps", "ODPS", "DATABASE", "ODPS / MaxCompute 数据源");
        normalizeDatasourceTypeCapabilityMetadataMysql("tbds-hdfs", "TBDS HDFS", "FILE_SYSTEM", "TBDS HDFS 文件系统");
        normalizeDatasourceTypeCapabilityMetadataMysql("tbds-hdfs3", "TBDS HDFS3", "FILE_SYSTEM", "TBDS HDFS3 文件系统");
        normalizeDatasourceTypeCapabilityMetadataMysql("tbds-hive2", "TBDS Hive2", "DATABASE", "TBDS Hive2 数据源");
        normalizeDatasourceTypeCapabilityMetadataMysql("tbds-hive3", "TBDS Hive3", "DATABASE", "TBDS Hive3 数据源");
        normalizeDatasourceTypeCapabilityMetadataMysql("influxdb", "InfluxDB", "DATABASE", "InfluxDB 数据源");
        normalizeDatasourceTypeCapabilityMetadataMysql("influxdbv1", "InfluxDB v1", "DATABASE", "InfluxDB v1 数据源");
    }

    private void normalizeDatasourceTypeCapabilityMetadataMysql(String typeCode,
                                                                String typeName,
                                                                String sourceCategory,
                                                                String description) {
        jdbcTemplate.update("update datasource_type_capability set type_name = ?, source_category = ?, description = ? where tenant_id = 'default' and type_code = ?",
                typeName, sourceCategory, description, typeCode);
    }

    void ensureDatasourceTypeCapabilityTablesSqlite() {
        jdbcTemplate.execute("create table if not exists datasource_type_capability (" +
                "id integer primary key," +
                "tenant_id text default 'default'," +
                "deleted integer default 0," +
                "created_at text," +
                "updated_at text," +
                "type_code text not null," +
                "type_name text not null," +
                "enabled integer default 1," +
                "readable integer default 0," +
                "writable integer default 0," +
                "executable integer default 0," +
                "sql_executable integer default 0," +
                "source_category text not null default 'DATABASE'," +
                "source_plugin text," +
                "reader_plugins_json text," +
                "writer_plugins_json text," +
                "runtime_capabilities_json text," +
                "sort_order integer default 0," +
                "description text" +
                ")");
        ensureColumn("datasource_type_capability", "source_category",
                "alter table datasource_type_capability add column source_category text not null default 'DATABASE'");
        ensureColumn("datasource_type_capability", "runtime_capabilities_json",
                "alter table datasource_type_capability add column runtime_capabilities_json text");
        jdbcTemplate.execute("create unique index if not exists uk_datasource_type_capability_code on datasource_type_capability(tenant_id, type_code)");
        backfillDatasourceTypeCapabilityCategoriesSqlite();
        seedDatasourceTypeCapabilitiesSqlite();
        backfillDatasourceTypeRuntimeCapabilitiesSqlite();
        normalizeDatasourceTypeCapabilityMetadataSqlite();
    }

    private void seedDatasourceTypeCapabilitiesSqlite() {
        insertDatasourceTypeCapabilitySqlite("mysql8", "MySQL 8", "DATABASE", 1, 1, 1, 1, 1, "mysql8", "[\"mysql8\"]", "[\"mysql8\"]", 10, "MySQL 数据库");
        insertDatasourceTypeCapabilitySqlite("oracle", "Oracle", "DATABASE", 1, 1, 0, 1, 1, "oracle", "[\"oracle\"]", "[]", 20, "Oracle 数据库");
        insertDatasourceTypeCapabilitySqlite("postgres", "PostgreSQL", "DATABASE", 1, 1, 1, 1, 1, "postgres", "[\"postgresql\"]", "[\"postgresql\"]", 30, "PostgreSQL 数据库");
        insertDatasourceTypeCapabilitySqlite("dm", "达梦数据库", "DATABASE", 1, 1, 1, 1, 1, "dm", "[\"dm\"]", "[\"dm\"]", 40, "达梦数据库");
        insertDatasourceTypeCapabilitySqlite("local", "Local File", "FILE_SYSTEM", 1, 0, 0, 1, 0, "local", "[]", "[]", 45, "本地文件系统");
        insertDatasourceTypeCapabilitySqlite("ftp", "FTP", "FILE_SYSTEM", 1, 0, 0, 1, 0, "ftp", "[]", "[]", 50, "FTP 文件数据源");
        insertDatasourceTypeCapabilitySqlite("sftp", "SFTP", "FILE_SYSTEM", 1, 0, 0, 1, 0, "sftp", "[]", "[]", 60, "SFTP 文件数据源");
        insertDatasourceTypeCapabilitySqlite("minio", "MinIO", "FILE_SYSTEM", 1, 0, 0, 1, 0, "minio", "[]", "[]", 70, "MinIO 对象存储");
        insertDatasourceTypeCapabilitySqlite("oss", "Aliyun OSS", "FILE_SYSTEM", 1, 0, 0, 1, 0, "oss", "[]", "[]", 75, "阿里云 OSS 对象存储");
        insertDatasourceTypeCapabilitySqlite("kafka", "Kafka", "MESSAGE_QUEUE", 1, 1, 1, 1, 0, "kafka", "[\"kafka\"]", "[\"kafka\"]", 80, "Kafka 消息队列");
        insertDatasourceTypeCapabilitySqlite("rocketmq", "RocketMQ", "MESSAGE_QUEUE", 1, 1, 1, 1, 0, "rocketmq", "[\"rocketmq\"]", "[\"rocketmq\"]", 90, "RocketMQ 消息队列");
        insertDatasourceTypeCapabilitySqlite("http", "HTTP", "HTTP_API", 1, 1, 1, 1, 0, "http", "[\"httpreader\"]", "[\"httpwriter\"]", 95, "HTTP 接口数据源");
        insertDatasourceTypeCapabilitySqlite("rabbitmq", "RabbitMQ", "MESSAGE_QUEUE", 1, 0, 0, 1, 0, "rabbitmq", "[]", "[]", 100, "RabbitMQ 消息队列");
        insertDatasourceTypeCapabilitySqlite("odps", "ODPS", "DATABASE", 1, 1, 1, 1, 1, "odps", "[\"odpsreader\"]", "[\"odpswriter\"]", 110, "ODPS / MaxCompute 数据源");
        insertDatasourceTypeCapabilitySqlite("tbds-hdfs", "TBDS HDFS", "FILE_SYSTEM", 1, 0, 0, 1, 0, "tbds-hdfs", "[]", "[]", 120, "TBDS HDFS 文件系统");
        insertDatasourceTypeCapabilitySqlite("tbds-hdfs3", "TBDS HDFS3", "FILE_SYSTEM", 1, 0, 0, 1, 0, "tbds-hdfs3", "[]", "[]", 130, "TBDS HDFS3 文件系统");
        insertDatasourceTypeCapabilitySqlite("tbds-hive2", "TBDS Hive2", "DATABASE", 1, 1, 0, 1, 1, "tbds-hive2", "[\"tbds-hive2\"]", "[]", 140, "TBDS Hive2 数据源");
        insertDatasourceTypeCapabilitySqlite("tbds-hive3", "TBDS Hive3", "DATABASE", 1, 0, 0, 1, 1, "tbds-hive3", "[]", "[]", 150, "TBDS Hive3 数据源");
        insertDatasourceTypeCapabilitySqlite("influxdb", "InfluxDB", "DATABASE", 1, 0, 0, 1, 0, "influxdb", "[]", "[]", 160, "InfluxDB 数据源");
        insertDatasourceTypeCapabilitySqlite("influxdbv1", "InfluxDB v1", "DATABASE", 1, 1, 1, 1, 0, "influxdbv1", "[\"influxdbv1\"]", "[\"influxdbv1\"]", 170, "InfluxDB v1 数据源");
    }

    private void insertDatasourceTypeCapabilitySqlite(String typeCode,
                                                      String typeName,
                                                      String sourceCategory,
                                                      int enabled,
                                                      int readable,
                                                      int writable,
                                                      int executable,
                                                      int sqlExecutable,
                                                      String sourcePlugin,
                                                      String readerPluginsJson,
                                                      String writerPluginsJson,
                                                      int sortOrder,
                                                      String description) {
        jdbcTemplate.update("insert or ignore into datasource_type_capability (" +
                        "id, tenant_id, deleted, created_at, updated_at, type_code, type_name, source_category, enabled, readable, writable, executable, sql_executable, source_plugin, reader_plugins_json, writer_plugins_json, sort_order, description" +
                        ") values (abs(random()), 'default', 0, datetime('now'), datetime('now'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                typeCode,
                typeName,
                sourceCategory,
                Integer.valueOf(enabled),
                Integer.valueOf(readable),
                Integer.valueOf(writable),
                Integer.valueOf(executable),
                Integer.valueOf(sqlExecutable),
                sourcePlugin,
                readerPluginsJson,
                writerPluginsJson,
                Integer.valueOf(sortOrder),
                description);
    }

    private void backfillDatasourceTypeCapabilityCategoriesSqlite() {
        jdbcTemplate.update("update datasource_type_capability set source_category = 'FILE_SYSTEM' where type_code in ('local', 'ftp', 'sftp', 'minio', 'oss', 'tbds-hdfs', 'tbds-hdfs3')");
        jdbcTemplate.update("update datasource_type_capability set source_category = 'MESSAGE_QUEUE' where type_code in ('kafka', 'rocketmq', 'rabbitmq')");
        jdbcTemplate.update("update datasource_type_capability set source_category = 'HTTP_API' where type_code = 'http'");
        jdbcTemplate.update("update datasource_type_capability set writable = 1, writer_plugins_json = '[\"httpwriter\"]' where type_code = 'http'");
        jdbcTemplate.update("update datasource_type_capability set source_category = 'DATABASE' where source_category is null or source_category = ''");
    }

    private void backfillDatasourceTypeRuntimeCapabilitiesSqlite() {
        jdbcTemplate.update("update datasource_type_capability set runtime_capabilities_json = '{}' where runtime_capabilities_json is null");
        jdbcTemplate.update("update datasource_type_capability set runtime_capabilities_json = '{\"binaryFile\":{\"browse\":true,\"read\":true,\"write\":true,\"manage\":true,\"transferSource\":true,\"transferTarget\":true}}' where type_code in ('local', 'ftp', 'sftp', 'minio', 'oss')");
    }

    private void normalizeDatasourceTypeCapabilityMetadataSqlite() {
        normalizeDatasourceTypeCapabilityMetadataSqlite("mysql8", "MySQL 8", "DATABASE", "MySQL 数据库");
        normalizeDatasourceTypeCapabilityMetadataSqlite("oracle", "Oracle", "DATABASE", "Oracle 数据库");
        normalizeDatasourceTypeCapabilityMetadataSqlite("postgres", "PostgreSQL", "DATABASE", "PostgreSQL 数据库");
        normalizeDatasourceTypeCapabilityMetadataSqlite("dm", "达梦数据库", "DATABASE", "达梦数据库");
        normalizeDatasourceTypeCapabilityMetadataSqlite("local", "Local File", "FILE_SYSTEM", "本地文件系统");
        normalizeDatasourceTypeCapabilityMetadataSqlite("ftp", "FTP", "FILE_SYSTEM", "FTP 文件数据源");
        normalizeDatasourceTypeCapabilityMetadataSqlite("sftp", "SFTP", "FILE_SYSTEM", "SFTP 文件数据源");
        normalizeDatasourceTypeCapabilityMetadataSqlite("minio", "MinIO", "FILE_SYSTEM", "MinIO 对象存储");
        normalizeDatasourceTypeCapabilityMetadataSqlite("oss", "Aliyun OSS", "FILE_SYSTEM", "阿里云 OSS 对象存储");
        normalizeDatasourceTypeCapabilityMetadataSqlite("kafka", "Kafka", "MESSAGE_QUEUE", "Kafka 消息队列");
        normalizeDatasourceTypeCapabilityMetadataSqlite("rocketmq", "RocketMQ", "MESSAGE_QUEUE", "RocketMQ 消息队列");
        normalizeDatasourceTypeCapabilityMetadataSqlite("http", "HTTP", "HTTP_API", "HTTP 接口数据源");
        normalizeDatasourceTypeCapabilityMetadataSqlite("rabbitmq", "RabbitMQ", "MESSAGE_QUEUE", "RabbitMQ 消息队列");
        normalizeDatasourceTypeCapabilityMetadataSqlite("odps", "ODPS", "DATABASE", "ODPS / MaxCompute 数据源");
        normalizeDatasourceTypeCapabilityMetadataSqlite("tbds-hdfs", "TBDS HDFS", "FILE_SYSTEM", "TBDS HDFS 文件系统");
        normalizeDatasourceTypeCapabilityMetadataSqlite("tbds-hdfs3", "TBDS HDFS3", "FILE_SYSTEM", "TBDS HDFS3 文件系统");
        normalizeDatasourceTypeCapabilityMetadataSqlite("tbds-hive2", "TBDS Hive2", "DATABASE", "TBDS Hive2 数据源");
        normalizeDatasourceTypeCapabilityMetadataSqlite("tbds-hive3", "TBDS Hive3", "DATABASE", "TBDS Hive3 数据源");
        normalizeDatasourceTypeCapabilityMetadataSqlite("influxdb", "InfluxDB", "DATABASE", "InfluxDB 数据源");
        normalizeDatasourceTypeCapabilityMetadataSqlite("influxdbv1", "InfluxDB v1", "DATABASE", "InfluxDB v1 数据源");
    }

    private void normalizeDatasourceTypeCapabilityMetadataSqlite(String typeCode,
                                                                 String typeName,
                                                                 String sourceCategory,
                                                                 String description) {
        jdbcTemplate.update("update datasource_type_capability set type_name = ?, source_category = ?, description = ? where tenant_id = 'default' and type_code = ?",
                typeName, sourceCategory, description, typeCode);
    }

    private void ensureColumn(String tableName, String columnName, String ddl) {
        schemaIntrospector.ensureColumn(tableName, columnName, ddl);
    }

    private void ensureIndex(String tableName, String indexName, String ddl) {
        schemaIntrospector.ensureIndex(tableName, indexName, ddl);
    }
}
