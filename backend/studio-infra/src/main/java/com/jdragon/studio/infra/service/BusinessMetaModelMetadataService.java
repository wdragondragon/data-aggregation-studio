package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BusinessMetaModelMetadataService {

    public static final String META_MODELS_KEY = "__metaModels";

    private final MetadataSchemaService metadataSchemaService;

    public BusinessMetaModelMetadataService(MetadataSchemaService metadataSchemaService) {
        this.metadataSchemaService = metadataSchemaService;
    }

    public Map<String, Object> emptyMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(META_MODELS_KEY, new ArrayList<Map<String, Object>>());
        return metadata;
    }

    public Map<String, Object> normalizeForDatasource(Map<String, Object> metadata) {
        return toMetadata(resolveEntries(metadata, Collections.singleton("source"), true, "datasource"));
    }

    public Map<String, Object> normalizeForModel(Map<String, Object> metadata,
                                                 Collection<String> allowedMetaModelCodes) {
        Set<String> allowedCodes = normalizeCodes(allowedMetaModelCodes);
        return toMetadata(resolveEntries(metadata, allowedCodes, true, "model"));
    }

    public List<ResolvedBusinessMetaModelEntry> resolveEntries(Map<String, Object> metadata) {
        return resolveEntries(metadata, null, false, "runtime");
    }

    private List<ResolvedBusinessMetaModelEntry> resolveEntries(Map<String, Object> metadata,
                                                                Set<String> allowedMetaModelCodes,
                                                                boolean strict,
                                                                String contextName) {
        List<ResolvedBusinessMetaModelEntry> entries = new ArrayList<ResolvedBusinessMetaModelEntry>();
        for (Map<String, Object> entry : parseRawEntries(metadata)) {
            Long schemaVersionId = parseLong(entry.get("schemaVersionId"));
            if (schemaVersionId == null) {
                if (strict) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Business meta model schemaVersionId is required for " + contextName);
                }
                continue;
            }
            MetadataSchemaDefinition schema = metadataSchemaService.findSchemaByVersionId(schemaVersionId);
            if (schema == null) {
                if (strict) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Business meta model schema version not found: " + schemaVersionId);
                }
                continue;
            }
            if (!"BUSINESS".equalsIgnoreCase(metadataSchemaService.getSchemaDomain(schema))) {
                if (strict) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Schema is not a business meta model: " + schemaVersionId);
                }
                continue;
            }
            String metaModelCode = normalize(metadataSchemaService.getSchemaMetaModelCode(schema));
            if (allowedMetaModelCodes != null && !allowedMetaModelCodes.contains(metaModelCode)) {
                if (strict) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Business meta model code '" + metadataSchemaService.getSchemaMetaModelCode(schema)
                                    + "' is not allowed for " + contextName);
                }
                continue;
            }
            String displayMode = metadataSchemaService.getSchemaDisplayMode(schema);
            if ("MULTIPLE".equalsIgnoreCase(displayMode)) {
                List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
                for (Map<String, Object> row : parseRows(entry.get("rows"))) {
                    rows.add(applyDefaults(row, schema));
                }
                entries.add(new ResolvedBusinessMetaModelEntry(schema, rows));
            } else {
                entries.add(new ResolvedBusinessMetaModelEntry(schema, applyDefaults(parseValues(entry.get("values")), schema)));
            }
        }
        entries.sort((left, right) -> compareEntries(left.getSchema(), right.getSchema()));
        return entries;
    }

    private int compareEntries(MetadataSchemaDefinition left, MetadataSchemaDefinition right) {
        String leftDirectory = normalize(metadataSchemaService.getSchemaDirectoryName(left));
        String rightDirectory = normalize(metadataSchemaService.getSchemaDirectoryName(right));
        int directoryCompare = leftDirectory.compareTo(rightDirectory);
        if (directoryCompare != 0) {
            return directoryCompare;
        }
        String leftName = normalize(left == null ? null : left.getSchemaName());
        String rightName = normalize(right == null ? null : right.getSchemaName());
        return leftName.compareTo(rightName);
    }

    private Map<String, Object> toMetadata(List<ResolvedBusinessMetaModelEntry> entries) {
        Map<String, Object> metadata = emptyMetadata();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ResolvedBusinessMetaModelEntry entry : entries) {
            MetadataSchemaDefinition schema = entry.getSchema();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("schemaVersionId", schema.getCurrentVersionId() == null ? schema.getId() : schema.getCurrentVersionId());
            item.put("schemaCode", schema.getSchemaCode());
            item.put("schemaName", schema.getSchemaName());
            item.put("directoryCode", metadataSchemaService.getSchemaDirectoryCode(schema));
            item.put("directoryName", metadataSchemaService.getSchemaDirectoryName(schema));
            item.put("metaModelCode", metadataSchemaService.getSchemaMetaModelCode(schema));
            item.put("displayMode", metadataSchemaService.getSchemaDisplayMode(schema));
            if (entry.isMultiple()) {
                item.put("rows", entry.getRows());
            } else {
                item.put("values", entry.getValues());
            }
            items.add(item);
        }
        metadata.put(META_MODELS_KEY, items);
        return metadata;
    }

    private Set<String> normalizeCodes(Collection<String> codes) {
        if (codes == null) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<String>();
        for (String code : codes) {
            String value = normalize(code);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private List<Map<String, Object>> parseRawEntries(Map<String, Object> metadata) {
        if (metadata == null) {
            return new ArrayList<Map<String, Object>>();
        }
        Object rawEntries = metadata.get(META_MODELS_KEY);
        if (!(rawEntries instanceof List)) {
            return new ArrayList<Map<String, Object>>();
        }
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        for (Object candidate : (List<?>) rawEntries) {
            if (candidate instanceof Map) {
                entries.add(new LinkedHashMap<String, Object>((Map<String, Object>) candidate));
            }
        }
        return entries;
    }

    private Map<String, Object> parseValues(Object value) {
        if (!(value instanceof Map)) {
            return new LinkedHashMap<String, Object>();
        }
        return new LinkedHashMap<String, Object>((Map<String, Object>) value);
    }

    private List<Map<String, Object>> parseRows(Object value) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (!(value instanceof List)) {
            return rows;
        }
        for (Object candidate : (List<?>) value) {
            if (candidate instanceof Map) {
                rows.add(new LinkedHashMap<String, Object>((Map<String, Object>) candidate));
            }
        }
        return rows;
    }

    private Map<String, Object> applyDefaults(Map<String, Object> input,
                                              MetadataSchemaDefinition schema) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (input != null) {
            output.putAll(input);
        }
        if (schema == null || schema.getFields() == null) {
            return output;
        }
        for (MetadataFieldDefinition field : schema.getFields()) {
            if (field.getScope() != MetadataScope.BUSINESS) {
                continue;
            }
            if (output.containsKey(field.getFieldKey())) {
                continue;
            }
            Object defaultValue = parseDefaultValue(field);
            if (defaultValue != null) {
                output.put(field.getFieldKey(), defaultValue);
            }
        }
        return output;
    }

    private Object parseDefaultValue(MetadataFieldDefinition field) {
        String defaultValue = field.getDefaultValue();
        if (defaultValue == null || defaultValue.trim().isEmpty()) {
            return null;
        }
        FieldValueType valueType = field.getValueType();
        if (valueType == null) {
            return defaultValue;
        }
        try {
            switch (valueType) {
                case BOOLEAN:
                    return Boolean.parseBoolean(defaultValue);
                case INTEGER:
                    return Integer.parseInt(defaultValue);
                case LONG:
                    return Long.parseLong(defaultValue);
                case DECIMAL:
                    return new BigDecimal(defaultValue);
                default:
                    return defaultValue;
            }
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private Long parseLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    public static class ResolvedBusinessMetaModelEntry {
        private final MetadataSchemaDefinition schema;
        private final Map<String, Object> values;
        private final List<Map<String, Object>> rows;
        private final boolean multiple;

        public ResolvedBusinessMetaModelEntry(MetadataSchemaDefinition schema,
                                              Map<String, Object> values) {
            this.schema = schema;
            this.values = values == null ? new LinkedHashMap<String, Object>() : values;
            this.rows = new ArrayList<Map<String, Object>>();
            this.multiple = false;
        }

        public ResolvedBusinessMetaModelEntry(MetadataSchemaDefinition schema,
                                              List<Map<String, Object>> rows) {
            this.schema = schema;
            this.values = new LinkedHashMap<String, Object>();
            this.rows = rows == null ? new ArrayList<Map<String, Object>>() : rows;
            this.multiple = true;
        }

        public MetadataSchemaDefinition getSchema() {
            return schema;
        }

        public Map<String, Object> getValues() {
            return values;
        }

        public List<Map<String, Object>> getRows() {
            return rows;
        }

        public boolean isMultiple() {
            return multiple;
        }
    }
}
