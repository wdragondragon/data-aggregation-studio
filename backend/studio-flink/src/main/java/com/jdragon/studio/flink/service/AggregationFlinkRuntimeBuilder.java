package com.jdragon.studio.flink.service;

import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.flink.connector.AggregationFlinkTableRuntime;
import com.jdragon.studio.infra.service.EncryptionService;
import org.apache.flink.table.types.DataType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
class AggregationFlinkRuntimeBuilder {
    private static final Set<String> RESERVED_KEYS = new LinkedHashSet<String>();

    static {
        RESERVED_KEYS.add("name");
        RESERVED_KEYS.add("type");
        RESERVED_KEYS.add("host");
        RESERVED_KEYS.add("port");
        RESERVED_KEYS.add("database");
        RESERVED_KEYS.add("userName");
        RESERVED_KEYS.add("password");
        RESERVED_KEYS.add("other");
        RESERVED_KEYS.add("usePool");
        RESERVED_KEYS.add("bucket");
        RESERVED_KEYS.add("principal");
        RESERVED_KEYS.add("keytabPath");
        RESERVED_KEYS.add("krb5File");
        RESERVED_KEYS.add("jdbcUrl");
        RESERVED_KEYS.add("driverClassName");
        RESERVED_KEYS.add("extraParams");
    }

    private final EncryptionService encryptionService;

