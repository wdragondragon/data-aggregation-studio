package com.jdragon.studio.worker.runtime;

import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.infra.service.DataDevelopmentService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class DataDevelopmentNodeExecutor implements NodeExecutor {

    private final DataDevelopmentService dataDevelopmentService;

    public DataDevelopmentNodeExecutor(DataDevelopmentService dataDevelopmentService) {
        this.dataDevelopmentService = dataDevelopmentService;
    }

    @Override
    public boolean supports(WorkflowNodeDefinition definition) {
        return definition.getNodeType() == NodeType.DATA_SCRIPT;
    }

    @Override
    public Map<String, Object> execute(WorkflowNodeDefinition definition, Map<String, Object> runtimeContext) {
        Map<String, Object> config = definition.getConfig() == null
                ? new LinkedHashMap<String, Object>()
                : definition.getConfig();
        Long scriptId = parseLong(config.get("scriptId"));
        if (scriptId == null) {
            throw new IllegalStateException("DATA_SCRIPT node is missing scriptId");
        }
        Integer maxRows = parseInteger(config.get("maxRows"));
        long startedAt = System.currentTimeMillis();
        log.info("Executing data script node {} with scriptId={}", definition.getNodeCode(), scriptId);
        Map<String, Object> arguments = resolveArguments(config.get("arguments"));
        DataScriptExecutionResultView executionResult = dataDevelopmentService.executeScript(scriptId, maxRows, arguments, runtimeContext);
        long endedAt = System.currentTimeMillis();
        emitExecutionLogs(definition, executionResult);
        if (!Boolean.TRUE.equals(executionResult.getSuccess())) {
            throw new IllegalStateException(executionResult.getMessage());
        }
        log.info("Completed data script node {} with scriptId={} in {} ms", definition.getNodeCode(), scriptId, endedAt - startedAt);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "SUCCESS");
        result.put("nodeType", NodeType.DATA_SCRIPT.name());
        result.put("startedAt", startedAt);
        result.put("endedAt", endedAt);
        result.put("durationMs", endedAt - startedAt);
        result.put("scriptId", scriptId);
        result.put("scriptName", config.get("scriptName"));
        result.put("scriptType", executionResult.getScriptType() == null ? config.get("scriptType") : executionResult.getScriptType().name());
        result.put("datasourceName", executionResult.getDatasourceName());
        result.put("message", buildMessage(config, executionResult, endedAt - startedAt));
        result.put("logs", executionResult.getLogs());
        result.put("resultJson", executionResult.getResultJson());
        if (executionResult.getSqlResult() != null) {
            result.put("query", executionResult.getSqlResult().getQuery());
            result.put("statementCount", executionResult.getSqlResult().getStatementCount());
            result.put("affectedRows", executionResult.getSqlResult().getAffectedRows());
            result.put("rowCount", executionResult.getSqlResult().getRows() == null ? 0 : executionResult.getSqlResult().getRows().size());
            result.put("columnCount", executionResult.getSqlResult().getColumns() == null ? 0 : executionResult.getSqlResult().getColumns().size());
            result.put("summary", executionResult.getSqlResult().getSummary());
        } else {
            result.put("summary", executionResult.getResultJson());
        }
        return result;
    }

    private void emitExecutionLogs(WorkflowNodeDefinition definition, DataScriptExecutionResultView executionResult) {
        if (executionResult == null || executionResult.getLogs() == null || executionResult.getLogs().trim().isEmpty()) {
            return;
        }
        String[] lines = executionResult.getLogs().split("\\r?\\n");
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            log.info("[DATA_SCRIPT:{}] {}", definition.getNodeCode(), line);
        }
    }

    private String buildMessage(Map<String, Object> config, DataScriptExecutionResultView executionResult, long durationMs) {
        String scriptName = config.get("scriptName") == null ? "Data script" : String.valueOf(config.get("scriptName"));
        if (executionResult.getSqlResult() != null && Boolean.TRUE.equals(executionResult.getSqlResult().getQuery())) {
            int rowCount = executionResult.getSqlResult().getRows() == null ? 0 : executionResult.getSqlResult().getRows().size();
            return String.format("%s completed in %d ms with %d row(s) returned", scriptName, durationMs, rowCount);
        }
        if (executionResult.getSqlResult() == null) {
            return executionResult.getMessage() == null
                    ? String.format("%s completed in %d ms", scriptName, durationMs)
                    : executionResult.getMessage();
        }
        return String.format("%s completed in %d ms, affected rows: %d",
                scriptName, durationMs, executionResult.getSqlResult().getAffectedRows() == null ? 0 : executionResult.getSqlResult().getAffectedRows());
    }

    private Long parseLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Long.parseLong(((String) value).trim());
        }
        return null;
    }

    private Integer parseInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Integer.parseInt(((String) value).trim());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveArguments(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            Object parsed = JSON.parse((String) value);
            if (parsed instanceof Map) {
                return new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
            }
        }
        return new LinkedHashMap<String, Object>();
    }
}
