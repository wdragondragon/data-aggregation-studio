package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.SqlStatementExecutionResultView;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.infra.service.script.DataDevelopmentExecutionContext;
import com.jdragon.studio.infra.service.script.DataDevelopmentScriptExecutor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FlinkQuestionSqlDataDevelopmentExecutor implements DataDevelopmentScriptExecutor {

    private final ObjectMapper objectMapper;
    private final ExecutionGateway executionGateway;

    public FlinkQuestionSqlDataDevelopmentExecutor(ObjectMapper objectMapper,
                                                   ExecutionGateway executionGateway) {
        this.objectMapper = objectMapper;
        this.executionGateway = executionGateway;
    }

    @Override
    public ScriptType getScriptType() {
        return ScriptType.FLINK_QUESTION_SQL;
    }

    @Override
    public DataScriptExecutionResultView execute(DataDevelopmentExecutionContext context) {
        long startedAt = System.currentTimeMillis();
        FlinkQuestionResultView flinkResult = executeRemote(context);
        SqlExecutionResultView sqlResult = toSqlResult(flinkResult, context, startedAt);

        DataScriptExecutionResultView result = new DataScriptExecutionResultView();
        result.setScriptType(ScriptType.FLINK_QUESTION_SQL);
        result.setSuccess(Boolean.TRUE);
        result.setStatus("SUCCESS");
        result.setMessage(sqlResult.getMessage());
        result.setExecutionMs(sqlResult.getExecutionMs());
        result.setLogs(String.join("\n", safeWarnings(flinkResult)));
        result.setSqlResult(sqlResult);
        result.setResultJson(toResultJson(flinkResult));
        return result;
    }

    private FlinkQuestionResultView executeRemote(DataDevelopmentExecutionContext context) {
        FlinkSqlExecuteRequest request = new FlinkSqlExecuteRequest();
        request.setRuntimeClusterId(resolveLong(context.getRuntimeContext().get("runtimeClusterId")));
        request.setSql(context.getContent());
        request.setMaxRows(context.getMaxRows());
        request.setModelIds(resolveModelIds(context));
        request.setScanMaxRows(resolveInteger(context, "scanMaxRows"));
        if (request.getModelIds().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "modelIds are required for 模型 Flink SQL execution");
        }
        try {
            return executionGateway.execute(request);
        } catch (StudioException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Worker Flink SQL execution failed: " + exception.getMessage(), exception);
        }
    }

    private SqlExecutionResultView toSqlResult(FlinkQuestionResultView flinkResult,
                                               DataDevelopmentExecutionContext context,
                                               long startedAt) {
        SqlStatementExecutionResultView statementResult = new SqlStatementExecutionResultView();
        statementResult.setStatementIndex(1);
        statementResult.setSql(flinkResult.getSql() == null ? context.getContent() : flinkResult.getSql());
        statementResult.setQuery(Boolean.TRUE);
        statementResult.setAffectedRows(0);
        statementResult.setColumns(safeColumns(flinkResult));
        statementResult.setRows(safeRows(flinkResult));
        statementResult.setExecutionMs(flinkResult.getExecutionMs());
        statementResult.setMessage(String.format("Query returned %d row(s)", safeRows(flinkResult).size()));
        statementResult.getSummary().putAll(safeSummary(flinkResult));
        statementResult.getSummary().put("rowCount", safeRows(flinkResult).size());

        SqlExecutionResultView sqlResult = new SqlExecutionResultView();
        sqlResult.setQuery(Boolean.TRUE);
        sqlResult.setStatementCount(1);
        sqlResult.setAffectedRows(0);
        sqlResult.setExecutionMs(flinkResult.getExecutionMs() == null
                ? Long.valueOf(System.currentTimeMillis() - startedAt)
                : flinkResult.getExecutionMs());
        sqlResult.setMessage("模型 Flink SQL executed successfully");
        sqlResult.setColumns(safeColumns(flinkResult));
        sqlResult.setRows(safeRows(flinkResult));
        sqlResult.getSummary().putAll(safeSummary(flinkResult));
        sqlResult.getSummary().put("rowCount", safeRows(flinkResult).size());
        sqlResult.getSummary().put("flinkSql", statementResult.getSql());
        sqlResult.getResults().add(statementResult);
        return sqlResult;
    }

    private Map<String, Object> toResultJson(FlinkQuestionResultView flinkResult) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.putAll(safeSummary(flinkResult));
        result.put("sql", flinkResult.getSql());
        result.put("modelRefs", objectMapper.convertValue(
                flinkResult.getModelRefs() == null ? new ArrayList<Object>() : flinkResult.getModelRefs(), Object.class));
        result.put("warnings", new ArrayList<String>(safeWarnings(flinkResult)));
        return result;
    }

    private List<String> safeColumns(FlinkQuestionResultView result) {
        return result.getColumns() == null ? new ArrayList<String>() : new ArrayList<String>(result.getColumns());
    }

    private List<Map<String, Object>> safeRows(FlinkQuestionResultView result) {
        return result.getRows() == null
                ? new ArrayList<Map<String, Object>>()
                : new ArrayList<Map<String, Object>>(result.getRows());
    }

    private List<String> safeWarnings(FlinkQuestionResultView result) {
        return result.getWarnings() == null ? new ArrayList<String>() : new ArrayList<String>(result.getWarnings());
    }

    private Map<String, Object> safeSummary(FlinkQuestionResultView result) {
        return result.getSummary() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(result.getSummary());
    }

    private List<Long> resolveModelIds(DataDevelopmentExecutionContext context) {
        Object candidate = context.getExecutionConfig().get("modelIds");
        if (candidate == null) {
            candidate = context.getArguments().get("modelIds");
        }
        return toLongList(candidate);
    }

    private Integer resolveInteger(DataDevelopmentExecutionContext context, String key) {
        Object candidate = context.getExecutionConfig().get(key);
        if (candidate == null) {
            candidate = context.getArguments().get(key);
        }
        Long value = resolveLong(candidate);
        return value == null ? null : value.intValue();
    }

    private List<Long> toLongList(Object value) {
        List<Long> result = new ArrayList<Long>();
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                Long resolved = resolveLong(item);
                if (resolved != null) {
                    result.add(resolved);
                }
            }
            return result;
        }
        if (value instanceof String) {
            String[] parts = ((String) value).split(",");
            for (String part : parts) {
                Long resolved = resolveLong(part);
                if (resolved != null) {
                    result.add(resolved);
                }
            }
            return result;
        }
        Long single = resolveLong(value);
        if (single != null) {
            result.add(single);
        }
        return result;
    }

    private Long resolveLong(Object value) {
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        if (value instanceof String && StringUtils.hasText((String) value)) {
            try {
                return Long.valueOf(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @FunctionalInterface
    public interface ExecutionGateway {
        FlinkQuestionResultView execute(FlinkSqlExecuteRequest request);
    }
}
