package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.commons.element.BoolColumn;
import com.jdragon.aggregation.commons.element.Column;
import com.jdragon.aggregation.commons.element.DoubleColumn;
import com.jdragon.aggregation.commons.element.LongColumn;
import com.jdragon.aggregation.commons.element.ObjectColumn;
import com.jdragon.aggregation.commons.element.Record;
import com.jdragon.aggregation.commons.element.StringColumn;
import com.jdragon.aggregation.commons.exception.AggregationException;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.core.plugin.TaskPluginCollector;
import com.jdragon.aggregation.core.statistics.communication.Communication;
import com.jdragon.aggregation.core.transformer.TransformerExecution;
import com.jdragon.aggregation.core.transport.exchanger.TransformerExchanger;
import com.jdragon.aggregation.core.transport.record.DefaultRecord;
import com.jdragon.aggregation.core.utils.TransformerUtil;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataServiceResponseParamView;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.dto.model.TransformerBinding;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class StudioTransformerSupport {

    private static final Set<String> ONLINE_UNSAFE_TRANSFORMERS = new LinkedHashSet<String>(Arrays.asList(
            "dx_filter",
            "range_number_filter",
            "string_operation_filter",
            "number_operation_filter",
            "date_filter",
            "date_operation_filter",
            "null_value_filter",
            "dx_groovy",
            "dx_fackGroovy"
    ));

    private final ObjectMapper objectMapper;

    public StudioTransformerSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<Map<String, Object>> buildAggregationTransformers(List<FieldMappingDefinition> mappings, List<String> targetFields) {
        List<TransformerTarget> targets = new ArrayList<TransformerTarget>();
        if (mappings != null) {
            for (FieldMappingDefinition mapping : mappings) {
                if (mapping == null || !hasText(mapping.getTargetField())) {
                    continue;
                }
                targets.add(new TransformerTarget(mapping.getTargetField(), mapping.getTransformers()));
            }
        }
        return buildAggregationTransformers(targets, targetFields, false);
    }

    List<Map<String, Object>> buildAggregationTransformersForResponses(List<DataServiceResponseParamView> responseParams,
                                                                       List<String> responseFields,
                                                                       boolean online) {
        List<TransformerTarget> targets = new ArrayList<TransformerTarget>();
        if (responseParams != null) {
            for (DataServiceResponseParamView param : responseParams) {
                if (param == null || Boolean.FALSE.equals(param.getEnabled()) || !hasText(param.getParamName())) {
                    continue;
                }
                targets.add(new TransformerTarget(param.getParamName(), param.getTransformers()));
            }
        }
        return buildAggregationTransformers(targets, responseFields, online);
    }

    List<Map<String, Object>> buildAggregationTransformers(List<TransformerTarget> targets,
                                                           List<String> orderedFields,
                                                           boolean online) {
        List<Map<String, Object>> transformers = new ArrayList<Map<String, Object>>();
        if (targets == null || orderedFields == null) {
            return transformers;
        }
        for (TransformerTarget target : targets) {
            if (target == null || !hasText(target.fieldName) || target.transformers == null || target.transformers.isEmpty()) {
                continue;
            }
            int columnIndex = orderedFields.indexOf(target.fieldName);
            if (columnIndex < 0) {
                continue;
            }
            for (TransformerBinding transformer : target.transformers) {
                String transformerCode = normalizeTransformerCode(transformer);
                if (!hasText(transformerCode)) {
                    continue;
                }
                if (online && isOnlineUnsafe(transformerCode)) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Transformer is not allowed for online data service response: " + transformerCode);
                }
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("name", transformerCode);
                Map<String, Object> parameters = new LinkedHashMap<String, Object>();
                parameters.put("columnIndex", Integer.valueOf(columnIndex));
                parameters.put("paras", extractRuntimeParas(transformer));
                item.put("parameter", parameters);
                transformers.add(item);
            }
        }
        return transformers;
    }

    public void validateOnlineResponseTransformers(List<DataServiceResponseParamView> responseParams) {
        if (responseParams == null) {
            return;
        }
        for (DataServiceResponseParamView param : responseParams) {
            if (param == null || param.getTransformers() == null) {
                continue;
            }
            for (TransformerBinding binding : param.getTransformers()) {
                String transformerCode = normalizeTransformerCode(binding);
                if (hasText(transformerCode) && isOnlineUnsafe(transformerCode)) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Transformer is not allowed for online data service response: " + transformerCode);
                }
            }
        }
    }

    public List<Map<String, Object>> applyOnlineResponseTransformers(List<Map<String, Object>> rows,
                                                                      List<DataServiceResponseParamView> responseParams) {
        if (rows == null || rows.isEmpty()) {
            return rows == null ? new ArrayList<Map<String, Object>>() : rows;
        }
        List<String> orderedFields = resolveEnabledResponseParamNames(responseParams);
        if (orderedFields.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> transformerConfigs = buildAggregationTransformersForResponses(responseParams, orderedFields, true);
        if (transformerConfigs.isEmpty()) {
            return rows;
        }
        Configuration config = Configuration.newDefault();
        config.set("transformer", transformerConfigs);
        List<TransformerExecution> transformerExecutions;
        try {
            transformerExecutions = TransformerUtil.buildTransformerInfo(config, Collections.emptyList());
        } catch (RuntimeException ex) {
            throw toStudioException("Data service response transformer initialization failed", ex);
        }
        TransformerExchanger exchanger = new TransformerExchanger(transformerExecutions, new Communication(), new NoopTaskPluginCollector()) {
        };
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            Record transformed;
            try {
                transformed = exchanger.doTransformer(toRecord(row, orderedFields));
            } catch (RuntimeException ex) {
                throw toStudioException("Data service response transformer execution failed", ex);
            }
            if (transformed == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Data service response transformer returned null; row filtering is not supported for online services");
            }
            result.add(toRow(transformed, orderedFields));
        }
        return result;
    }

    public List<Map<String, Object>> toBindingMaps(List<TransformerBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return new ArrayList<Map<String, Object>>();
        }
        return objectMapper.convertValue(bindings, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    public List<TransformerBinding> toBindings(List<Map<String, Object>> maps) {
        if (maps == null || maps.isEmpty()) {
            return new ArrayList<TransformerBinding>();
        }
        return objectMapper.convertValue(maps, new TypeReference<List<TransformerBinding>>() {
        });
    }

    private List<String> resolveEnabledResponseParamNames(List<DataServiceResponseParamView> responseParams) {
        List<String> result = new ArrayList<String>();
        if (responseParams == null) {
            return result;
        }
        for (DataServiceResponseParamView param : responseParams) {
            if (param == null || Boolean.FALSE.equals(param.getEnabled()) || !hasText(param.getParamName())) {
                continue;
            }
            result.add(param.getParamName().trim());
        }
        return result;
    }

    private Record toRecord(Map<String, Object> row, List<String> orderedFields) {
        DefaultRecord record = new DefaultRecord();
        for (int index = 0; index < orderedFields.size(); index++) {
            Object value = row == null ? null : row.get(orderedFields.get(index));
            record.setColumn(index, toColumn(value));
        }
        return record;
    }

    private Map<String, Object> toRow(Record record, List<String> orderedFields) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int index = 0; index < orderedFields.size(); index++) {
            row.put(orderedFields.get(index), toValue(record.getColumn(index)));
        }
        return row;
    }

    private Column toColumn(Object value) {
        if (value == null) {
            return new StringColumn(null);
        }
        if (value instanceof Boolean) {
            return new BoolColumn((Boolean) value);
        }
        if (value instanceof Integer) {
            return new LongColumn((Integer) value);
        }
        if (value instanceof Long) {
            return new LongColumn((Long) value);
        }
        if (value instanceof Short || value instanceof Byte || value instanceof BigInteger) {
            return new LongColumn(String.valueOf(value));
        }
        if (value instanceof Float) {
            return new DoubleColumn((Float) value);
        }
        if (value instanceof Double) {
            return new DoubleColumn((Double) value);
        }
        if (value instanceof BigDecimal) {
            return new DoubleColumn(((BigDecimal) value).toPlainString());
        }
        if (value instanceof String) {
            return new StringColumn((String) value);
        }
        return new ObjectColumn(value);
    }

    private Object toValue(Column column) {
        if (column == null || column.getRawData() == null) {
            return null;
        }
        if (column.getType() == Column.Type.INT || column.getType() == Column.Type.LONG) {
            return column.asLong();
        }
        if (column.getType() == Column.Type.DOUBLE) {
            return column.asDouble();
        }
        if (column.getType() == Column.Type.BOOL) {
            return column.asBoolean();
        }
        if (column.getType() == Column.Type.STRING) {
            return column.asString();
        }
        return column.getRawData();
    }

    private List<Object> extractRuntimeParas(TransformerBinding transformer) {
        if (transformer == null || transformer.getParameters() == null || transformer.getParameters().isEmpty()) {
            return Collections.emptyList();
        }
        Object paras = transformer.getParameters().get("paras");
        if (paras instanceof List<?>) {
            return new ArrayList<Object>((List<?>) paras);
        }
        List<Object> fallback = new ArrayList<Object>();
        for (Map.Entry<String, Object> entry : transformer.getParameters().entrySet()) {
            if ("columnIndex".equals(entry.getKey()) || "paras".equals(entry.getKey())) {
                continue;
            }
            fallback.add(entry.getValue());
        }
        return fallback;
    }

    private String normalizeTransformerCode(TransformerBinding transformer) {
        if (transformer == null || !hasText(transformer.getTransformerCode())) {
            return null;
        }
        return transformer.getTransformerCode().trim();
    }

    private boolean isOnlineUnsafe(String transformerCode) {
        for (String unsafe : ONLINE_UNSAFE_TRANSFORMERS) {
            if (unsafe.equalsIgnoreCase(transformerCode)) {
                return true;
            }
        }
        return false;
    }

    private StudioException toStudioException(String prefix, RuntimeException ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
        if (ex instanceof AggregationException) {
            return new StudioException(StudioErrorCode.BAD_REQUEST, prefix + ": " + message);
        }
        return new StudioException(StudioErrorCode.BAD_REQUEST, prefix + ": " + message);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    static final class TransformerTarget {
        private final String fieldName;
        private final List<TransformerBinding> transformers;

        private TransformerTarget(String fieldName, List<TransformerBinding> transformers) {
            this.fieldName = fieldName;
            this.transformers = transformers == null ? Collections.<TransformerBinding>emptyList() : transformers;
        }
    }

    private static final class NoopTaskPluginCollector extends TaskPluginCollector {
        @Override
        public void collectDirtyRecord(Record dirtyRecord, Throwable t, String errorMessage) {
        }

        @Override
        public void collectMessage(String key, String value) {
        }
    }
}
