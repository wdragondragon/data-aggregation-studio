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
import com.jdragon.studio.dto.model.DataIngestionSourceBinding;
import com.jdragon.studio.dto.model.DataIngestionSourceInvokeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class DataIngestionExecutionSupport {

    private static final Logger log = LoggerFactory.getLogger(DataIngestionExecutionSupport.class);
    private static final int DEFAULT_MAX_PARALLEL_TARGETS = 4;
    private static final long DEFAULT_WRITE_SLOW_THRESHOLD_MS = 10000L;
    private static final long JOB_CANCELLATION_WAIT_MS = 5000L;
    private static final long TARGET_CANCELLATION_WAIT_MS = JOB_CANCELLATION_WAIT_MS + 1000L;

    private final CollectionTaskAssemblerService collectionTaskAssemblerService;
    private final ObjectMapper objectMapper;
    private final long writeSlowThresholdMs;

    public DataIngestionExecutionSupport(CollectionTaskAssemblerService collectionTaskAssemblerService,
                                         ObjectMapper objectMapper) {
        this(collectionTaskAssemblerService, objectMapper, DEFAULT_WRITE_SLOW_THRESHOLD_MS);
    }

    DataIngestionExecutionSupport(CollectionTaskAssemblerService collectionTaskAssemblerService,
                                  ObjectMapper objectMapper,
                                  long writeSlowThresholdMs) {
        this.collectionTaskAssemblerService = collectionTaskAssemblerService;
        this.objectMapper = objectMapper;
        this.writeSlowThresholdMs = writeSlowThresholdMs;
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
        DataIngestionSourceBinding binding = new DataIngestionSourceBinding();
        binding.setSourceCode("source_1");
        binding.setSourceName("默认来源");
        binding.setSourcePosition(DataIngestionSourcePosition.BODY);
        binding.setSourcePath(service.getDataNodePath());
        binding.setPayloadMode(service.getPayloadMode());
        binding.setTargetType(service.getTargetType());
        binding.setDatasourceId(service.getDatasourceId());
        binding.setDatasourceName(service.getDatasourceName());
        binding.setDatasourceTypeCode(service.getDatasourceTypeCode());
        binding.setModelId(service.getModelId());
        binding.setModelName(service.getModelName());
        binding.setModelPhysicalLocator(service.getModelPhysicalLocator());
        binding.setMaxBatchSize(service.getMaxBatchSize());
        binding.setWriterOptions(service.getWriterOptions());
        binding.setFieldMappings(mappings);
        binding.setSortOrder(Integer.valueOf(0));
        binding.setEnabled(Boolean.TRUE);
        return executeBindings(service, Collections.singletonList(binding), headers, query, form, body, requestId, jobId, logCaptureId, null, enforceStatus);
    }

    DataIngestionInvokeResult executeBindings(DataIngestionServiceView service,
                                              List<DataIngestionSourceBinding> sourceBindings,
                                              Map<String, Object> headers,
                                              Map<String, Object> query,
                                              Map<String, Object> form,
                                              Object body,
                                              String requestId,
                                              Long jobId,
                                              String logCaptureId,
                                              boolean enforceStatus) {
        return executeBindings(service, sourceBindings, headers, query, form, body, requestId, jobId, logCaptureId, null, enforceStatus);
    }

    DataIngestionInvokeResult executeBindings(DataIngestionServiceView service,
                                              List<DataIngestionSourceBinding> sourceBindings,
                                              Map<String, Object> headers,
                                              Map<String, Object> query,
                                              Map<String, Object> form,
                                              Object body,
                                              String requestId,
                                              Long jobId,
                                              String logCaptureId,
                                              OpenServiceInvocationLogSupport.LogScope logScope,
                                              boolean enforceStatus) {
        if (enforceStatus && service.getStatus() != DataIngestionStatus.ONLINE) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Data ingestion service is not available");
        }
        List<DataIngestionSourceBinding> enabledBindings = enabledBindings(sourceBindings);
        if (enabledBindings.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "At least one enabled source binding is required");
        }
        DataIngestionInvokeResult result = new DataIngestionInvokeResult();
        result.setRequestId(requestId);
        result.setServiceCode(service.getServiceCode());
        long receivedCount = 0L;
        long successCount = 0L;
        long failedCount = 0L;
        int successSources = 0;
        int failedSources = 0;
        List<DataIngestionSourceInvokeResult> sourceResults = new ArrayList<DataIngestionSourceInvokeResult>();
        List<PreparedSourceBinding> preparedBindings = prepareSourceBindings(enabledBindings, headers, query, form, body);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(enabledBindings.size(), DEFAULT_MAX_PARALLEL_TARGETS), new ThreadFactory() {
            private int index = 1;

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "DataIngestion-TargetDispatcher-" + requestId + "-" + index++);
                thread.setDaemon(true);
                return thread;
            }
        });
        List<Future<IndexedSourceResult>> futures = new ArrayList<Future<IndexedSourceResult>>();
        List<Long> sourceJobIds = new ArrayList<Long>();
        List<String> logSectionKeys = new ArrayList<String>();
        for (int index = 0; index < preparedBindings.size(); index++) {
            final PreparedSourceBinding preparedBinding = preparedBindings.get(index);
            final int sourceIndex = preparedBinding.index;
            final DataIngestionSourceBinding binding = preparedBinding.binding;
            final Long sourceJobId = preparedBindings.size() == 1 && jobId != null ? jobId : IdWorker.getId();
            final String logSectionKey = buildLogSectionKey(binding, index, sourceJobId);
            sourceJobIds.add(sourceJobId);
            logSectionKeys.add(logSectionKey);
            if (logScope != null) {
                logScope.registerSection(logSectionKey, sourceJobId);
            }
            futures.add(executor.submit(new Callable<IndexedSourceResult>() {
                @Override
                public IndexedSourceResult call() {
                    DataIngestionSourceInvokeResult sourceResult = executeBinding(preparedBinding,
                            headers,
                            query,
                            form,
                            requestId,
                            sourceJobId,
                            logCaptureId,
                            logSectionKey);
                    return new IndexedSourceResult(sourceIndex, sourceResult);
                }
            }));
        }
        try {
            for (int index = 0; index < futures.size(); index++) {
                DataIngestionSourceBinding binding = preparedBindings.get(index).binding;
                Long sourceJobId = sourceJobIds.get(index);
                String logSectionKey = logSectionKeys.get(index);
                DataIngestionSourceInvokeResult sourceResult;
                try {
                    sourceResult = futures.get(index).get().sourceResult;
                } catch (InterruptedException ex) {
                    for (Future<IndexedSourceResult> future : futures) {
                        future.cancel(true);
                    }
                    executor.shutdownNow();
                    if (!awaitTerminationUninterruptibly(executor, TARGET_CANCELLATION_WAIT_MS)) {
                        log.warn("Data ingestion target dispatchers did not stop after cancellation requestId={}", requestId);
                    }
                    Thread.currentThread().interrupt();
                    throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Data ingestion write was interrupted");
                } catch (ExecutionException ex) {
                    sourceResult = failedResult(binding, sourceJobId, logSectionKey, ex.getCause());
                }
                sourceResults.add(sourceResult);
                receivedCount += safeLong(sourceResult.getReceivedCount());
                successCount += safeLong(sourceResult.getSuccessCount());
                failedCount += safeLong(sourceResult.getFailedCount());
                if ("SUCCESS".equalsIgnoreCase(sourceResult.getStatus())) {
                    successSources++;
                } else {
                    failedSources++;
                }
            }
        } finally {
            executor.shutdownNow();
        }
        result.setReceivedCount(Long.valueOf(receivedCount));
        result.setSuccessCount(Long.valueOf(successCount));
        result.setFailedCount(Long.valueOf(failedCount));
        result.setSourceResults(sourceResults);
        result.setPluginRevisions(mergePluginRevisions(sourceResults));
        if (failedSources == 0) {
            result.setStatus("SUCCESS");
        } else if (successSources == 0) {
            result.setStatus("FAILED");
        } else {
            result.setStatus("PARTIAL_SUCCESS");
        }
        return result;
    }

    private List<PreparedSourceBinding> prepareSourceBindings(List<DataIngestionSourceBinding> enabledBindings,
                                                              Map<String, Object> headers,
                                                              Map<String, Object> query,
                                                              Map<String, Object> form,
                                                              Object body) {
        List<PreparedSourceBinding> preparedBindings = new ArrayList<PreparedSourceBinding>();
        for (int index = 0; index < enabledBindings.size(); index++) {
            DataIngestionSourceBinding binding = enabledBindings.get(index);
            List<DataIngestionFieldMapping> mappings = binding.getFieldMappings() == null
                    ? Collections.<DataIngestionFieldMapping>emptyList()
                    : binding.getFieldMappings();
            List<Map<String, Object>> sourceRows = parseSourceRows(binding, headers, query, form, body, mappings);
            validateSourceRowLimit(binding, sourceRows.size());
            preparedBindings.add(new PreparedSourceBinding(index, binding, mappings, sourceRows));
        }
        return preparedBindings;
    }

    private void validateSourceRowLimit(DataIngestionSourceBinding binding, int rowCount) {
        Integer maxBatchSize = binding == null ? null : binding.getMaxBatchSize();
        if (maxBatchSize == null || maxBatchSize.intValue() <= 0 || rowCount <= maxBatchSize.intValue()) {
            return;
        }
        String sourceCode = hasText(binding.getSourceCode()) ? binding.getSourceCode() : "source";
        throw new StudioException(StudioErrorCode.BAD_REQUEST,
                "Request row count for source " + sourceCode + " exceeds max batch size: " + maxBatchSize);
    }

    private DataIngestionSourceInvokeResult executeBinding(PreparedSourceBinding preparedBinding,
                                                           Map<String, Object> headers,
                                                           Map<String, Object> query,
                                                           Map<String, Object> form,
                                                           String requestId,
                                                           Long jobId,
                                                           String logCaptureId,
                                                           String logSectionKey) {
        DataIngestionSourceBinding binding = preparedBinding.binding;
        DataIngestionSourceInvokeResult sourceResult = new DataIngestionSourceInvokeResult();
        sourceResult.setSourceCode(binding.getSourceCode());
        sourceResult.setSourceName(binding.getSourceName());
        sourceResult.setTargetDatasourceName(binding.getDatasourceName());
        sourceResult.setTargetModelName(binding.getModelName());
        sourceResult.setJobId(jobId);
        sourceResult.setLogSectionKey(logSectionKey);
        long receivedCount = preparedBinding.sourceRows.size();
        JobContainer container = null;
        try {
            List<DataIngestionFieldMapping> mappings = preparedBinding.mappings;
            List<Map<String, Object>> sourceRows = preparedBinding.sourceRows;
            List<String> targetFields = resolveTargetFields(mappings);
            List<Map<String, Object>> writerRows = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> sourceRow : sourceRows) {
                writerRows.add(buildWriterRow(mappings, sourceRow, headers, query, form));
            }
            if (!writerRows.isEmpty()) {
                Map<String, Object> writer = collectionTaskAssemblerService.assembleWriter(binding.getDatasourceId(),
                        binding.getModelId(),
                        targetFields,
                        binding.getWriterOptions());
                applyIngestionWriterOptions(writer, binding.getWriterOptions());
                Map<String, Object> jobConfig = new LinkedHashMap<String, Object>();
                jobConfig.put("core.container.taskGroup.reportInterval", Integer.valueOf(1000));
                jobConfig.put("core.container.taskGroup.sleepInterval", Integer.valueOf(50));
                Map<String, Object> reader = new LinkedHashMap<String, Object>();
                reader.put("type", "memory");
                reader.put("config", new LinkedHashMap<String, Object>());
                jobConfig.put("reader", reader);
                jobConfig.put("writer", writer);
                container = new JobContainer(Configuration.from(jobConfig));
                container.setRunContext("jobId", jobId);
                container.addConsumerPlugin(PluginType.READER, new InMemoryRecordReader(writerRows, targetFields, mappings));
                startAndAssertJob(container, requestId, jobId, logCaptureId);
            }
            sourceResult.setPluginRevisions(pluginRevisions(container));
            sourceResult.setReceivedCount(Long.valueOf(receivedCount));
            sourceResult.setSuccessCount(Long.valueOf(sourceRows.size()));
            sourceResult.setFailedCount(Long.valueOf(0L));
            sourceResult.setStatus("SUCCESS");
            return sourceResult;
        } catch (RuntimeException ex) {
            sourceResult.setPluginRevisions(pluginRevisions(container));
            sourceResult.setReceivedCount(Long.valueOf(receivedCount));
            sourceResult.setSuccessCount(Long.valueOf(0L));
            sourceResult.setFailedCount(Long.valueOf(receivedCount));
            sourceResult.setStatus("FAILED");
            sourceResult.setMessage(safeFailureMessage(ex));
            return sourceResult;
        }
    }

    private DataIngestionSourceInvokeResult failedResult(DataIngestionSourceBinding binding,
                                                         Long jobId,
                                                         String logSectionKey,
                                                         Throwable throwable) {
        DataIngestionSourceInvokeResult result = new DataIngestionSourceInvokeResult();
        result.setSourceCode(binding == null ? null : binding.getSourceCode());
        result.setSourceName(binding == null ? null : binding.getSourceName());
        result.setTargetDatasourceName(binding == null ? null : binding.getDatasourceName());
        result.setTargetModelName(binding == null ? null : binding.getModelName());
        result.setJobId(jobId);
        result.setLogSectionKey(logSectionKey);
        result.setReceivedCount(Long.valueOf(0L));
        result.setSuccessCount(Long.valueOf(0L));
        result.setFailedCount(Long.valueOf(0L));
        result.setStatus("FAILED");
        result.setMessage(safeFailureMessage(throwable));
        return result;
    }

    private Map<String, String> mergePluginRevisions(List<DataIngestionSourceInvokeResult> sourceResults) {
        Map<String, String> revisions = new LinkedHashMap<String, String>();
        if (sourceResults == null) {
            return revisions;
        }
        for (DataIngestionSourceInvokeResult sourceResult : sourceResults) {
            if (sourceResult == null || sourceResult.getPluginRevisions() == null) {
                continue;
            }
            for (Map.Entry<String, String> entry : sourceResult.getPluginRevisions().entrySet()) {
                String coordinate = entry.getKey();
                String identity = entry.getValue();
                if (hasText(coordinate) && hasText(identity) && !revisions.containsKey(coordinate)) {
                    revisions.put(coordinate, identity);
                }
            }
        }
        return revisions;
    }

    private Map<String, String> pluginRevisions(JobContainer container) {
        Map<String, String> revisions = new LinkedHashMap<String, String>();
        if (container == null || container.getRunContext() == null) {
            return revisions;
        }
        Object rawRevisions = container.getRunContext().get("pluginRevisions");
        if (!(rawRevisions instanceof Map<?, ?>)) {
            return revisions;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawRevisions).entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String coordinate = String.valueOf(entry.getKey()).trim();
            String identity = String.valueOf(entry.getValue()).trim();
            if (hasText(coordinate) && hasText(identity)) {
                revisions.put(coordinate, identity);
            }
        }
        return revisions;
    }

    private String buildLogSectionKey(DataIngestionSourceBinding binding, int index, Long jobId) {
        String sourceCode = binding == null ? null : binding.getSourceCode();
        String normalized = normalizeSectionPart(sourceCode);
        if (normalized.length() == 0) {
            normalized = "source_" + (index + 1);
        }
        return "target_" + (index + 1) + "_" + normalized + "_" + (jobId == null ? "no_job" : jobId);
    }

    private String normalizeSectionPart(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ((ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_'
                    || ch == '-') {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    void startAndAssertJob(JobContainer container, String requestId, Long jobId, String logCaptureId) {
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
                OpenServiceInvocationLogSupport.withMdc(logCaptureId, new Runnable() {
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
            if (writeSlowThresholdMs > 0) {
                future.get(writeSlowThresholdMs, TimeUnit.MILLISECONDS);
            } else {
                future.get();
            }
        } catch (TimeoutException e) {
            log.warn("Data ingestion write exceeded {} ms; waiting for final state requestId={}, jobId={}",
                    writeSlowThresholdMs, requestId, jobId);
            waitForJobCompletion(future, executor, requestId, jobId);
        } catch (InterruptedException e) {
            cancelJobAndAwait(future, executor, requestId, jobId);
            Thread.currentThread().interrupt();
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Data ingestion write was interrupted");
        } catch (ExecutionException e) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Data ingestion write failed: " + safeFailureMessage(e.getCause()));
        } finally {
            executor.shutdownNow();
        }
        assertJobSucceeded(container);
    }

    private void waitForJobCompletion(Future<?> future,
                                      ExecutorService executor,
                                      String requestId,
                                      Long jobId) {
        try {
            future.get();
        } catch (InterruptedException e) {
            cancelJobAndAwait(future, executor, requestId, jobId);
            Thread.currentThread().interrupt();
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Data ingestion write was interrupted");
        } catch (ExecutionException e) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Data ingestion write failed: " + safeFailureMessage(e.getCause()));
        }
    }

    private void cancelJobAndAwait(Future<?> future,
                                   ExecutorService executor,
                                   String requestId,
                                   Long jobId) {
        future.cancel(true);
        executor.shutdownNow();
        if (!awaitTerminationUninterruptibly(executor, JOB_CANCELLATION_WAIT_MS)) {
            log.warn("Data ingestion JobContainer did not stop after cancellation requestId={}, jobId={}",
                    requestId, jobId);
        }
    }

    private boolean awaitTerminationUninterruptibly(ExecutorService executor, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return executor.isTerminated();
                }
                try {
                    if (executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                        return true;
                    }
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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
        String detail = safeFailureMessage(throwable);
        String message = hasFailureText(detail)
                ? "Data ingestion write failed: " + detail
                : "Data ingestion write failed";
        throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, message);
    }

    private static final class PreparedSourceBinding {
        private final int index;
        private final DataIngestionSourceBinding binding;
        private final List<DataIngestionFieldMapping> mappings;
        private final List<Map<String, Object>> sourceRows;

        private PreparedSourceBinding(int index,
                                      DataIngestionSourceBinding binding,
                                      List<DataIngestionFieldMapping> mappings,
                                      List<Map<String, Object>> sourceRows) {
            this.index = index;
            this.binding = binding;
            this.mappings = mappings;
            this.sourceRows = sourceRows;
        }
    }

    private static final class IndexedSourceResult {
        private final int index;
        private final DataIngestionSourceInvokeResult sourceResult;

        private IndexedSourceResult(int index, DataIngestionSourceInvokeResult sourceResult) {
            this.index = index;
            this.sourceResult = sourceResult;
        }
    }

    static String safeFailureMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable current = throwable;
        String selected = null;
        while (current != null) {
            String sanitized = sanitizeFailureMessage(current.getMessage());
            if (hasFailureText(sanitized)) {
                selected = sanitized;
            }
            current = current.getCause();
        }
        return selected == null ? throwable.getClass().getSimpleName() : selected;
    }

    private static String sanitizeFailureMessage(String message) {
        if (!hasFailureText(message)) {
            return null;
        }
        String normalized = message.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        int causedByIndex = normalized.lastIndexOf("Caused by:");
        if (causedByIndex >= 0) {
            normalized = normalized.substring(causedByIndex + "Caused by:".length()).trim();
        }
        int stackFrameIndex = normalized.indexOf(" at ");
        if (stackFrameIndex >= 0) {
            normalized = normalized.substring(0, stackFrameIndex).trim();
        }
        normalized = normalized.replaceFirst("^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error):\\s*", "");
        if (normalized.length() > 500) {
            normalized = normalized.substring(0, 500) + "...";
        }
        return normalized;
    }

    private static boolean hasFailureText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    List<Map<String, Object>> parseSourceRows(DataIngestionServiceView service,
                                              Object body,
                                              List<DataIngestionFieldMapping> mappings) {
        DataIngestionSourceBinding binding = new DataIngestionSourceBinding();
        binding.setSourcePosition(DataIngestionSourcePosition.BODY);
        binding.setSourcePath(service == null ? null : service.getDataNodePath());
        binding.setFieldMappings(mappings);
        return parseSourceRows(binding,
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                body,
                mappings);
    }

    List<Map<String, Object>> parseSourceRows(DataIngestionSourceBinding binding,
                                              Map<String, Object> headers,
                                              Map<String, Object> query,
                                              Map<String, Object> form,
                                              Object body,
                                              List<DataIngestionFieldMapping> mappings) {
        DataIngestionSourcePosition sourcePosition = binding == null || binding.getSourcePosition() == null
                ? DataIngestionSourcePosition.BODY
                : binding.getSourcePosition();
        Object root = sourceRoot(sourcePosition, headers, query, form, body);
        if (sourcePosition == DataIngestionSourcePosition.BODY && !usesJsonBody(mappings) && !hasText(binding == null ? null : binding.getSourcePath())) {
            return Collections.singletonList(new LinkedHashMap<String, Object>());
        }
        Object payload = root;
        if (binding != null && hasText(binding.getSourcePath())) {
            payload = readPath(root, binding.getSourcePath());
        }
        if (payload instanceof List<?>) {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Object item : (List<?>) payload) {
                rows.add(asObjectMap(item, "Source array item must be an object"));
            }
            return rows;
        }
        return Collections.singletonList(asObjectMap(payload, "Source payload must be an object or array"));
    }

    private Object sourceRoot(DataIngestionSourcePosition sourcePosition,
                              Map<String, Object> headers,
                              Map<String, Object> query,
                              Map<String, Object> form,
                              Object body) {
        if (sourcePosition == DataIngestionSourcePosition.HEADER) {
            return headers == null ? new LinkedHashMap<String, Object>() : headers;
        }
        if (sourcePosition == DataIngestionSourcePosition.QUERY) {
            return query == null ? new LinkedHashMap<String, Object>() : query;
        }
        if (sourcePosition == DataIngestionSourcePosition.FORM) {
            return form == null ? new LinkedHashMap<String, Object>() : form;
        }
        return body;
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

    private List<DataIngestionSourceBinding> enabledBindings(List<DataIngestionSourceBinding> sourceBindings) {
        List<DataIngestionSourceBinding> result = new ArrayList<DataIngestionSourceBinding>();
        if (sourceBindings == null) {
            return result;
        }
        for (DataIngestionSourceBinding binding : sourceBindings) {
            if (binding != null && !Boolean.FALSE.equals(binding.getEnabled())) {
                result.add(binding);
            }
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
        List<Object> currentValues = new ArrayList<Object>();
        currentValues.add(source);
        boolean expandedArray = false;
        for (String rawSegment : path.split("\\.")) {
            if (!hasText(rawSegment)) {
                return absent();
            }
            PathSegment segment = parsePathSegment(rawSegment.trim());
            if (segment == null) {
                return absent();
            }
            List<Object> nextValues = new ArrayList<Object>();
            for (Object current : currentValues) {
                if (appendPathValues(current, segment, nextValues)) {
                    expandedArray = true;
                }
            }
            if (nextValues.isEmpty()) {
                return expandedArray ? Collections.emptyList() : absent();
            }
            currentValues = nextValues;
        }
        return pathResult(currentValues);
    }

    private PathSegment parsePathSegment(String segment) {
        String name = segment;
        Integer index = absent();
        int bracket = segment.indexOf('[');
        if (bracket >= 0 && segment.endsWith("]")) {
            name = segment.substring(0, bracket);
            try {
                index = Integer.valueOf(segment.substring(bracket + 1, segment.length() - 1));
                if (index.intValue() < 0) {
                    return absent();
                }
            } catch (Exception ex) {
                return absent();
            }
        }
        if (!hasText(name) && index == null) {
            return absent();
        }
        return new PathSegment(name == null ? "" : name.trim(), index);
    }

    private boolean appendPathValues(Object source, PathSegment segment, List<Object> target) {
        if (source instanceof List<?> && hasText(segment.name)) {
            for (Object item : (List<?>) source) {
                appendSinglePathValue(item, segment, target);
            }
            return true;
        }
        appendSinglePathValue(source, segment, target);
        return false;
    }

    private void appendSinglePathValue(Object source, PathSegment segment, List<Object> target) {
        Object value = source;
        if (hasText(segment.name)) {
            if (!(source instanceof Map<?, ?>)) {
                return;
            }
            LookupResult lookup = lookupPathValue(castMap(source), segment.name);
            if (!lookup.present) {
                return;
            }
            value = lookup.value;
        }
        if (segment.index != null) {
            if (!(value instanceof List<?>)) {
                return;
            }
            List<?> list = (List<?>) value;
            if (segment.index.intValue() < list.size()) {
                target.add(list.get(segment.index.intValue()));
            }
            return;
        }
        target.add(value);
    }

    private Object pathResult(List<Object> values) {
        if (values == null || values.isEmpty()) {
            return absent();
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        List<Object> result = new ArrayList<Object>();
        for (Object value : values) {
            if (value instanceof List<?>) {
                result.addAll((List<?>) value);
            } else {
                result.add(value);
            }
        }
        return result;
    }

    private LookupResult lookupPathValue(Map<String, Object> source, String key) {
        if (source == null || key == null) {
            return LookupResult.missing();
        }
        if (source.containsKey(key)) {
            return LookupResult.found(source.get(key));
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return LookupResult.found(entry.getValue());
            }
        }
        return LookupResult.missing();
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
                    if (value instanceof BigDecimal) {
                        return value;
                    }
                    return BigDecimal.valueOf(((Number) value).doubleValue());
                }
                return new BigDecimal(String.valueOf(value).trim());
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
            if (value instanceof BigDecimal) {
                return new DoubleColumn((BigDecimal) value);
            }
            if (value instanceof Number) {
                return new DoubleColumn(BigDecimal.valueOf(((Number) value).doubleValue()));
            }
            return new DoubleColumn(new BigDecimal(String.valueOf(value).trim()));
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

    private long safeLong(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private <T> T absent() {
        return Optional.<T>empty().orElse(null);
    }

    private static final class PathSegment {
        private final String name;
        private final Integer index;

        private PathSegment(String name, Integer index) {
            this.name = name;
            this.index = index;
        }
    }

    private static final class LookupResult {
        private final boolean present;
        private final Object value;

        private LookupResult(boolean present, Object value) {
            this.present = present;
            this.value = value;
        }

        private static LookupResult found(Object value) {
            return new LookupResult(true, value);
        }

        private static LookupResult missing() {
            return new LookupResult(false, null);
        }
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
