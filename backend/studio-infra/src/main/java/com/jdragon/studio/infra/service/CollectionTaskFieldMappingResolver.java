package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.FieldMappingDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CollectionTaskFieldMappingResolver {

    static final String FILE_FIELD_SOURCE_KIND_TAG = "TAG";

    private final StudioTransformerSupport transformerSupport;

    CollectionTaskFieldMappingResolver() {
        this(new StudioTransformerSupport(new ObjectMapper()));
    }

    CollectionTaskFieldMappingResolver(StudioTransformerSupport transformerSupport) {
        this.transformerSupport = transformerSupport;
    }

    List<Map<String, Object>> resolveColumnEntries(DataModelDefinition model,
                                                   List<String> fields,
                                                   boolean influxTypes) {
        List<String> selectedFields = fields == null || fields.isEmpty() ? resolveModelFields(model) : fields;
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> metadata = resolveModelFieldMetadata(model);
        for (int i = 0; i < selectedFields.size(); i++) {
            String fieldName = selectedFields.get(i);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", fieldName);
            item.put("index", Integer.valueOf(i));
            item.put("type", influxTypes ? resolveInfluxColumnType(fieldName, metadata.get(fieldName)) : resolveGenericColumnType(metadata.get(fieldName)));
            result.add(item);
        }
        return result;
    }

    List<Map<String, Object>> resolveFileColumnEntries(DataModelDefinition model,
                                                       List<String> fields,
                                                       List<String> dataTags) {
        List<String> selectedFields = fields == null || fields.isEmpty() ? resolveModelFields(model) : fields;
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> metadata = resolveModelFieldMetadata(model);
        Map<String, Integer> fieldOrder = resolveModelFieldOrder(model);
        Map<String, Integer> tagOrder = resolveTagFieldOrder(dataTags);
        int dataColumnCount = resolveDataColumnCount(model);
        for (int i = 0; i < selectedFields.size(); i++) {
            String fieldName = selectedFields.get(i);
            Map<String, Object> fieldMetadata = metadata.get(fieldName);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", fieldName);
            item.put("index", resolveFileColumnIndex(fieldName, fieldMetadata, fieldOrder, tagOrder, dataColumnCount, Integer.valueOf(i)));
            item.put("type", resolveGenericColumnType(fieldMetadata));
            result.add(item);
        }
        return result;
    }

    List<Map<String, Object>> resolveFileWriterColumnEntries(DataModelDefinition model,
                                                             List<String> fields) {
        List<String> selectedFields = fields == null || fields.isEmpty() ? resolveModelFields(model) : fields;
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> metadata = resolveModelFieldMetadata(model);
        for (int i = 0; i < selectedFields.size(); i++) {
            String fieldName = selectedFields.get(i);
            Map<String, Object> fieldMetadata = metadata.get(fieldName);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", fieldName);
            item.put("index", Integer.valueOf(i));
            item.put("type", resolveGenericColumnType(fieldMetadata));
            if (isTagFileField(fieldMetadata)) {
                item.put("sourceKind", FILE_FIELD_SOURCE_KIND_TAG);
            }
            result.add(item);
        }
        return result;
    }

    List<Map<String, Object>> resolveHttpColumnEntries(DataModelDefinition model,
                                                       List<String> fields) {
        List<String> selectedFields = fields == null || fields.isEmpty() ? resolveModelFields(model) : fields;
        if (selectedFields.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP response fields are required");
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> metadata = resolveModelFieldMetadata(model);
        for (String fieldName : selectedFields) {
            Map<String, Object> fieldMetadata = metadata.get(fieldName);
            Object parentNode = fieldMetadata == null ? null : fieldMetadata.get("parentNode");
            if (isBlankValue(parentNode)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP response field parentNode is required for field " + fieldName);
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("parentNode", String.valueOf(parentNode).trim());
            item.put("name", fieldName);
            item.put("type", resolveHttpColumnType(fieldMetadata));
            result.add(item);
        }
        return result;
    }

    List<Map<String, Object>> resolveHttpWriterColumnEntries(DataModelDefinition model,
                                                             List<String> fields) {
        List<String> selectedFields = fields == null || fields.isEmpty() ? resolveModelFields(model) : fields;
        if (selectedFields.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP request fields are required");
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> metadata = resolveModelFieldMetadata(model);
        for (int i = 0; i < selectedFields.size(); i++) {
            String fieldName = selectedFields.get(i);
            Map<String, Object> fieldMetadata = metadata.get(fieldName);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("index", Integer.valueOf(i));
            item.put("name", fieldName);
            item.put("type", resolveHttpColumnType(fieldMetadata));
            Object parentNode = fieldMetadata == null ? null : fieldMetadata.get("parentNode");
            if (!isBlankValue(parentNode)) {
                item.put("parentNode", String.valueOf(parentNode).trim());
            }
            result.add(item);
        }
        return result;
    }

    boolean hasTagFileField(List<Map<String, Object>> columns) {
        if (columns == null) {
            return false;
        }
        for (Map<String, Object> column : columns) {
            if (isTagFileField(column)) {
                return true;
            }
        }
        return false;
    }

    List<String> resolveFileDataTags(DataModelDefinition model) {
        List<String> result = new ArrayList<String>();
        for (Map<String, Object> metadata : resolveModelFieldMetadata(model).values()) {
            Object name = metadata.get("name");
            if (name != null && isTagFileField(metadata)) {
                result.add(String.valueOf(name));
            }
        }
        return result;
    }

    List<Map<String, Object>> buildTransformers(List<FieldMappingDefinition> mappings, List<String> targetFields) {
        return transformerSupport.buildAggregationTransformers(mappings, targetFields);
    }

    List<String> resolveTargetFields(List<FieldMappingDefinition> fieldMappings, DataModelDefinition targetModel) {
        List<String> targetFields = new ArrayList<String>();
        if (fieldMappings != null) {
            for (FieldMappingDefinition mapping : fieldMappings) {
                if (mapping.getTargetField() != null && !mapping.getTargetField().trim().isEmpty()) {
                    targetFields.add(mapping.getTargetField());
                }
            }
        }
        if (!targetFields.isEmpty()) {
            return targetFields;
        }
        return resolveModelFields(targetModel);
    }

    List<String> resolveSingleSourceFields(List<FieldMappingDefinition> fieldMappings, DataModelDefinition sourceModel) {
        List<String> sourceFields = new ArrayList<String>();
        if (fieldMappings != null) {
            for (FieldMappingDefinition mapping : fieldMappings) {
                if (mapping.getSourceField() != null && !mapping.getSourceField().trim().isEmpty()) {
                    sourceFields.add(mapping.getSourceField());
                }
            }
        }
        if (!sourceFields.isEmpty()) {
            return sourceFields;
        }
        return resolveModelFields(sourceModel);
    }

    List<String> resolveSourceFieldsByAlias(List<FieldMappingDefinition> fieldMappings,
                                            String sourceAlias,
                                            DataModelDefinition sourceModel) {
        Set<String> fields = new LinkedHashSet<String>();
        if (fieldMappings != null) {
            for (FieldMappingDefinition mapping : fieldMappings) {
                if (sourceAlias != null
                        && sourceAlias.equals(mapping.getSourceAlias())
                        && mapping.getSourceField() != null
                        && !mapping.getSourceField().trim().isEmpty()) {
                    fields.add(mapping.getSourceField());
                }
            }
        }
        if (!fields.isEmpty()) {
            return new ArrayList<String>(fields);
        }
        return resolveModelFields(sourceModel);
    }

    List<String> resolveModelFields(DataModelDefinition model) {
        if (model == null || model.getTechnicalMetadata() == null) {
            return Collections.emptyList();
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        List<String> fields = new ArrayList<String>();
        if (columns instanceof List<?>) {
            for (Object item : (List<?>) columns) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Object name = ((Map<?, ?>) item).get("name");
                if (name != null && !String.valueOf(name).trim().isEmpty()) {
                    fields.add(String.valueOf(name));
                }
            }
        }
        return fields;
    }

    List<String> resolveJoinKeys(CollectionTaskDefinitionView definition) {
        Map<String, Object> executionOptions = definition.getExecutionOptions() == null
                ? new LinkedHashMap<String, Object>()
                : definition.getExecutionOptions();
        Object keys = executionOptions.get("joinKeys");
        List<String> joinKeys = new ArrayList<String>();
        if (keys instanceof List<?>) {
            for (Object item : (List<?>) keys) {
                if (item != null && !String.valueOf(item).trim().isEmpty()) {
                    joinKeys.add(String.valueOf(item));
                }
            }
        } else if (keys instanceof String && !((String) keys).trim().isEmpty()) {
            String[] items = ((String) keys).split(",");
            for (String item : items) {
                if (!item.trim().isEmpty()) {
                    joinKeys.add(item.trim());
                }
            }
        }
        if (definition.getTaskType() == CollectionTaskType.FUSION && joinKeys.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Fusion task requires join keys");
        }
        return joinKeys;
    }

    private Integer resolveFileColumnIndex(String fieldName,
                                           Map<String, Object> metadata,
                                           Map<String, Integer> fieldOrder,
                                           Map<String, Integer> tagOrder,
                                           int dataColumnCount,
                                           Integer fallback) {
        Integer tagIndex = tagOrder.get(fieldName);
        if (tagIndex != null) {
            return Integer.valueOf(dataColumnCount + tagIndex.intValue());
        }
        if (metadata != null) {
            Object index = metadata.get("index");
            if (index instanceof Number) {
                return Integer.valueOf(((Number) index).intValue());
            }
            if (index != null && !String.valueOf(index).trim().isEmpty()) {
                try {
                    return Integer.valueOf(String.valueOf(index).trim());
                } catch (NumberFormatException parseFailure) {
                    // Fall through to model order.
                }
            }
        }
        Integer modelOrder = fieldOrder.get(fieldName);
        return modelOrder == null ? fallback : modelOrder;
    }

    private Map<String, Integer> resolveTagFieldOrder(List<String> dataTags) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        if (dataTags == null) {
            return result;
        }
        for (int i = 0; i < dataTags.size(); i++) {
            String tag = dataTags.get(i);
            if (tag != null && !tag.trim().isEmpty()) {
                result.put(tag, Integer.valueOf(i));
            }
        }
        return result;
    }

    private int resolveDataColumnCount(DataModelDefinition model) {
        if (model == null || model.getTechnicalMetadata() == null) {
            return 0;
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        int count = 0;
        if (columns instanceof List<?>) {
            for (Object item : (List<?>) columns) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Map<?, ?> source = (Map<?, ?>) item;
                Object name = source.get("name");
                if (name != null && !String.valueOf(name).trim().isEmpty() && !isTagFileField(source)) {
                    count++;
                }
            }
        }
        return count;
    }

    boolean isTagFileField(Map<?, ?> metadata) {
        Object sourceKind = metadata == null ? null : metadata.get("sourceKind");
        return sourceKind != null && FILE_FIELD_SOURCE_KIND_TAG.equalsIgnoreCase(String.valueOf(sourceKind).trim());
    }

    private Map<String, Integer> resolveModelFieldOrder(DataModelDefinition model) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        if (model == null || model.getTechnicalMetadata() == null) {
            return result;
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        if (columns instanceof List<?>) {
            int index = 0;
            for (Object item : (List<?>) columns) {
                if (item instanceof Map<?, ?>) {
                    Object name = ((Map<?, ?>) item).get("name");
                    if (name != null && !String.valueOf(name).trim().isEmpty()) {
                        result.put(String.valueOf(name), Integer.valueOf(index));
                    }
                }
                index++;
            }
        }
        return result;
    }

    private Map<String, Map<String, Object>> resolveModelFieldMetadata(DataModelDefinition model) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        if (model == null || model.getTechnicalMetadata() == null) {
            return result;
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        if (columns instanceof List<?>) {
            for (Object item : (List<?>) columns) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Map<?, ?> source = (Map<?, ?>) item;
                Object name = source.get("name");
                if (name == null || String.valueOf(name).trim().isEmpty()) {
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (entry.getKey() != null) {
                        metadata.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                result.put(String.valueOf(name), metadata);
            }
        }
        return result;
    }

    private String resolveGenericColumnType(Map<String, Object> metadata) {
        if (metadata == null) {
            return "string";
        }
        Object type = metadata.get("type");
        return type == null || String.valueOf(type).trim().isEmpty() ? "string" : String.valueOf(type);
    }

    private String resolveInfluxColumnType(String fieldName, Map<String, Object> metadata) {
        if ("time".equalsIgnoreCase(fieldName)) {
            return "time";
        }
        if (metadata != null) {
            Object type = metadata.get("type");
            if (type != null) {
                String normalized = String.valueOf(type).trim().toLowerCase();
                if ("time".equals(normalized) || "tag".equals(normalized) || "field".equals(normalized)) {
                    return normalized;
                }
            }
        }
        return "field";
    }

    private String resolveHttpColumnType(Map<String, Object> metadata) {
        Object type = metadata == null ? null : metadata.get("type");
        return isBlankValue(type) ? "STRING" : String.valueOf(type).trim();
    }

    private boolean isBlankValue(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }
}
