package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.commons.element.BoolColumn;
import com.jdragon.aggregation.commons.element.Column;
import com.jdragon.aggregation.commons.element.DoubleColumn;
import com.jdragon.aggregation.commons.element.LongColumn;
import com.jdragon.aggregation.commons.element.Record;
import com.jdragon.aggregation.commons.element.StringColumn;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.core.enums.State;
import com.jdragon.aggregation.core.job.JobContainer;
import com.jdragon.aggregation.core.plugin.PluginType;
import com.jdragon.aggregation.core.plugin.RecordSender;
import com.jdragon.aggregation.core.plugin.spi.Reader;
import com.jdragon.aggregation.core.statistics.communication.Communication;
import com.jdragon.aggregation.core.transport.record.DefaultRecord;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.DataIngestionStatus;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionInvokeResult;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class DataIngestionExecutionSupport {

    private static final Logger log = LoggerFactory.getLogger(DataIngestionExecutionSupport.class);
    private static final int DEFAULT_MAX_BATCH_SIZE = 500;
    private static final long DEFAULT_WRITE_TIMEOUT_MS = 10000L;

    private final CollectionTaskAssemblerService collectionTaskAssemblerService;
    private final ObjectMapper objectMapper;

    DataIngestionExecutionSupport(CollectionTaskAssemblerService collectionTaskAssemblerService,
                                  ObjectMapper objectMapper) {
        this.collectionTaskAssemblerService = collectionTaskAssemblerService;
        this.objectMapper = objectMapper;
    }

    DataIngestionInvokeResult execute(DataIngestionServiceView service,
                                      List<DataIngestionFieldMapping> mappings,
                                      Map<String, Object> headers,
                                      Map<String, Object> query,
                                      Map<String, Object> form,
                                      Object body,
                                      String requestId,
                                      Long jobId,
                                      String logCaptureId,
                                      boolean enforceStatus) {
        if (enforceStatus && service.getStatus() != DataIngestionStatus.ONLINE) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data ingestion service is not available");
        }
        List<Map<String, Object>> sourceRows = parseSourceRows(service, body, mappings);
        int maxBatchSize = service.getMaxBatchSize() == null ? DEFAULT_MAX_BATCH_SIZE : service.getMaxBatchSize().intValue();
        if (sourceRows.size() > maxBatchSize) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Request row count exceeds max batch size: " + maxBatchSize);
        }
        List<String> targetFields = resolveTargetFields(mappings);
        List<Map<String, Object>> writerRows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> sourceRow : sourceRows) {
            writerRows.add(buildWriterRow(mappings, sourceRow, headers, query, form));
        }
        if (!writerRows.isEmpty()) {
            Map<String, Object> writer = collectionTaskAssemblerService.assembleWriter(service.getDatasourceId(),
                    service.getModelId(),
                    targetFields,
                    service.getWriterOptions());
            applyIngestionWriterOptions(writer, service.getWriterOptions());
            Map<String, Object> jobConfig = new LinkedHashMap<String, Object>();
            jobConfig.put("core.container.taskGroup.reportInterval", Integer.valueOf(1000));
            jobConfig.put("core.container.taskGroup.sleepInterval", Integer.valueOf(50));
            Map<String, Object> reader = new LinkedHashMap<String, Object>();
            reader.put("type", "memory");
            reader.put("config", new LinkedHashMap<String, Object>());
            jobConfig.put("reader", reader);
            jobConfig.put("writer", writer);
            JobContainer container = new JobContainer(Configuration.from(jobConfig));
            Long safeJobId = jobId == null ? IdWorker.getId() : jobId;
            container.setRunContext("jobId", safeJobId);
            container.addConsumerPlugin(PluginType.READER, new InMemoryRecordReader(writerRows, targetFields, mappings));
            startAndAssertJob(container, requestId, safeJobId, logCaptureId);
        }
        DataIngestionInvokeResult result = new DataIngestionInvokeResult();
        result.setRequestId(requestId);
        result.setServiceCode(service.getServiceCode());
        result.setReceivedCount(Long.valueOf(sourceRows.size()));
        result.setSuccessCount(Long.valueOf(sourceRows.size()));
        result.setFailedCount(Long.valueOf(0L));
        result.setStatus("SUCCESS");
        return result;
    }

    private void startAndAssertJob(JobContainer container, String requestId, Long jobId, String logCaptureId) {
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "DataIngestion-JobContainer-" + jobId);
                thread.setDaemon(true);
                return thread;
            }
        });
        Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                DataIngestionInvocationLogSupport.withMdc(logCaptureId, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            log.info("Starting data ingestion JobContainer requestId={}, jobId={}", requestId, jobId);
                            container.start();
                            log.info("Completed data ingestion JobContainer requestId={}, jobId={}", requestId, jobId);
                        } catch (RuntimeException ex) {
                            log.error("Data ingestion JobContainer failed requestId={}, jobId={}", requestId, jobId, ex);
                            throw ex;
                        } catch (Error error) {
                            log.error("Data ingestion JobContainer errored requestId={}, jobId={}", requestId, jobId, error);
                            throw error;
                        }
                    }
                });
            }
        });
        try {
            future.get(DEFAULT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Data ingestion write timed out after " + DEFAULT_WRITE_TIMEOUT_MS + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Data ingestion write was interrupted");
        } catch (ExecutionException e) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Data ingestion write failed: " + rootMessage(e.getCause()));
        } finally {
            executor.shutdownNow();
        }
        assertJobSucceeded(container);
    }

    private void assertJobSucceeded(JobContainer container) {
        Communication communication = container == null || container.getJobPointReporter() == null
                ? absent()
                : container.getJobPointReporter().getTrackCommunication();
        State state = communication == null ? absent() : communication.getState();
        if (State.SUCCEEDED.equals(state)) {
            return;
        }
        Throwable throwable = communication == null ? absent() : communication.getThrowable();
        String message = throwable == null || throwable.getMessage() == null
                ? "Data ingestion write failed"
                : "Data ingestion write failed: " + throwable.getMessage();
        throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, message);
    }

    private String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    List<Map<String, Object>> parseSourceRows(DataIngestionServiceView service,
                                              Object body,
                                              List<DataIngestionFieldMapping> mappings) {
        if (!usesJsonBody(mappings)) {
            return Collections.singletonList(new LinkedHashMap<String, Object>());
        }
        Object payload = body;
        if (hasText(service.getDataNodePath())) {
            payload = readPath(body, service.getDataNodePath());
        }
        if (payload instanceof List<?>) {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Object item : (List<?>) payload) {
                rows.add(asObjectMap(item, "JSON array item must be an object"));
            }
            return rows;
        }
        return Collections.singletonList(asObjectMap(payload, "JSON body must be an object or array"));
    }

    private Map<String, Object> buildWriterRow(List<DataIngestionFieldMapping> mappings,
                                               Map<String, Object> sourceRow,
                                               Map<String, Object> headers,
                                               Map<String, Object> query,
                                               Map<String, Object> form) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (DataIngestionFieldMapping mapping : mappings) {
            String sourceField = hasText(mapping.getSourceField()) ? mapping.getSourceField().trim() : mapping.getTargetField();
            Object value = resolveIncomingValue(mapping.getSourcePosition(), sourceField, sourceRow, headers, query, form);
            if (isBlankValue(value) && hasText(mapping.getDefaultValue())) {
                value = mapping.getDefaultValue().trim();
            }
            if (isBlankValue(value) && Boolean.TRUE.equals(mapping.getRequired())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Field is required: " + mapping.getTargetField());
            }
            row.put(mapping.getTargetField(), convertValue(value, mapping.getValueType()));
        }
        return row;
    }

    private Object resolveIncomingValue(DataIngestionSourcePosition position,
                                        String sourceField,
                                        Map<String, Object> sourceRow,
                                        Map<String, Object> headers,
                                        Map<String, Object> query,
                                        Map<String, Object> form) {
        DataIngestionSourcePosition safePosition = position == null ? DataIngestionSourcePosition.BODY : position;
        if (safePosition == DataIngestionSourcePosition.HEADER) {
            return lookupIgnoreCase(headers, sourceField);
        }
        if (safePosition == DataIngestionSourcePosition.QUERY) {
            return lookupIgnoreCase(query, sourceField);
        }
        if (safePosition == DataIngestionSourcePosition.FORM) {
            return lookupIgnoreCase(form, sourceField);
        }
        return readPath(sourceRow, sourceField);
    }

    private void applyIngestionWriterOptions(Map<String, Object> writer, Map<String, Object> writerOptions) {
        if (writer == null || writerOptions == null || writerOptions.isEmpty()) {
            return;
        }
        Object configObject = writer.get("config");
        if (!(configObject instanceof Map<?, ?>)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) configObject;
        for (Map.Entry<String, Object> entry : writerOptions.entrySet()) {
            if (entry.getKey() == null || "connect".equalsIgnoreCase(entry.getKey()) || "columns".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            config.put(entry.getKey(), entry.getValue());
        }
    }

    private List<String> resolveTargetFields(List<DataIngestionFieldMapping> mappings) {
        List<String> result = new ArrayList<String>();
        for (DataIngestionFieldMapping mapping : mappings) {
            result.add(mapping.getTargetField());
        }
        return result;
    }

    private boolean usesJsonBody(List<DataIngestionFieldMapping> mappings) {
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

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String) {
            return ((String) value).trim().isEmpty();
        }
        return false;
    }

    private Object lookupIgnoreCase(Map<String, Object> source, String key) {
        if (source == null || key == null) {
            return absent();
        }
        if (source.containsKey(key)) {
            return source.get(key);
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return absent();
    }

    private Object readPath(Object source, String path) {
        if (source == null || !hasText(path)) {
            return source;
        }
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (current == null || !hasText(segment)) {
                return absent();
            }
            current = readSegment(current, segment.trim());
        }
        return current;
    }

    private Object readSegment(Object source, String segment) {
        String name = segment;
        Integer index = absent();
        int bracket = segment.indexOf('[');
        if (bracket >= 0 && segment.endsWith("]")) {
            name = segment.substring(0, bracket);
            try {
                index = Integer.valueOf(segment.substring(bracket + 1, segment.length() - 1));
            } catch (Exception ex) {
                return absent();
            }
        }
        Object value = source;
        if (hasText(name)) {
            if (!(source instanceof Map<?, ?>)) {
                return absent();
            }
            value = lookupIgnoreCase(castMap(source), name);
        }
        if (index != null) {
            if (!(value instanceof List<?>)) {
                return absent();
            }
            List<?> list = (List<?>) value;
            return index.intValue() >= 0 && index.intValue() < list.size() ? list.get(index.intValue()) : absent();
        }
        return value;
    }

    private Map<String, Object> asObjectMap(Object value, String message) {
        if (value == null) {
            return new LinkedHashMap<String, Object>();
        }
        if (value instanceof Map<?, ?>) {
            return castMap(value);
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
    }

    private Map<String, Object> castMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map<?, ?>)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private Object convertValue(Object value, FieldValueType valueType) {
        if (value == null) {
            return absent();
        }
        FieldValueType type = valueType == null ? FieldValueType.STRING : valueType;
        try {
            if (type == FieldValueType.INTEGER || type == FieldValueType.LONG) {
                if (value instanceof Number) {
                    return Long.valueOf(((Number) value).longValue());
                }
                return Long.valueOf(String.valueOf(value).trim());
            }
            if (type == FieldValueType.DECIMAL) {
                if (value instanceof Number) {
                    return Double.valueOf(((Number) value).doubleValue());
                }
                return new BigDecimal(String.valueOf(value).trim()).doubleValue();
            }
            if (type == FieldValueType.BOOLEAN) {
                if (value instanceof Boolean) {
                    return value;
                }
                return Boolean.valueOf(String.valueOf(value).trim());
            }
            if (type == FieldValueType.ARRAY || type == FieldValueType.OBJECT || type == FieldValueType.JSON) {
                return objectMapper.writeValueAsString(value);
            }
            return value;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Field value conversion failed: " + ex.getMessage());
        }
    }

    private Column toColumn(Object value, FieldValueType valueType) {
        if (value == null) {
            return new StringColumn(absent());
        }
        FieldValueType type = valueType == null ? FieldValueType.STRING : valueType;
        if (type == FieldValueType.INTEGER || type == FieldValueType.LONG) {
            if (value instanceof Number) {
                return new LongColumn(((Number) value).longValue());
            }
            return new LongColumn(Long.valueOf(String.valueOf(value).trim()));
        }
        if (type == FieldValueType.DECIMAL) {
            if (value instanceof Number) {
                return new DoubleColumn(((Number) value).doubleValue());
            }
            return new DoubleColumn(Double.valueOf(String.valueOf(value).trim()));
        }
        if (type == FieldValueType.BOOLEAN) {
            if (value instanceof Boolean) {
                return new BoolColumn((Boolean) value);
            }
            return new BoolColumn(Boolean.valueOf(String.valueOf(value).trim()));
        }
        return new StringColumn(String.valueOf(value));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private <T> T absent() {
        return Optional.<T>empty().orElse(null);
    }

    private final class InMemoryRecordReader extends Reader.Job {
        private final List<Map<String, Object>> rows;
        private final List<String> targetFields;
        private final Map<String, FieldValueType> valueTypes = new LinkedHashMap<String, FieldValueType>();

        private InMemoryRecordReader(List<Map<String, Object>> rows,
                                     List<String> targetFields,
                                     List<DataIngestionFieldMapping> mappings) {
            this.rows = rows;
            this.targetFields = targetFields;
            for (DataIngestionFieldMapping mapping : mappings) {
                valueTypes.put(mapping.getTargetField(), mapping.getValueType());
            }
        }

        @Override
        public void init() {
        }

        @Override
        public void startRead(RecordSender recordSender) {
            for (Map<String, Object> row : rows) {
                Record record = new DefaultRecord();
                for (int index = 0; index < targetFields.size(); index++) {
                    String field = targetFields.get(index);
                    record.setColumn(index, toColumn(row.get(field), valueTypes.get(field)));
                }
                recordSender.sendToWriter(record);
            }
        }

        @Override
        public void post() {
        }
    }
}
