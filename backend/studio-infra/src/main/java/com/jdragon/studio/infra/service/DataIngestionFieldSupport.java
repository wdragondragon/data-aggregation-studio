package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataServiceFieldView;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.SqlExecutionResultView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class DataIngestionFieldSupport {

    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataDevelopmentSqlExecutor sqlExecutor;
    private final DataServiceInvocationSupport dataServiceInvocationSupport = new DataServiceInvocationSupport();
    private final CollectionTaskFieldMappingResolver fieldMappingResolver = new CollectionTaskFieldMappingResolver();

    DataIngestionFieldSupport(DataDevelopmentSqlExecutor sqlExecutor) {
        this.sqlExecutor = sqlExecutor;
    }

    List<DataServiceFieldView> resolveModelFields(DataSourceDefinition datasource, DataModelDefinition model) {
        List<DataServiceFieldView> fromMetadata = fieldsFromModelMetadata(model);
        if (!fromMetadata.isEmpty()) {
            return fromMetadata;
        }
        if (datasource != null && sqlExecutor.supports(datasource) && hasText(model.getPhysicalLocator())) {
            String physicalLocator = normalizeRequiredText(model.getPhysicalLocator(), "Model physical locator is empty");
            dataServiceInvocationSupport.validateTableReference(physicalLocator);
            SqlExecutionResultView result = sqlExecutor.executePreparedQuery(datasource,
                    "select * from " + physicalLocator + " where 1 = 0",
                    Collections.<Object>emptyList(),
                    1);
            return fieldsFromColumns(result.getColumns());
        }
        List<DataServiceFieldView> result = new ArrayList<DataServiceFieldView>();
        for (String field : fieldMappingResolver.resolveModelFields(model)) {
            DataServiceFieldView item = new DataServiceFieldView();
            item.setFieldName(field);
            item.setFieldType("string");
            result.add(item);
        }
        return result;
    }

    List<DataIngestionFieldMapping> normalizeFieldMappings(List<DataIngestionFieldMapping> mappings, DataModelDefinition model) {
        List<DataIngestionFieldMapping> source = mappings == null || mappings.isEmpty() ? defaultFieldMappings(model) : mappings;
        List<DataIngestionFieldMapping> result = new ArrayList<DataIngestionFieldMapping>();
        Set<String> targetFields = new LinkedHashSet<String>();
        int index = 0;
        for (DataIngestionFieldMapping mapping : source) {
            if (mapping == null || !hasText(mapping.getTargetField())) {
                continue;
            }
            validateSimpleIdentifier(mapping.getTargetField(), "Target field is invalid: " + mapping.getTargetField());
            if (!targetFields.add(mapping.getTargetField().trim())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Duplicate target field: " + mapping.getTargetField());
            }
            DataIngestionFieldMapping item = new DataIngestionFieldMapping();
            item.setSortOrder(mapping.getSortOrder() == null ? Integer.valueOf(index) : mapping.getSortOrder());
            item.setSourcePosition(mapping.getSourcePosition() == null ? DataIngestionSourcePosition.BODY : mapping.getSourcePosition());
            item.setSourceField(hasText(mapping.getSourceField()) ? mapping.getSourceField().trim() : mapping.getTargetField().trim());
            item.setTargetField(mapping.getTargetField().trim());
            item.setValueType(mapping.getValueType() == null ? FieldValueType.STRING : mapping.getValueType());
            item.setRequired(Boolean.TRUE.equals(mapping.getRequired()));
            item.setDefaultValue(normalizeText(mapping.getDefaultValue()));
            item.setDescription(normalizeText(mapping.getDescription()));
            result.add(item);
            index++;
        }
        result.sort(Comparator.comparing(DataIngestionFieldMapping::getSortOrder, Comparator.nullsLast(Integer::compareTo)));
        if (result.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "At least one field mapping is required");
        }
        return result;
    }

    List<DataIngestionFieldMapping> defaultFieldMappings(DataModelDefinition model) {
        List<DataIngestionFieldMapping> result = new ArrayList<DataIngestionFieldMapping>();
        int index = 0;
        for (String field : fieldMappingResolver.resolveModelFields(model)) {
            DataIngestionFieldMapping mapping = new DataIngestionFieldMapping();
            mapping.setSortOrder(Integer.valueOf(index));
            mapping.setSourcePosition(DataIngestionSourcePosition.BODY);
            mapping.setSourceField(field);
            mapping.setTargetField(field);
            mapping.setValueType(FieldValueType.STRING);
            mapping.setRequired(Boolean.FALSE);
            result.add(mapping);
            index++;
        }
        return result;
    }

    boolean usesJsonBody(List<DataIngestionFieldMapping> mappings) {
        if (mappings == null) {
            return false;
        }
        for (DataIngestionFieldMapping mapping : mappings) {
            if (mapping == null) {
                continue;
            }
            DataIngestionSourcePosition position = mapping.getSourcePosition() == null
                    ? DataIngestionSourcePosition.BODY
                    : mapping.getSourcePosition();
            if (position == DataIngestionSourcePosition.BODY) {
                return true;
            }
        }
        return false;
    }

    private List<DataServiceFieldView> fieldsFromModelMetadata(DataModelDefinition model) {
        List<DataServiceFieldView> result = new ArrayList<DataServiceFieldView>();
        if (model == null || model.getTechnicalMetadata() == null) {
            return result;
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        if (!(columns instanceof List<?>)) {
            return result;
        }
        for (Object column : (List<?>) columns) {
            if (!(column instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> item = (Map<?, ?>) column;
            String name = asString(firstPresent(item, "name", "columnName", "fieldName"));
            if (!hasText(name)) {
                continue;
            }
            DataServiceFieldView field = new DataServiceFieldView();
            field.setFieldName(name.trim());
            field.setFieldType(asString(firstPresent(item, "type", "dataType", "columnType")));
            field.setDescription(asString(firstPresent(item, "comment", "description", "remark")));
            result.add(field);
        }
        return result;
    }

    private List<DataServiceFieldView> fieldsFromColumns(List<String> columns) {
        List<DataServiceFieldView> result = new ArrayList<DataServiceFieldView>();
        if (columns == null) {
            return result;
        }
        for (String column : columns) {
            if (!hasText(column)) {
                continue;
            }
            DataServiceFieldView field = new DataServiceFieldView();
            field.setFieldName(column.trim());
            field.setFieldType("string");
            result.add(field);
        }
        return result;
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return absent();
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (!isBlankValue(value)) {
                return value;
            }
        }
        return absent();
    }

    private void validateSimpleIdentifier(String identifier, String message) {
        if (!hasText(identifier) || !SIMPLE_IDENTIFIER.matcher(identifier.trim()).matches()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
    }

    private String normalizeRequiredText(String value, String message) {
        if (!hasText(value)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeText(String value) {
        return value == null || value.trim().isEmpty() ? absent() : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String) {
            return ((String) value).trim().isEmpty();
        }
        return false;
    }

    private String asString(Object value) {
        return value == null ? absent() : String.valueOf(value);
    }

    private <T> T absent() {
        return java.util.Optional.<T>empty().orElse(null);
    }
}