    AggregationFlinkRuntimeBuilder(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    AggregationFlinkTableRuntime build(DataSourceDefinition datasource,
                                       DataModelDefinition model,
                                       Integer scanMaxRows) {
        Map<String, Object> datasourceMetadata = normalizePluginMetadata(datasource.getTypeCode(),
                decryptMetadata(datasource.getTechnicalMetadata()));
        Map<String, Object> modelMetadata = model.getTechnicalMetadata() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(model.getTechnicalMetadata());
        DataType rowType = AggregationFlinkDataTypeMapper.rowType(modelMetadata, datasource.getTypeCode());

        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setDatasourceId(datasource.getId());
        runtime.setModelId(model.getId());
        runtime.setPluginName(datasource.getTypeCode());
        runtime.setTableName(model.getName());
        runtime.setPhysicalLocator(resolvePhysicalLocator(model));
        runtime.setScanSql(firstText(modelMetadata.get("scanSql"), modelMetadata.get("querySql")));
        runtime.setScanMode(isQueue(datasource.getTypeCode()) ? "unbounded" : "bounded");
        runtime.setMaxRows(scanMaxRows);
        runtime.setProducedDataType(rowType);
        runtime.setFieldNames(DataType.getFieldNames(rowType));
        runtime.setModelMetadata(modelMetadata);
        runtime.setConnectionConfig(Configuration.from(datasourceMetadata));
        runtime.setExtConfig(Configuration.from(modelMetadata));
        runtime.setDataSourceDTO(toBaseDataSource(datasource, datasourceMetadata));
        return runtime;
    }

    private BaseDataSourceDTO toBaseDataSource(DataSourceDefinition definition, Map<String, Object> metadata) {
        BaseDataSourceDTO dto = new BaseDataSourceDTO();
        dto.setName(definition.getName());
        dto.setType(definition.getTypeCode());
        dto.setHost(firstText(metadata.get("host"), metadata.get("endpoint")));
        dto.setPort(asString(metadata.get("port")));
        dto.setDatabase(firstText(metadata.get("database"), firstText(metadata.get("projectName"), metadata.get("org"))));
        dto.setUserName(firstText(metadata.get("userName"), firstText(metadata.get("username"),
                firstText(metadata.get("accessId"), metadata.get("aliyunAccessId")))));
        dto.setPassword(firstText(metadata.get("password"), firstText(metadata.get("token"),
                firstText(metadata.get("accessKeySecret"), metadata.get("aliyunAccessKey")))));
        dto.setOther(asJsonString(metadata.get("other")));
        dto.setUsePool(Boolean.parseBoolean(String.valueOf(metadata.getOrDefault("usePool", Boolean.TRUE))));
        dto.setBucket(firstText(metadata.get("bucket"), metadata.get("bucketName")));
        dto.setPrincipal(firstText(metadata.get("principal"), metadata.get("kerberosPrincipal")));
        dto.setKeytabPath(firstText(metadata.get("keytabPath"), metadata.get("kerberosKeytabFilePath")));
        dto.setKrb5File(firstText(metadata.get("krb5File"), metadata.get("krb5Conf")));
        dto.setJdbcUrl(asString(metadata.get("jdbcUrl")));
        dto.setDriverClassName(asString(metadata.get("driverClassName")));
        Map<String, String> extraParams = new LinkedHashMap<String, String>();
        Object configuredExtraParams = metadata.get("extraParams");
        if (configuredExtraParams instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) configuredExtraParams).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    extraParams.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (!RESERVED_KEYS.contains(entry.getKey()) && entry.getValue() != null) {
                extraParams.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        dto.setExtraParams(extraParams);
        return dto;
    }

    private Map<String, Object> decryptMetadata(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (metadata == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && isSensitive(entry.getKey()) && ((String) value).startsWith("ENC(")
                    && ((String) value).endsWith(")")) {
                result.put(entry.getKey(), encryptionService.decrypt(((String) value).substring(4, ((String) value).length() - 1)));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private Map<String, Object> normalizePluginMetadata(String typeCode, Map<String, Object> metadata) {
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        if (metadata != null) {
            normalized.putAll(metadata);
        }
        String type = typeCode == null ? "" : typeCode.trim().toLowerCase(Locale.ENGLISH);
        if ("ftp".equals(type) || "sftp".equals(type)) {
            copyIfMissing(normalized, "host", "endpoint");
            copyIfMissing(normalized, "username", "userName");
        } else if ("kafka".equals(type)) {
            copyIfMissing(normalized, "bootstrap.servers", "brokers");
            copyIfMissing(normalized, "group.id", "consumerGroup");
            copyIfMissing(normalized, "username", "userName");
        } else if ("rabbitmq".equals(type)) {
            copyIfMissing(normalized, "username", "userName");
            copyIfMissing(normalized, "queueName", "queue");
        } else if ("rocketmq".equals(type)) {
            copyIfMissing(normalized, "namesrvAddr", "brokers");
            copyIfMissing(normalized, "topic", "queue");
        } else if ("influxdb".equals(type) || "influxdbv1".equals(type) || "odps".equals(type)) {
            copyIfMissing(normalized, "host", "endpoint");
        } else if ("tbds-hive3".equals(type)) {
            copyIfMissing(normalized, "principal", "kerberosPrincipal");
            copyIfMissing(normalized, "keytabPath", "kerberosKeytabFilePath");
            copyIfMissing(normalized, "krb5File", "krb5Conf");
        }
        return normalized;
    }

    private String resolvePhysicalLocator(DataModelDefinition model) {
        if (model.getPhysicalLocator() != null && !model.getPhysicalLocator().trim().isEmpty()) {
            return model.getPhysicalLocator().trim();
        }
        Object physicalName = model.getTechnicalMetadata() == null ? null : model.getTechnicalMetadata().get("physicalName");
        if (physicalName != null && !String.valueOf(physicalName).trim().isEmpty()) {
            return String.valueOf(physicalName).trim();
        }
        return model.getName();
    }

    private void copyIfMissing(Map<String, Object> target, String targetKey, String sourceKey) {
        if (!hasText(target.get(targetKey)) && hasText(target.get(sourceKey))) {
            target.put(targetKey, target.get(sourceKey));
        }
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ENGLISH);
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("accesskey");
    }

    private boolean isQueue(String typeCode) {
        String type = typeCode == null ? "" : typeCode.trim().toLowerCase(Locale.ENGLISH);
        return "kafka".equals(type) || "rocketmq".equals(type) || "rabbitmq".equals(type);
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private String firstText(Object first, Object second) {
        return hasText(first) ? String.valueOf(first).trim() : asString(second);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String asJsonString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        return String.valueOf(value);
    }
}
