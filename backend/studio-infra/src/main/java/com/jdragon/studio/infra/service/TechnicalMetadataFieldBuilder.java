package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.FieldComponentType;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class TechnicalMetadataFieldBuilder {

    private static final List<String> FILE_TABLE_TYPE_OPTIONS = Arrays.asList("csv", "json", "jsonl", "efile", "excel");

    List<MetadataFieldDefinition> buildTechnicalFields(String datasourceType, String metaModelCode) {
        if ("source".equalsIgnoreCase(metaModelCode)) {
            return buildSourceFields(datasourceType);
        }
        if ("field".equalsIgnoreCase(metaModelCode)) {
            return buildFieldFields(datasourceType);
        }
        return buildTableFields(datasourceType);
    }

    private List<MetadataFieldDefinition> buildSourceFields(String datasourceType) {
        String normalized = normalize(datasourceType);
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        if ("http".equals(normalized)) {
            fields.add(field("url", "URL", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            return fields;
        }
        if ("ftp".equals(normalized)) {
            fields.add(field("host", "主机地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("port", "端口", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 20, "21"));
            fields.add(field("username", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
            fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, true, true, 40, null));
            fields.add(field("ftpTLS", "TLS 模式", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, "none"));
            fields.add(field("connectMode", "连接模式", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, "PASV"));
            fields.add(field("timeout", "超时时间(毫秒)", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 70, "60000"));
            return fields;
        }
        if ("sftp".equals(normalized)) {
            fields.add(field("host", "主机地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("port", "端口", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 20, "22"));
            fields.add(field("username", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
            fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, true, true, 40, null));
            fields.add(field("timeout", "超时时间(毫秒)", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 50, "60000"));
            return fields;
        }
        if ("minio".equals(normalized) || "oss".equals(normalized)) {
            fields.add(field("storageProvider", "存储类型", FieldValueType.STRING, FieldComponentType.SELECT, true, false, 10,
                    "oss", Arrays.asList("oss", "minio")));
            fields.add(field("endpoint", "访问地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 20, null));
            fields.add(field("accessKey", "访问密钥", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
            fields.add(field("secretKey", "密钥", FieldValueType.STRING, FieldComponentType.PASSWORD, true, true, 40, null));
            fields.add(field("bucket", "存储桶", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 50, null));
            return fields;
        }
        if ("tbds-hdfs".equals(normalized) || "tbds-hdfs3".equals(normalized)) {
            fields.add(field("hdfsSiteFilePath", "hdfs-site.xml 路径", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 10, null));
            fields.add(field("coreSiteFilePath", "core-site.xml 路径", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, null));
            fields.add(field("hadoopConfig", "Hadoop 配置", FieldValueType.JSON, FieldComponentType.JSON_EDITOR, false, false, 30, "{}"));
            fields.add(field("kerberosPrincipal", "Kerberos Principal", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 40, null));
            fields.add(field("kerberosKeytabFilePath", "Keytab 路径", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            fields.add(field("krb5Conf", "krb5.conf 路径", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, null));
            appendFileDiscoveryFields(fields, 70);
            return fields;
        }
        if ("kafka".equals(normalized)) {
            fields.add(field("bootstrap.servers", "Bootstrap Servers", FieldValueType.STRING, FieldComponentType.TEXTAREA, true, false, 10, null));
            fields.add(field("topic", "主题", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, null));
            fields.add(field("group.id", "消费组 ID", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 30, null));
            fields.add(field("username", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 40, null));
            fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, false, true, 50, null));
            fields.add(field("kerberos", "启用 Kerberos", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 60, "false"));
            fields.add(field("principal", "Kerberos Principal", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 70, null));
            fields.add(field("kerberosKeytabFilePath", "Keytab 路径", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 80, null));
            fields.add(field("krb5Conf", "krb5.conf 路径", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 90, null));
            fields.add(field("kerberosDomain", "Kerberos 域", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 100, null));
            return fields;
        }
        if ("rabbitmq".equals(normalized)) {
            fields.add(field("host", "主机地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("port", "端口", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 20, "5672"));
            fields.add(field("username", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 30, "guest"));
            fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, false, true, 40, "guest"));
            fields.add(field("queueName", "队列名称", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            return fields;
        }
        if ("rocketmq".equals(normalized)) {
            fields.add(field("namesrvAddr", "NameServer 地址", FieldValueType.STRING, FieldComponentType.TEXTAREA, true, false, 10, null));
            fields.add(field("producerGroup", "生产者组", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 20, null));
            fields.add(field("topic", "主题", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
            fields.add(field("tag", "标签", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 40, null));
            fields.add(field("consumerGroup", "消费组", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            fields.add(field("accessKey", "访问密钥", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, null));
            fields.add(field("secretKey", "密钥", FieldValueType.STRING, FieldComponentType.PASSWORD, false, true, 70, null));
            fields.add(field("pullBatchSize", "拉取批次大小", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 80, "100"));
            fields.add(field("pullInterval", "拉取间隔(毫秒)", FieldValueType.LONG, FieldComponentType.NUMBER, false, false, 90, "-1"));
            return fields;
        }
        if ("influxdb".equals(normalized)) {
            fields.add(field("host", "服务地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("database", "组织名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 20, null));
            fields.add(field("bucket", "存储桶", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
            fields.add(field("password", "访问令牌", FieldValueType.STRING, FieldComponentType.PASSWORD, true, true, 40, null));
            return fields;
        }
        if ("influxdbv1".equals(normalized)) {
            fields.add(field("host", "服务地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("database", "数据库名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 20, null));
            fields.add(field("userName", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 30, null));
            fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, false, true, 40, null));
            return fields;
        }
        if ("odps".equals(normalized)) {
            fields.add(field("host", "MaxCompute Endpoint", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("database", "Project 名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 20, null));
            fields.add(field("userName", "AccessKey ID", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
            fields.add(field("password", "AccessKey Secret", FieldValueType.STRING, FieldComponentType.PASSWORD, true, true, 40, null));
            fields.add(field("extraParams", "全局参数", FieldValueType.JSON, FieldComponentType.JSON_EDITOR, false, false, 50, "{}"));
            return fields;
        }
        if ("tbds-hive3".equals(normalized)) {
            fields.add(field("host", "主机地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("port", "端口", FieldValueType.INTEGER, FieldComponentType.NUMBER, true, false, 20, null));
            fields.add(field("database", "数据库名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 30, null));
            fields.add(field("principal", "Kerberos Principal", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 40, null));
            fields.add(field("keytabPath", "Keytab 路径", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 50, null));
            fields.add(field("krb5File", "krb5.conf 路径", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 60, null));
            fields.add(field("other", "附加连接参数", FieldValueType.JSON, FieldComponentType.JSON_EDITOR, false, false, 70, "{}"));
            fields.add(field("jdbcUrl", "JDBC 地址", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 80, null));
            fields.add(field("driverClassName", "驱动类名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 90, null));
            return fields;
        }
        if (isDatabaseType(normalized)) {
            fields.add(field("host", "主机地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("port", "端口", FieldValueType.INTEGER, FieldComponentType.NUMBER, true, false, 20, "3306"));
            fields.add(field("database", "数据库名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
            fields.add(field("userName", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 40, null));
            fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, true, true, 50, null));
            fields.add(field("jdbcUrl", "JDBC 地址", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, null));
            fields.add(field("driverClassName", "驱动类名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 70, null));
            fields.add(field("usePool", "启用连接池", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 80, "true"));
            return fields;
        }
        if (isQueueType(normalized)) {
            fields.add(field("brokers", "Broker 地址", FieldValueType.STRING, FieldComponentType.TEXTAREA, true, false, 10, null));
            fields.add(field("topic", "主题", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, null));
            fields.add(field("queue", "队列", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 30, null));
            fields.add(field("consumerGroup", "消费组", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 40, null));
            fields.add(field("username", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, false, true, 60, null));
            fields.add(field("tag", "标签", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 70, null));
            return fields;
        }
        if (isFtpType(normalized)) {
            fields.add(field("host", "主机地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("port", "端口", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 20, "21"));
            fields.add(field("username", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
            fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, true, true, 40, null));
            fields.add(field("ftpTLS", "TLS 模式", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 50,
                    "none", Arrays.asList("none", "implicit", "explicit")));
            fields.add(field("connectMode", "连接模式", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 60,
                    "PASV", Arrays.asList("PASV", "PORT")));
            fields.add(field("timeout", "超时时间(毫秒)", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 70, "60000"));
            return fields;
        }
        if (isSftpType(normalized)) {
            fields.add(field("host", "主机地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("port", "端口", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 20, "22"));
            fields.add(field("username", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
            fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, true, true, 40, null));
            fields.add(field("timeout", "超时时间(毫秒)", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 50, "60000"));
            return fields;
        }
        if (isMinioType(normalized)) {
            fields.add(field("storageProvider", "存储类型", FieldValueType.STRING, FieldComponentType.SELECT, true, false, 10,
                    "oss", Arrays.asList("oss", "minio")));
            fields.add(field("endpoint", "访问地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 20, null));
            fields.add(field("accessKey", "访问密钥", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
            fields.add(field("secretKey", "密钥", FieldValueType.STRING, FieldComponentType.PASSWORD, true, true, 40, null));
            fields.add(field("bucket", "存储桶", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 50, null));
            return fields;
        }
        fields.add(field("endpoint", "访问地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
        fields.add(field("username", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, null));
        fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, false, true, 30, null));
        return fields;
    }

    private void appendFileDiscoveryFields(List<MetadataFieldDefinition> fields, int startOrder) {
        fields.add(field("rootPath", "根路径", FieldValueType.STRING, FieldComponentType.INPUT, false, false, startOrder, "/"));
        fields.add(field("partitionType", "分区匹配类型", FieldValueType.STRING, FieldComponentType.SELECT, false, false, startOrder + 10,
                "glob", Arrays.asList("glob", "regex")));
        fields.add(field("partition", "分区匹配规则", FieldValueType.STRING, FieldComponentType.INPUT, false, false, startOrder + 20, "*"));
        fields.add(field("fileType", "文件类型", FieldValueType.STRING, FieldComponentType.SELECT, false, false, startOrder + 30,
                "csv", FILE_TABLE_TYPE_OPTIONS));
        fields.add(field("encoding", "编码", FieldValueType.STRING, FieldComponentType.INPUT, false, false, startOrder + 40, "UTF-8"));
        fields.add(field("delimiter", "分隔符", FieldValueType.STRING, FieldComponentType.INPUT, false, false, startOrder + 50, ","));
    }

    private List<MetadataFieldDefinition> buildTableFields(String datasourceType) {
        String normalized = normalize(datasourceType);
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        if ("http".equals(normalized)) {
            fields.add(field("physicalName", "请求路径", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("description", "描述", FieldValueType.STRING, FieldComponentType.TEXTAREA, false, false, 20, null));
            fields.add(field("protocolMode", "协议模式", FieldValueType.STRING, FieldComponentType.SELECT, true, false, 30,
                    "REST_JSON", Arrays.asList("REST_JSON", "REST_XML", "SOAP")));
            fields.add(field("mode", "请求类型", FieldValueType.STRING, FieldComponentType.SELECT, true, false, 40,
                    "GET", Arrays.asList("GET", "POST")));
            fields.add(field("resultType", "返回数据类型", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 50,
                    "json", Arrays.asList("json", "xml", "soap")));
            fields.add(field("soapVersion", "SOAP 版本", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 60,
                    "SOAP_11", Arrays.asList("SOAP_11", "SOAP_12")));
            fields.add(field("namespaceUri", "Namespace URI", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 70, null));
            fields.add(field("operationName", "Operation", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 80, null));
            fields.add(field("soapAction", "SOAPAction", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 90, null));
            fields.add(field("requestRootName", "请求根节点", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 100, null));
            fields.add(field("responseRootName", "响应根节点", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 110, null));
            fields.add(field("wsdlUrl", "WSDL 地址", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 120, null));
            fields.add(field("businessStatusPath", "业务状态节点", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 130, null));
            fields.add(field("businessStatusCode", "业务状态码", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 140, null));
            fields.add(field("totalCodePath", "总量节点", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 150, null));
            return fields;
        }
        fields.add(field("sourceType", "数据源类型", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 10, datasourceType));
        fields.add(field("discoveryMode", "发现方式", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, "AUTO"));
        fields.add(field("physicalName", "物理名称", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
        if (isDatabaseType(datasourceType)) {
            fields.add(field("catalog", "目录名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 40, null));
            fields.add(field("schema", "Schema 名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            fields.add(field("tableType", "表类型", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, "TABLE"));
            fields.add(field("remarks", "备注", FieldValueType.STRING, FieldComponentType.TEXTAREA, false, false, 70, null));
            fields.add(field("partitioned", "是否分区", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 80, "false"));
            fields.add(field("externalTable", "是否外部表", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 90, "false"));
            fields.add(field("columnCount", "字段数", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 100, null));
            fields.add(field("columns", "字段列表", FieldValueType.JSON, FieldComponentType.JSON_EDITOR, false, false, 110, "[]"));
            return fields;
        }
        if (isFileType(datasourceType)) {
            fields.add(field("rootPath", "根路径", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 40, null));
            fields.add(field("fileName", "文件名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            fields.add(field("partitionType", "分区匹配类型", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 60,
                    "glob", Arrays.asList("glob", "regex")));
            fields.add(field("partition", "分区匹配规则", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 70, null));
            fields.add(field("fileType", "文件类型", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 80,
                    "csv", FILE_TABLE_TYPE_OPTIONS));
            fields.add(field("encoding", "编码", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 90, "UTF-8"));
            fields.add(field("delimiter", "分隔符", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 100, ","));
            fields.add(field("efile.entity", "EFILE 实体标识", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 110, null));
            fields.add(field("efile.type", "EFILE 类型标识", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 120, null));
            fields.add(field("efile.dataTime", "EFILE 数据时间", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 130, null));
            fields.add(field("efile.tableName", "EFILE 表名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 140, null));
            fields.add(field("efile.tableCode", "EFILE 表编码", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 150, null));
            fields.add(field("efile.planDate", "EFILE 计划日期", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 160, null));
            return fields;
        }
        if (isQueueType(datasourceType)) {
            fields.add(field("queueName", "队列名称", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 40, null));
            fields.add(field("topic", "主题", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            fields.add(field("queue", "队列", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, null));
            fields.add(field("brokers", "Broker 地址", FieldValueType.STRING, FieldComponentType.TEXTAREA, false, false, 70, null));
            fields.add(field("consumerGroup", "消费组", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 80, null));
            fields.add(field("tag", "标签", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 90, null));
            return fields;
        }
        return fields;
    }

    private List<MetadataFieldDefinition> buildFieldFields(String datasourceType) {
        String normalized = normalize(datasourceType);
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        if ("http".equals(normalized)) {
            fields.add(field("name", "字段名称", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("cnName", "字段中文名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, null));
            fields.add(field("parentNode", "父节点名称", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 30, null));
            fields.add(field("remarks", "字段备注", FieldValueType.STRING, FieldComponentType.TEXTAREA, false, false, 40, null));
            fields.add(field("primaryKey", "是否主键", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 50, "false"));
            fields.add(field("nullable", "能否为空", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 60, "true"));
            fields.add(field("type", "类型", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 70,
                    null, Arrays.asList("STRING", "TEXT", "LONG", "INT", "INTEGER", "NUMBER", "DECIMAL", "DOUBLE", "FLOAT", "BOOLEAN", "DATE", "DATETIME", "TIMESTAMP")));
            fields.add(field("size", "长度", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 80, null));
            fields.add(field("scale", "精度", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 90, null));
            return fields;
        }
        fields.add(field("name", "字段名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
        if (isFileType(datasourceType)) {
            fields.add(field("type", "字段类型", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 20,
                    "STRING", Arrays.asList("STRING", "TEXT", "LONG", "INT", "INTEGER", "NUMBER", "DECIMAL", "DOUBLE", "FLOAT", "BOOLEAN", "DATE", "DATETIME", "TIMESTAMP")));
            fields.add(field("index", "字段下标", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 25, null));
            fields.add(field("sourceKind", "字段来源", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 26,
                    "DATA", Arrays.asList("DATA", "TAG")));
        } else {
            fields.add(field("type", "字段类型", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, null));
        }
        fields.add(field("size", "长度", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 30, null));
        fields.add(field("scale", "精度", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 40, null));
        if (isFileType(datasourceType)) {
            fields.add(field("nullable", "是否可空", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 50, "true"));
            fields.add(field("primaryKey", "是否主键", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 60, "false"));
            fields.add(field("autoIncrement", "是否自增", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 70, "false"));
        } else {
            fields.add(field("nullable", "是否可空", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            fields.add(field("primaryKey", "是否主键", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, null));
            fields.add(field("autoIncrement", "是否自增", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 70, null));
        }
        fields.add(field("remarks", "备注", FieldValueType.STRING, FieldComponentType.TEXTAREA, false, false, 80, null));
        fields.add(field("defaultValue", "默认值", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 90, null));
        if ("odps".equals(normalized)) {
            fields.add(field("partitionColumn", "是否分区字段", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 100, "false"));
        }
        if (!isDatabaseType(datasourceType)) {
            fields.add(field("sourceType", "数据源类型", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 100, datasourceType));
        }
        return fields;
    }

    private MetadataFieldDefinition field(String fieldKey,
                                          String fieldName,
                                          FieldValueType valueType,
                                          FieldComponentType componentType,
                                          boolean required,
                                          boolean sensitive,
                                          int sortOrder,
                                          String defaultValue) {
        return field(fieldKey, fieldName, valueType, componentType, required, sensitive, sortOrder, defaultValue,
                new ArrayList<String>());
    }

    private MetadataFieldDefinition field(String fieldKey,
                                          String fieldName,
                                          FieldValueType valueType,
                                          FieldComponentType componentType,
                                          boolean required,
                                          boolean sensitive,
                                          int sortOrder,
                                          String defaultValue,
                                          List<String> options) {
        MetadataFieldDefinition field = new MetadataFieldDefinition();
        field.setFieldKey(fieldKey);
        field.setFieldName(fieldName);
        field.setScope(MetadataScope.TECHNICAL);
        field.setValueType(valueType);
        field.setComponentType(componentType);
        field.setRequired(required);
        field.setSensitive(sensitive);
        field.setSortOrder(sortOrder);
        field.setDescription(fieldName);
        field.setDefaultValue(defaultValue);
        field.setOptions(options == null ? new ArrayList<String>() : new ArrayList<String>(options));
        applyQueryCapabilities(field);
        return field;
    }

    private void applyQueryCapabilities(MetadataFieldDefinition field) {
        if (field == null) {
            return;
        }
        if (Boolean.TRUE.equals(field.getSensitive())) {
            field.setSearchable(false);
            field.setSortable(false);
            field.setQueryOperators(new ArrayList<String>());
            field.setQueryDefaultOperator(null);
            return;
        }
        List<String> operators = defaultQueryOperators(field.getValueType());
        field.setSearchable(!operators.isEmpty());
        field.setSortable(isSortableValueType(field.getValueType()));
        field.setQueryOperators(operators);
        field.setQueryDefaultOperator(operators.isEmpty() ? null : defaultQueryOperator(field.getValueType()));
    }

    private List<String> defaultQueryOperators(FieldValueType valueType) {
        List<String> operators = new ArrayList<String>();
        if (valueType == null) {
            return operators;
        }
        switch (valueType) {
            case STRING:
                operators.add("EQ");
                operators.add("LIKE");
                operators.add("IN");
                return operators;
            case BOOLEAN:
                operators.add("EQ");
                return operators;
            case INTEGER:
            case LONG:
            case DECIMAL:
                operators.add("EQ");
                operators.add("GT");
                operators.add("GE");
                operators.add("LT");
                operators.add("LE");
                operators.add("BETWEEN");
                operators.add("IN");
                return operators;
            default:
                return operators;
        }
    }

    private String defaultQueryOperator(FieldValueType valueType) {
        if (valueType == null) {
            return null;
        }
        if (FieldValueType.STRING == valueType) {
            return "LIKE";
        }
        return "EQ";
    }

    private boolean isSortableValueType(FieldValueType valueType) {
        return FieldValueType.STRING == valueType
                || FieldValueType.BOOLEAN == valueType
                || FieldValueType.INTEGER == valueType
                || FieldValueType.LONG == valueType
                || FieldValueType.DECIMAL == valueType;
    }

    private boolean isDatabaseType(String typeCode) {
        String normalized = normalize(typeCode);
        return containsAny(normalized, "mysql", "oracle", "postgres", "postgresql", "sqlserver",
                "clickhouse", "kingbase", "dm", "db2", "hive", "gauss", "tidb", "phoenix",
                "greenplum", "starrocks", "doris", "sqlite", "odps");
    }

    private boolean isQueueType(String typeCode) {
        return containsAny(normalize(typeCode), "kafka", "rocketmq", "rabbitmq");
    }

    private boolean isFtpType(String typeCode) {
        return "ftp".equals(normalize(typeCode));
    }

    private boolean isSftpType(String typeCode) {
        return "sftp".equals(normalize(typeCode));
    }

    private boolean isMinioType(String typeCode) {
        return "minio".equals(normalize(typeCode));
    }

    private boolean isFileType(String typeCode) {
        return containsAny(normalize(typeCode), "ftp", "sftp", "minio", "oss", "file");
    }

    private boolean containsAny(String source, String... candidates) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        for (String candidate : candidates) {
            if (source.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
