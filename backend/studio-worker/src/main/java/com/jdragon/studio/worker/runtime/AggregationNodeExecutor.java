package com.jdragon.studio.worker.runtime;

import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.core.enums.State;
import com.jdragon.aggregation.core.job.JobContainer;
import com.jdragon.aggregation.core.plugin.spi.reporter.JobPointReporter;
import com.jdragon.aggregation.core.statistics.communication.Communication;
import com.jdragon.aggregation.core.statistics.communication.CommunicationTool;
import com.jdragon.aggregation.core.statistics.communication.RunStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AggregationNodeExecutor implements NodeExecutor {

    @Override
    public boolean supports(WorkflowNodeDefinition definition) {
        if (definition.getNodeType() == NodeType.COLLECTION_TASK) {
            return true;
        }
        return definition.getNodeType() == NodeType.ETL_SINGLE
                || definition.getNodeType() == NodeType.FUSION
                || definition.getNodeType() == NodeType.CONSISTENCY;
    }

    @Override
    public Map<String, Object> execute(WorkflowNodeDefinition definition, Map<String, Object> runtimeContext) {
        Map<String, Object> config = definition.getConfig() == null
                ? Collections.<String, Object>emptyMap()
                : definition.getConfig();
        long startedAt = System.currentTimeMillis();
        JobContainer container = createJobContainer(config);
        if (runtimeContext != null) {
            for (Map.Entry<String, Object> entry : runtimeContext.entrySet()) {
                container.setRunContext(entry.getKey(), entry.getValue());
            }
        }
        container.start();
        long endedAt = System.currentTimeMillis();
        Communication communication = resolveCommunication(container);
        State jobState = communication == null ? null : communication.getState();
        boolean success = State.SUCCEEDED.equals(jobState);
        Throwable failure = communication == null ? null : communication.getThrowable();
        RunStatus runStatus = resolveRunStatus(container);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", success ? "SUCCESS" : "FAILED");
        result.put("nodeType", definition.getNodeType().name());
        result.put("startedAt", startedAt);
        result.put("endedAt", endedAt);
        result.put("durationMs", endedAt - startedAt);
        result.put("jobState", jobState == null ? null : jobState.name());
        result.put("message", buildMessage(definition, config, endedAt - startedAt, success, failure));
        if (!success && failure != null) {
            result.put("error", failure.getMessage());
            result.put("exceptionType", failure.getClass().getName());
        }
        result.put("summary", buildSummary(config, communication, runStatus, jobState));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSummary(Map<String, Object> config,
                                             Communication communication,
                                             RunStatus runStatus,
                                             State jobState) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        Map<String, Object> reader = valueAsMap(config.get("reader"));
        Map<String, Object> writer = valueAsMap(config.get("writer"));
        List<Map<String, Object>> transformers = valueAsList(config.get("transformer"));
        summary.put("readerType", reader.get("type"));
        summary.put("writerType", writer.get("type"));
        summary.put("transformerCount", transformers.size());
        summary.put("source", summarizeEndpoint(reader));
        summary.put("target", summarizeEndpoint(writer));
        summary.put("jobState", jobState == null ? null : jobState.name());
        if (runStatus != null) {
            long readSucceedRecords = communication == null ? 0L : communication.getLongCounter(CommunicationTool.READ_SUCCEED_RECORDS);
            long readFailedRecords = communication == null ? 0L : communication.getLongCounter(CommunicationTool.READ_FAILED_RECORDS);
            long writeFailedRecords = communication == null ? 0L : communication.getLongCounter(CommunicationTool.WRITE_FAILED_RECORDS);
            long writeSucceedRecords = communication == null ? 0L : CommunicationTool.getWriteSucceedRecords(communication);
            long totalReadRecords = communication == null ? runStatus.getTotal() : CommunicationTool.getTotalReadRecords(communication);
            long totalErrorRecords = communication == null ? runStatus.getError() : CommunicationTool.getTotalErrorRecords(communication);
            long successRecords = Math.max(0L, readSucceedRecords - writeFailedRecords);
            long transformerSuccess = runStatus.getTransformerSuccess();
            long transformerError = runStatus.getTransformerError();
            long transformerFilter = runStatus.getTransformerFilter();
            summary.put("totalRecords", runStatus.getTotal());
            summary.put("totalBytes", runStatus.getBytes());
            summary.put("errorRecords", runStatus.getError());
            summary.put("errorBytes", runStatus.getBytesError());
            summary.put("collectedRecords", totalReadRecords);
            summary.put("successRecords", successRecords);
            summary.put("readSucceedRecords", readSucceedRecords);
            summary.put("readFailedRecords", readFailedRecords);
            summary.put("writeSucceedRecords", writeSucceedRecords);
            summary.put("writeFailedRecords", writeFailedRecords);
            summary.put("failedRecords", totalErrorRecords);
            summary.put("transformerTotalRecords", transformerSuccess + transformerError + transformerFilter);
            summary.put("transformerSuccessRecords", transformerSuccess);
            summary.put("transformerFailedRecords", transformerError);
            summary.put("transformerFilterRecords", transformerFilter);
            summary.put("transformerSuccess", transformerSuccess);
            summary.put("transformerError", transformerError);
            summary.put("transformerFilter", transformerFilter);
            summary.put("recordSpeed", runStatus.getRecordSpeed());
            summary.put("byteSpeed", runStatus.getByteSpeed());
        }
        if ("fusion".equals(String.valueOf(reader.get("type")))) {
            Map<String, Object> readerConfig = valueAsMap(reader.get("config"));
            List<Map<String, Object>> sources = valueAsList(readerConfig.get("sources"));
            Map<String, Object> join = valueAsMap(readerConfig.get("join"));
            summary.put("sourceCount", sources.size());
            summary.put("sourceAliases", extractAliases(sources));
            summary.put("joinKeys", join.get("keys"));
            summary.put("joinType", join.get("type"));
        }
        return summary;
    }

    private String buildMessage(WorkflowNodeDefinition definition,
                                Map<String, Object> config,
                                long durationMs,
                                boolean success,
                                Throwable failure) {
        Map<String, Object> reader = valueAsMap(config.get("reader"));
        Map<String, Object> writer = valueAsMap(config.get("writer"));
        String readerType = String.valueOf(reader.get("type"));
        String writerType = String.valueOf(writer.get("type"));
        if (success) {
            return String.format("%s node completed in %d ms (%s -> %s)",
                    definition.getNodeType().name(), durationMs, readerType, writerType);
        }
        return String.format("%s node failed in %d ms (%s -> %s): %s",
                definition.getNodeType().name(),
                durationMs,
                readerType,
                writerType,
                failure == null || failure.getMessage() == null ? "JobContainer reported FAILED" : failure.getMessage());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summarizeEndpoint(Map<String, Object> endpoint) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("type", endpoint.get("type"));
        Map<String, Object> endpointConfig = valueAsMap(endpoint.get("config"));
        if (endpointConfig.containsKey("table")) {
            summary.put("table", endpointConfig.get("table"));
        }
        if (endpointConfig.containsKey("columns")) {
            List<Object> columns = (List<Object>) endpointConfig.get("columns");
            summary.put("columnCount", columns == null ? 0 : columns.size());
        }
        if (endpointConfig.containsKey("querySql")) {
            summary.put("querySql", endpointConfig.get("querySql"));
        }
        return summary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> valueAsMap(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        }
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> valueAsList(Object value) {
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return Collections.emptyList();
    }

    private List<String> extractAliases(List<Map<String, Object>> sources) {
        java.util.ArrayList<String> aliases = new java.util.ArrayList<String>();
        for (Map<String, Object> source : sources) {
            Object id = source.get("id");
            if (id != null) {
                aliases.add(String.valueOf(id));
            }
        }
        return aliases;
    }

    protected JobContainer createJobContainer(Map<String, Object> config) {
        return new JobContainer(Configuration.from(config));
    }

    private Communication resolveCommunication(JobContainer container) {
        JobPointReporter reporter = container.getJobPointReporter();
        return reporter == null ? null : reporter.getTrackCommunication();
    }

    private RunStatus resolveRunStatus(JobContainer container) {
        JobPointReporter reporter = container.getJobPointReporter();
        return reporter == null ? null : reporter.openReport();
    }
}

