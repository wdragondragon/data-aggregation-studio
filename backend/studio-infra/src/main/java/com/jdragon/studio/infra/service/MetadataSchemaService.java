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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MetadataSchemaService implements MetadataSchemaRegistry {

    private final MetaSchemaMapper schemaMapper;
    private final MetaSchemaVersionMapper versionMapper;
    private final MetaFieldDefinitionMapper fieldDefinitionMapper;
    private final DatasourceMapper datasourceMapper;
    private final DataModelMapper dataModelMapper;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final DataModelScopedIndexRefreshService dataModelScopedIndexRefreshService;

    private static final List<String> FILE_TABLE_TYPE_OPTIONS = Arrays.asList("csv", "json", "jsonl", "efile", "excel");

    public MetadataSchemaService(MetaSchemaMapper schemaMapper,
                                 MetaSchemaVersionMapper versionMapper,
                                 MetaFieldDefinitionMapper fieldDefinitionMapper,
                                 DatasourceMapper datasourceMapper,
                                 DataModelMapper dataModelMapper,
                                 DatasourceTypeCapabilityService datasourceTypeCapabilityService,
                                 DataModelScopedIndexRefreshService dataModelScopedIndexRefreshService) {
        this.schemaMapper = schemaMapper;
        this.versionMapper = versionMapper;
        this.fieldDefinitionMapper = fieldDefinitionMapper;
        this.datasourceMapper = datasourceMapper;
        this.dataModelMapper = dataModelMapper;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.dataModelScopedIndexRefreshService = dataModelScopedIndexRefreshService;
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
        MetadataSchemaDefinition previousDefinition = schema == null || schema.getId() == null ? null : toDefinition(schema);
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
            entity.setSearchableFlag(Boolean.TRUE.equals(field.getSearchable()) ? 1 : 0);
            entity.setSortableFlag(Boolean.TRUE.equals(field.getSortable()) ? 1 : 0);
            entity.setQueryOperators(field.getQueryOperators() == null ? new ArrayList<String>() : field.getQueryOperators());
            entity.setQueryDefaultOperator(field.getQueryDefaultOperator());
            entity.setOptions(field.getOptions() == null ? new ArrayList<String>() : field.getOptions());
            fieldDefinitionMapper.insert(entity);
        }

        schema.setCurrentVersionId(version.getId());
        schemaMapper.updateById(schema);
        MetadataSchemaDefinition currentDefinition = toDefinition(schema);
        dataModelScopedIndexRefreshService.scheduleScopedRebuild(previousDefinition, currentDefinition);
        return currentDefinition;
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
        for (String typeCode : datasourceTypeCapabilityService.sourceTypes()) {
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
        MetaSchemaVersionEntity version = versionMapper.selectById(schemaVersionId);
        if (version != null && version.getSchemaId() != null) {
            MetaSchemaEntity schema = schemaMapper.selectById(version.getSchemaId());
            if (schema != null) {
                return toDefinition(schema);
            }
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

    public MetadataSchemaDefinition findRuntimeOptionSchema(String role, String pluginType) {
        String normalizedRole = normalize(role);
        String normalizedPluginType = normalize(pluginType);
        if (normalizedRole.isEmpty() || normalizedPluginType.isEmpty()) {
            return null;
        }
        String expectedTypeCode = normalizedRole + ":" + normalizedPluginType;
        String expectedSchemaCode = "runtime:" + normalizedRole + ":" + normalizedPluginType;
        for (MetadataSchemaDefinition schema : listSchemas()) {
            if (schema == null) {
                continue;
            }
            String schemaCode = normalize(schema.getSchemaCode());
            String objectType = normalize(schema.getObjectType());
            String typeCode = normalize(schema.getTypeCode());
            if (expectedSchemaCode.equals(schemaCode)) {
                return schema;
            }
            if ("collection-runtime-option".equals(objectType) && expectedTypeCode.equals(typeCode)) {
                return schema;
            }
            JSONObject config = extractMetaModelConfig(schema);
            if (config == null || !"RUNTIME".equalsIgnoreCase(config.getString("domain"))) {
                continue;
            }
            if (normalizedRole.equals(normalize(config.getString("role")))
                    && normalizedPluginType.equals(normalize(config.getString("pluginType")))) {
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
        if (existing != null && !needsTechnicalMetaModelRefresh(existing, datasourceType, metaModelCode)) {
            return existing;
        }
        return saveDraft(buildTechnicalMetaModelDraft(datasourceType, metaModelCode));
    }

    private boolean needsTechnicalMetaModelRefresh(MetadataSchemaDefinition existing,
                                                   String datasourceType,
                                                   String metaModelCode) {
        if (existing == null || existing.getFields() == null || existing.getFields().isEmpty()) {
            return true;
        }
        List<MetadataFieldDefinition> expectedFields = buildTechnicalFields(datasourceType, metaModelCode);
        if (existing.getFields().size() != expectedFields.size()) {
            return true;
        }
        for (int index = 0; index < expectedFields.size(); index++) {
            if (!sameFieldDefinition(existing.getFields().get(index), expectedFields.get(index))) {
                return true;
            }
        }
        return false;
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
        return MetaModelConfigDescriptions.encode(config, plainDescription);
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
            fields.add(field("mode", "请求类型", FieldValueType.STRING, FieldComponentType.SELECT, true, false, 30,
                    "GET", Arrays.asList("GET", "POST")));
            fields.add(field("resultType", "返回数据类型", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 40,
                    "json", Arrays.asList("json", "xml", "soap")));
            fields.add(field("businessStatusPath", "业务状态节点", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, null));
            fields.add(field("businessStatusCode", "业务状态码", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, null));
            fields.add(field("totalCodePath", "总量节点", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 70, null));
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
                    null, Arrays.asList("STRING", "TEXT", "LONG", "INT", "INTEGER", "NUMBER", "DOUBLE", "FLOAT", "BOOLEAN", "DATE", "DATETIME", "TIMESTAMP")));
            fields.add(field("size", "长度", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 80, null));
            fields.add(field("scale", "精度", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 90, null));
            return fields;
        }
        fields.add(field("name", "字段名", FieldValueType.STRING, FieldComponentType.INPUT, true, false, 10, null));
        if (isFileType(datasourceType)) {
            fields.add(field("type", "字段类型", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 20,
                    "STRING", Arrays.asList("STRING", "TEXT", "LONG", "INT", "INTEGER", "NUMBER", "DOUBLE", "FLOAT", "BOOLEAN", "DATE", "DATETIME", "TIMESTAMP")));
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

    private boolean sameFieldDefinition(MetadataFieldDefinition actual, MetadataFieldDefinition expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        return Objects.equals(actual.getFieldKey(), expected.getFieldKey())
                && Objects.equals(actual.getFieldName(), expected.getFieldName())
                && Objects.equals(actual.getDescription(), expected.getDescription())
                && Objects.equals(actual.getScope(), expected.getScope())
                && Objects.equals(actual.getValueType(), expected.getValueType())
                && Objects.equals(actual.getComponentType(), expected.getComponentType())
                && Objects.equals(actual.getRequired(), expected.getRequired())
                && Objects.equals(actual.getSensitive(), expected.getSensitive())
                && Objects.equals(actual.getSortOrder(), expected.getSortOrder())
                && Objects.equals(actual.getValidationRule(), expected.getValidationRule())
                && Objects.equals(actual.getPlaceholder(), expected.getPlaceholder())
                && Objects.equals(actual.getDefaultValue(), expected.getDefaultValue())
                && Objects.equals(actual.getSearchable(), expected.getSearchable())
                && Objects.equals(actual.getSortable(), expected.getSortable())
                && sameList(actual.getQueryOperators(), expected.getQueryOperators())
                && Objects.equals(actual.getQueryDefaultOperator(), expected.getQueryDefaultOperator())
                && sameList(actual.getOptions(), expected.getOptions());
    }

    private boolean sameList(List<String> left, List<String> right) {
        if (left == null || left.isEmpty()) {
            return right == null || right.isEmpty();
        }
        if (right == null || right.isEmpty()) {
            return false;
        }
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!Objects.equals(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
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

    public String getSchemaDomain(MetadataSchemaDefinition schema) {
        return resolveSchemaDomain(schema);
    }

    public String getSchemaDatasourceType(MetadataSchemaDefinition schema) {
        return resolveSchemaDatasourceType(schema);
    }

    public String getSchemaMetaModelCode(MetadataSchemaDefinition schema) {
        return resolveSchemaMetaModelCode(schema);
    }

    public String getSchemaDirectoryCode(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("directoryCode") != null) {
            return config.getString("directoryCode");
        }
        if (schema != null && schema.getSchemaCode() != null && schema.getSchemaCode().toLowerCase().startsWith("business:")) {
            String[] parts = schema.getSchemaCode().split(":");
            if (parts.length > 1) {
                return parts[1];
            }
        }
        return null;
    }

    public String getSchemaDirectoryName(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("directoryName") != null) {
            return config.getString("directoryName");
        }
        return getSchemaDirectoryCode(schema);
    }

    public String getSchemaDisplayMode(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("displayMode") != null) {
            return config.getString("displayMode");
        }
        return "field".equalsIgnoreCase(resolveSchemaMetaModelCode(schema)) ? "MULTIPLE" : "SINGLE";
    }

    public String getSchemaCollectionKey(MetadataSchemaDefinition schema) {
        String metaModelCode = resolveSchemaMetaModelCode(schema);
        if ("field".equalsIgnoreCase(metaModelCode)) {
            return "columns";
        }
        if (metaModelCode == null || metaModelCode.trim().isEmpty()) {
            return "items";
        }
        return metaModelCode.endsWith("s") ? metaModelCode : metaModelCode + "s";
    }

    private JSONObject extractMetaModelConfig(MetadataSchemaDefinition schema) {
        if (schema == null || schema.getDescription() == null) {
            return null;
        }
        return MetaModelConfigDescriptions.decode(schema.getDescription());
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
                fieldDefinition.setSearchable(field.getSearchableFlag() != null && field.getSearchableFlag() == 1);
                fieldDefinition.setSortable(field.getSortableFlag() != null && field.getSortableFlag() == 1);
                fieldDefinition.setQueryOperators(field.getQueryOperators() == null ? new ArrayList<String>() : field.getQueryOperators());
                fieldDefinition.setQueryDefaultOperator(field.getQueryDefaultOperator());
                fieldDefinition.setOptions(field.getOptions() == null ? new ArrayList<String>() : field.getOptions());
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

