package com.jdragon.studio.infra.service;

import com.jdragon.aggregation.commons.element.BoolColumn;
import com.jdragon.aggregation.commons.element.Column;
import com.jdragon.aggregation.commons.element.DoubleColumn;
import com.jdragon.aggregation.commons.element.LongColumn;
import com.jdragon.aggregation.commons.element.ObjectColumn;
import com.jdragon.aggregation.commons.element.Record;
import com.jdragon.aggregation.commons.element.StringColumn;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.core.plugin.TaskPluginCollector;
import com.jdragon.aggregation.core.statistics.communication.Communication;
import com.jdragon.aggregation.core.transformer.TransformerExecution;
import com.jdragon.aggregation.core.transport.exchanger.TransformerExchanger;
import com.jdragon.aggregation.core.transport.record.DefaultRecord;
import com.jdragon.aggregation.core.utils.TransformerUtil;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataServiceResponseParamView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes response transformers only inside the Worker execution plane. */
public final class StudioTransformerExecutionSupport {

    private final StudioTransformerSupport transformerSupport;

    public StudioTransformerExecutionSupport(StudioTransformerSupport transformerSupport) {
        this.transformerSupport = transformerSupport;
    }

    public List<Map<String, Object>> applyOnlineResponseTransformers(List<Map<String, Object>> rows,
                                                                      List<DataServiceResponseParamView> responseParams) {
        if (rows == null || rows.isEmpty()) {
            return rows == null ? new ArrayList<Map<String, Object>>() : rows;
        }
        List<String> orderedFields = transformerSupport.resolveEnabledResponseParamNames(responseParams);
        if (orderedFields.isEmpty()) {
            return rows;
        }

        /*
         * TransformerUtil resolves external transformers lazily.  Their loader
         * lease must remain alive from construction through every evaluate call,
         * rather than ending when TransformerInfo is cached.  Data-service
         * execution normally supplies the enclosing session; standalone Worker
         * operations receive a short-lived operation session here.
         */
        if (PluginRuntimeSession.current() != null) {
            return applyOnlineResponseTransformersInRuntimeSession(rows, responseParams, orderedFields);
        }
        try (PluginRuntimeSession operationSession = PluginRuntimeSession.open()) {
            return applyOnlineResponseTransformersInRuntimeSession(rows, responseParams, orderedFields);
        }
    }

    private List<Map<String, Object>> applyOnlineResponseTransformersInRuntimeSession(
            List<Map<String, Object>> rows,
            List<DataServiceResponseParamView> responseParams,
            List<String> orderedFields) {
        List<Map<String, Object>> transformerConfigs = transformerSupport.buildAggregationTransformersForResponses(
                responseParams, orderedFields, true);
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
        TransformerExchanger exchanger = new TransformerExchanger(
                transformerExecutions, new Communication(), new NoopTaskPluginCollector()) {
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

    private StudioException toStudioException(String prefix, RuntimeException ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
        return new StudioException(StudioErrorCode.BAD_REQUEST, prefix + ": " + message);
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
