package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.SchemaStatus;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.MetadataSchemaSaveRequest;
import com.jdragon.studio.core.spi.MetadataSchemaRegistry;
import com.jdragon.studio.infra.entity.MetaFieldDefinitionEntity;
import com.jdragon.studio.infra.entity.MetaSchemaEntity;
import com.jdragon.studio.infra.entity.MetaSchemaVersionEntity;
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

    public MetadataSchemaService(MetaSchemaMapper schemaMapper,
                                 MetaSchemaVersionMapper versionMapper,
                                 MetaFieldDefinitionMapper fieldDefinitionMapper) {
        this.schemaMapper = schemaMapper;
        this.versionMapper = versionMapper;
        this.fieldDefinitionMapper = fieldDefinitionMapper;
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

