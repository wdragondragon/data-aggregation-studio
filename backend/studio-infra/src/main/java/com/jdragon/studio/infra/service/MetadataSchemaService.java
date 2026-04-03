package com.jdragon.studio.infra.service;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.enums.FieldComponentType;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.SchemaStatus;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.MetadataSchemaSaveRequest;
import com.jdragon.studio.core.spi.MetadataSchemaRegistry;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.entity.MetaFieldDefinitionEntity;
import com.jdragon.studio.infra.entity.MetaSchemaEntity;
import com.jdragon.studio.infra.entity.MetaSchemaVersionEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.MetaFieldDefinitionMapper;
import com.jdragon.studio.infra.mapper.MetaSchemaMapper;
import com.jdragon.studio.infra.mapper.MetaSchemaVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class MetadataSchemaService implements MetadataSchemaRegistry {

    private final MetaSchemaMapper schemaMapper;
    private final MetaSchemaVersionMapper versionMapper;
    private final MetaFieldDefinitionMapper fieldDefinitionMapper;
    private final DatasourceMapper datasourceMapper;
    private final DataModelMapper dataModelMapper;
    private final PluginCatalogService pluginCatalogService;

    private static final String META_MODEL_CONFIG_PREFIX = "META_MODEL_CONFIG:";

    public MetadataSchemaService(MetaSchemaMapper schemaMapper,
                                 MetaSchemaVersionMapper versionMapper,
                                 MetaFieldDefinitionMapper fieldDefinitionMapper,
                                 DatasourceMapper datasourceMapper,
                                 DataModelMapper dataModelMapper,
                                 PluginCatalogService pluginCatalogService) {
        this.schemaMapper = schemaMapper;
        this.versionMapper = versionMapper;
        this.fieldDefinitionMapper = fieldDefinitionMapper;
        this.datasourceMapper = datasourceMapper;
        this.dataModelMapper = dataModelMapper;
        this.pluginCatalogService = pluginCatalogService;
    }

    @Override
    public List<MetadataSchemaDefinition> listSchemas() {
        List<MetaSchemaEntity> schemas = schemaMapper.selectList(new LambdaQueryWrapper<MetaSchemaEntity>()
                .orderByAsc(MetaSchemaEntity::getSchemaCode));
        List<MetadataSchemaDefinition> result = new ArrayList<MetadataSchemaDefinition>();
        for (MetaSchemaEntity schema : schemas) {
            result.add(toDefinition(schema));
        }
        return result;
    }

    @Override
    @Transactional
    public MetadataSchemaDefinition saveDraft(MetadataSchemaSaveRequest request) {
        MetaSchemaEntity schema = request.getSchemaId() == null ? new MetaSchemaEntity() : schemaMapper.selectById(request.getSchemaId());
        if (schema == null) {
            schema = new MetaSchemaEntity();
        }
        schema.setSchemaCode(request.getSchemaCode());
        schema.setSchemaName(request.getSchemaName());
        schema.setObjectType(request.getObjectType());
        schema.setTypeCode(request.getTypeCode());
        schema.setDescription(request.getDescription());
        schema.setStatus(SchemaStatus.DRAFT.name());
        if (schema.getId() == null) {
            schemaMapper.insert(schema);
        } else {
            schemaMapper.updateById(schema);
        }

        int nextVersion = nextVersion(schema.getId());
        MetaSchemaVersionEntity version = new MetaSchemaVersionEntity();
        version.setSchemaId(schema.getId());
        version.setVersionNumber(nextVersion);
        version.setStatus(SchemaStatus.DRAFT.name());
        version.setDescription(request.getDescription());
        versionMapper.insert(version);

        for (MetadataFieldDefinition field : request.getFields()) {
            MetaFieldDefinitionEntity entity = new MetaFieldDefinitionEntity();
            entity.setSchemaVersionId(version.getId());
            entity.setFieldKey(field.getFieldKey());
            entity.setFieldName(field.getFieldName());
            entity.setDescription(field.getDescription());
            entity.setScope(field.getScope() == null ? null : field.getScope().name());
            entity.setValueType(field.getValueType() == null ? null : field.getValueType().name());
            entity.setComponentType(field.getComponentType() == null ? null : field.getComponentType().name());
            entity.setRequiredFlag(Boolean.TRUE.equals(field.getRequired()) ? 1 : 0);
            entity.setSensitiveFlag(Boolean.TRUE.equals(field.getSensitive()) ? 1 : 0);
            entity.setSortOrder(field.getSortOrder());
            entity.setValidationRule(field.getValidationRule());
            entity.setPlaceholder(field.getPlaceholder());
            entity.setDefaultValue(field.getDefaultValue());
            entity.setOptions(field.getOptions());
            fieldDefinitionMapper.insert(entity);
        }

        schema.setCurrentVersionId(version.getId());
        schemaMapper.updateById(schema);
        return toDefinition(schema);
    }

    @Override
    @Transactional
    public MetadataSchemaDefinition publish(Long schemaId) {
        MetaSchemaEntity schema = schemaMapper.selectById(schemaId);
        if (schema == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Schema not found: " + schemaId);
        }
        MetaSchemaVersionEntity version = versionMapper.selectById(schema.getCurrentVersionId());
        if (version == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Current draft version not found");
        }
        version.setStatus(SchemaStatus.PUBLISHED.name());
        versionMapper.updateById(version);
        schema.setStatus(SchemaStatus.PUBLISHED.name());
        schemaMapper.updateById(schema);
        return toDefinition(schema);
    }

    @Transactional
    public void delete(Long schemaId) {
        MetaSchemaEntity schema = schemaMapper.selectById(schemaId);
        if (schema == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Schema not found: " + schemaId);
        }
        List<MetaSchemaVersionEntity> versions = versionMapper.selectList(new LambdaQueryWrapper<MetaSchemaVersionEntity>()
                .eq(MetaSchemaVersionEntity::getSchemaId, schemaId));
        List<Long> versionIds = new ArrayList<Long>();
        for (MetaSchemaVersionEntity version : versions) {
            versionIds.add(version.getId());
        }
        if (!versionIds.isEmpty()) {
            Long datasourceReferences = datasourceMapper.selectCount(new LambdaQueryWrapper<DatasourceEntity>()
                    .in(DatasourceEntity::getSchemaVersionId, versionIds));
            Long modelReferences = dataModelMapper.selectCount(new LambdaQueryWrapper<DataModelEntity>()
                    .in(DataModelEntity::getSchemaVersionId, versionIds));
            if ((datasourceReferences != null && datasourceReferences > 0)
                    || (modelReferences != null && modelReferences > 0)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Schema is referenced by datasource or model instances");
            }
            fieldDefinitionMapper.delete(new LambdaQueryWrapper<MetaFieldDefinitionEntity>()
                    .in(MetaFieldDefinitionEntity::getSchemaVersionId, versionIds));
        }
        versionMapper.delete(new LambdaQueryWrapper<MetaSchemaVersionEntity>()
                .eq(MetaSchemaVersionEntity::getSchemaId, schemaId));
        schemaMapper.deleteById(schemaId);
    }

    @Transactional
    public List<MetadataSchemaDefinition> syncAllTechnicalMetaModels() {
        List<MetadataSchemaDefinition> result = new ArrayList<MetadataSchemaDefinition>();
        for (String typeCode : pluginCatalogService.sourceTypes()) {
            result.addAll(syncTechnicalMetaModels(typeCode));
        }
        return result;
    }

    @Transactional
    public List<MetadataSchemaDefinition> syncTechnicalMetaModels(String typeCode) {
        if (typeCode == null || typeCode.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource type is required");
        }
        List<MetadataSchemaDefinition> synced = new ArrayList<MetadataSchemaDefinition>();
        synced.add(ensureTechnicalMetaModel(typeCode, "source"));
        synced.add(ensureTechnicalMetaModel(typeCode, "table"));
        synced.add(ensureTechnicalMetaModel(typeCode, "field"));
        return synced;
    }

    public MetadataSchemaDefinition findSchemaByVersionId(Long schemaVersionId) {
        if (schemaVersionId == null) {
            return null;
        }
        for (MetadataSchemaDefinition schema : listSchemas()) {
            if (schemaVersionId.equals(schema.getCurrentVersionId()) || schemaVersionId.equals(schema.getId())) {
                return schema;
            }
        }
        return null;
    }

    public MetadataSchemaDefinition findTechnicalMetaModel(String datasourceType, String metaModelCode) {
        if (datasourceType == null || datasourceType.trim().isEmpty()
                || metaModelCode == null || metaModelCode.trim().isEmpty()) {
            return null;
        }
        String expectedSchemaCode = "technical:" + datasourceType.trim() + ":" + metaModelCode.trim();
        for (MetadataSchemaDefinition schema : listSchemas()) {
            JSONObject config = extractMetaModelConfig(schema);
            if (config == null) {
                continue;
            }
            if (!"TECHNICAL".equalsIgnoreCase(config.getString("domain"))) {
                continue;
            }
            if (!normalize(datasourceType).equals(normalize(config.getString("datasourceType")))) {
                continue;
            }
            if (!normalize(metaModelCode).equals(normalize(config.getString("metaModelCode")))) {
                continue;
            }
            if (expectedSchemaCode.equalsIgnoreCase(schema.getSchemaCode())) {
                return schema;
            }
        }
        return null;
    }

    public MetadataSchemaDefinition findCurrentSchema(String objectType, String typeCode) {
        if (objectType == null || typeCode == null) {
            return null;
        }
        for (MetadataSchemaDefinition schema : listSchemas()) {
            if (!objectType.equalsIgnoreCase(schema.getObjectType())) {
                continue;
            }
            if (typeCode.equalsIgnoreCase(schema.getTypeCode())) {
                return schema;
            }
        }
        return null;
    }

    private int nextVersion(Long schemaId) {
        List<MetaSchemaVersionEntity> versions = versionMapper.selectList(new LambdaQueryWrapper<MetaSchemaVersionEntity>()
                .eq(MetaSchemaVersionEntity::getSchemaId, schemaId));
        int max = 0;
        for (MetaSchemaVersionEntity version : versions) {
            if (version.getVersionNumber() != null && version.getVersionNumber() > max) {
                max = version.getVersionNumber();
            }
        }
        return max + 1;
    }

    private MetadataSchemaDefinition ensureTechnicalMetaModel(String datasourceType, String metaModelCode) {
        MetadataSchemaDefinition existing = findTechnicalMetaModel(datasourceType, metaModelCode);
        if (existing != null) {
            return existing;
        }
        return saveDraft(buildTechnicalMetaModelDraft(datasourceType, metaModelCode));
    }

    private MetadataSchemaSaveRequest buildTechnicalMetaModelDraft(String datasourceType, String metaModelCode) {
        String normalizedType = datasourceType.trim();
        MetadataSchemaDefinition existing = findExistingTechnicalSchema(normalizedType, metaModelCode);
        MetadataSchemaSaveRequest request = new MetadataSchemaSaveRequest();
        request.setSchemaId(existing == null ? null : existing.getId());
        request.setSchemaCode("technical:" + normalizedType + ":" + metaModelCode);
        request.setSchemaName(buildTechnicalSchemaName(normalizedType, metaModelCode));
        request.setObjectType("source".equalsIgnoreCase(metaModelCode) ? "datasource" : "model");
        request.setTypeCode("source".equalsIgnoreCase(metaModelCode) ? normalizedType : normalizedType + "." + metaModelCode);
        request.setDescription(encodeMetaModelDescription(baseTechnicalDescription(normalizedType, metaModelCode),
                "TECHNICAL",
                normalizedType,
                null,
                null,
                metaModelCode,
                toMetaModelName(metaModelCode),
                "field".equalsIgnoreCase(metaModelCode) ? "MULTIPLE" : "SINGLE",
                true,
                resolveSyncStrategy(metaModelCode)));
        request.setFields(buildTechnicalFields(normalizedType, metaModelCode));
        return request;
    }

    private MetadataSchemaDefinition findExistingTechnicalSchema(String datasourceType, String metaModelCode) {
        return findTechnicalMetaModel(datasourceType, metaModelCode);
    }

    private String buildTechnicalSchemaName(String datasourceType, String metaModelCode) {
        return datasourceType.toUpperCase() + " " + toMetaModelName(metaModelCode);
    }

    private String toMetaModelName(String metaModelCode) {
        if ("source".equalsIgnoreCase(metaModelCode)) {
            return "数据源信息";
        }
        if ("table".equalsIgnoreCase(metaModelCode)) {
            return "表信息";
        }
        if ("field".equalsIgnoreCase(metaModelCode)) {
            return "字段信息";
        }
        if (metaModelCode == null || metaModelCode.trim().isEmpty()) {
            return "元模型";
        }
        return metaModelCode.endsWith("信息") ? metaModelCode : metaModelCode + "信息";
    }

    private String resolveSyncStrategy(String metaModelCode) {
        if ("source".equalsIgnoreCase(metaModelCode)) {
            return "DATASOURCE_CONNECTION";
        }
        if ("field".equalsIgnoreCase(metaModelCode)) {
            return "COLUMN_DISCOVERY";
        }
        return "OBJECT_DISCOVERY";
    }

    private String baseTechnicalDescription(String datasourceType, String metaModelCode) {
        return "用于采集 " + datasourceType.toUpperCase() + " " + toMetaModelName(metaModelCode) + " 的技术元模型定义。";
    }

    private String encodeMetaModelDescription(String plainDescription,
                                              String domain,
                                              String datasourceType,
                                              String directoryCode,
                                              String directoryName,
                                              String metaModelCode,
                                              String metaModelName,
                                              String displayMode,
                                              boolean required,
                                              String syncStrategy) {
        JSONObject config = new JSONObject(true);
        config.put("domain", domain);
        config.put("datasourceType", datasourceType);
        config.put("directoryCode", directoryCode);
        config.put("directoryName", directoryName);
        config.put("metaModelCode", metaModelCode);
        config.put("metaModelName", metaModelName);
        config.put("displayMode", displayMode);
        config.put("required", required);
        config.put("syncStrategy", syncStrategy);
        String description = plainDescription == null ? "" : plainDescription;
        return META_MODEL_CONFIG_PREFIX + config.toJSONString() + "\n" + description;
    }

    private List<MetadataFieldDefinition> buildTechnicalFields(String datasourceType, String metaModelCode) {
        if ("source".equalsIgnoreCase(metaModelCode)) {
            return buildSourceFields(datasourceType);
        }
        if ("field".equalsIgnoreCase(metaModelCode)) {
            return buildFieldFields(datasourceType);
        }
        return buildTableFields(datasourceType);
    }

    private List<MetadataFieldDefinition> buildSourceFields(String datasourceType) {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        if (isDatabaseType(datasourceType)) {
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
        if (isQueueType(datasourceType)) {
            fields.add(field("brokers", "Broker 地址", FieldValueType.STRING, FieldComponentType.TEXTAREA, true, false, 10, null));
            fields.add(field("topic", "主题", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, null));
            fields.add(field("queue", "队列", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 30, null));
            fields.add(field("consumerGroup", "消费组", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 40, null));
            fields.add(field("username", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, false, true, 60, null));
            fields.add(field("tag", "标签", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 70, null));
            return fields;
        }
        if (isFileType(datasourceType)) {
            fields.add(field("endpoint", "访问地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
            fields.add(field("rootPath", "根路径", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, "/"));
            fields.add(field("pattern", "匹配规则", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 30, ".*"));
            fields.add(field("fileType", "文件类型", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 40, null));
            fields.add(field("encoding", "编码", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            fields.add(field("delimiter", "分隔符", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, null));
            fields.add(field("bucket", "存储桶", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 70, null));
            fields.add(field("accessKey", "访问密钥", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 80, null));
            fields.add(field("secretKey", "密钥", FieldValueType.STRING, FieldComponentType.PASSWORD, false, true, 90, null));
            return fields;
        }
        fields.add(field("endpoint", "访问地址", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
        fields.add(field("username", "用户名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, null));
        fields.add(field("password", "密码", FieldValueType.STRING, FieldComponentType.PASSWORD, false, true, 30, null));
        return fields;
    }

    private List<MetadataFieldDefinition> buildTableFields(String datasourceType) {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
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
            fields.add(field("pattern", "匹配规则", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            fields.add(field("fileName", "文件名", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, null));
            fields.add(field("fileType", "文件类型", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 70, null));
            fields.add(field("encoding", "编码", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 80, null));
            fields.add(field("delimiter", "分隔符", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 90, null));
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
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        fields.add(field("name", "字段名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
        fields.add(field("type", "字段类型", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, null));
        fields.add(field("size", "长度", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 30, null));
        fields.add(field("scale", "精度", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 40, null));
        fields.add(field("nullable", "是否可空", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
        fields.add(field("primaryKey", "是否主键", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, null));
        fields.add(field("autoIncrement", "是否自增", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 70, null));
        fields.add(field("remarks", "备注", FieldValueType.STRING, FieldComponentType.TEXTAREA, false, false, 80, null));
        fields.add(field("defaultValue", "默认值", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 90, null));
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
        return field;
    }

    private boolean isDatabaseType(String typeCode) {
        String normalized = normalize(typeCode);
        return containsAny(normalized, "mysql", "oracle", "postgres", "postgresql", "sqlserver",
                "clickhouse", "kingbase", "dm", "db2", "hive", "gauss", "tidb", "phoenix",
                "greenplum", "starrocks", "doris", "sqlite");
    }

    private boolean isQueueType(String typeCode) {
        return containsAny(normalize(typeCode), "kafka", "rocketmq", "rabbitmq");
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

    private String resolveSchemaDomain(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("domain") != null) {
            return config.getString("domain");
        }
        if ("business".equalsIgnoreCase(schema.getObjectType())
                || (schema.getSchemaCode() != null && schema.getSchemaCode().toLowerCase().startsWith("business:"))) {
            return "BUSINESS";
        }
        return "TECHNICAL";
    }

    private String resolveSchemaDatasourceType(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("datasourceType") != null) {
            return config.getString("datasourceType");
        }
        if (schema.getSchemaCode() != null && schema.getSchemaCode().toLowerCase().startsWith("technical:")) {
            String[] parts = schema.getSchemaCode().split(":");
            if (parts.length > 1) {
                return parts[1];
            }
        }
        if ("datasource".equalsIgnoreCase(schema.getObjectType())) {
            return schema.getTypeCode();
        }
        if ("model".equalsIgnoreCase(schema.getObjectType()) && schema.getTypeCode() != null) {
            String[] parts = schema.getTypeCode().split("\\.");
            if (parts.length > 0) {
                return parts[0];
            }
        }
        return null;
    }

    private String resolveSchemaMetaModelCode(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("metaModelCode") != null) {
            return config.getString("metaModelCode");
        }
        if (schema.getSchemaCode() != null) {
            String[] parts = schema.getSchemaCode().split(":");
            if (parts.length > 2) {
                return parts[2];
            }
        }
        if ("datasource".equalsIgnoreCase(schema.getObjectType())) {
            return "source";
        }
        if ("model".equalsIgnoreCase(schema.getObjectType()) && schema.getTypeCode() != null) {
            String[] parts = schema.getTypeCode().split("\\.");
            if (parts.length > 1 && parts[1] != null && !parts[1].trim().isEmpty()) {
                return parts[1];
            }
            return "table";
        }
        return null;
    }

    private JSONObject extractMetaModelConfig(MetadataSchemaDefinition schema) {
        if (schema == null || schema.getDescription() == null) {
            return null;
        }
        String[] lines = schema.getDescription().split("\\r?\\n", 2);
        if (lines.length == 0 || !lines[0].startsWith(META_MODEL_CONFIG_PREFIX)) {
            return null;
        }
        try {
            return JSONObject.parseObject(lines[0].substring(META_MODEL_CONFIG_PREFIX.length()).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private MetadataSchemaDefinition toDefinition(MetaSchemaEntity schema) {
        MetadataSchemaDefinition definition = new MetadataSchemaDefinition();
        definition.setId(schema.getId());
        definition.setSchemaCode(schema.getSchemaCode());
        definition.setSchemaName(schema.getSchemaName());
        definition.setObjectType(schema.getObjectType());
        definition.setTypeCode(schema.getTypeCode());
        definition.setCurrentVersionId(schema.getCurrentVersionId());
        definition.setStatus(schema.getStatus() == null ? null : SchemaStatus.valueOf(schema.getStatus()));
        definition.setDescription(schema.getDescription());
        MetaSchemaVersionEntity version = schema.getCurrentVersionId() == null ? null : versionMapper.selectById(schema.getCurrentVersionId());
        if (version != null) {
            definition.setVersionNumber(version.getVersionNumber());
            List<MetaFieldDefinitionEntity> fields = fieldDefinitionMapper.selectList(new LambdaQueryWrapper<MetaFieldDefinitionEntity>()
                    .eq(MetaFieldDefinitionEntity::getSchemaVersionId, version.getId())
                    .orderByAsc(MetaFieldDefinitionEntity::getSortOrder));
            List<MetadataFieldDefinition> fieldDefinitions = new ArrayList<MetadataFieldDefinition>();
            for (MetaFieldDefinitionEntity field : fields) {
                MetadataFieldDefinition fieldDefinition = new MetadataFieldDefinition();
                fieldDefinition.setFieldKey(field.getFieldKey());
                fieldDefinition.setFieldName(field.getFieldName());
                fieldDefinition.setDescription(field.getDescription());
                fieldDefinition.setRequired(field.getRequiredFlag() != null && field.getRequiredFlag() == 1);
                fieldDefinition.setSensitive(field.getSensitiveFlag() != null && field.getSensitiveFlag() == 1);
                fieldDefinition.setSortOrder(field.getSortOrder());
                fieldDefinition.setValidationRule(field.getValidationRule());
                fieldDefinition.setPlaceholder(field.getPlaceholder());
                fieldDefinition.setDefaultValue(field.getDefaultValue());
                fieldDefinition.setOptions(field.getOptions());
                if (field.getScope() != null) {
                    fieldDefinition.setScope(com.jdragon.studio.dto.enums.MetadataScope.valueOf(field.getScope()));
                }
                if (field.getValueType() != null) {
                    fieldDefinition.setValueType(com.jdragon.studio.dto.enums.FieldValueType.valueOf(field.getValueType()));
                }
                if (field.getComponentType() != null) {
                    fieldDefinition.setComponentType(com.jdragon.studio.dto.enums.FieldComponentType.valueOf(field.getComponentType()));
                }
                fieldDefinitions.add(fieldDefinition);
            }
            definition.setFields(fieldDefinitions);
        }
        return definition;
    }
}

