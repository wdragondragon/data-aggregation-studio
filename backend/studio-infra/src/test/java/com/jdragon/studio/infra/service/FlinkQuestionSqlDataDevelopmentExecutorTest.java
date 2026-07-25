package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.infra.service.script.DataDevelopmentExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkQuestionSqlDataDevelopmentExecutorTest {

    @Test
    void shouldUseWorkerExecutionGatewayAndPreserveFlinkResult() {
        AtomicReference<FlinkSqlExecuteRequest> captured = new AtomicReference<FlinkSqlExecuteRequest>();
        FlinkQuestionResultView flinkResult = new FlinkQuestionResultView();
        flinkResult.setSql("SELECT * FROM m_7 LIMIT 10");
        flinkResult.setColumns(List.of("id"));
        flinkResult.setRows(List.of(Map.of("id", 1)));
        flinkResult.setWarnings(List.of("worker warning"));
        flinkResult.getSummary().put("executionMode", "embedded");
        FlinkQuestionSqlDataDevelopmentExecutor executor = new FlinkQuestionSqlDataDevelopmentExecutor(
                new ObjectMapper(), request -> {
                    captured.set(request);
                    return flinkResult;
                });
        DataDevelopmentExecutionContext context = new DataDevelopmentExecutionContext();
        context.setScriptType(ScriptType.FLINK_QUESTION_SQL);
        context.setContent("SELECT * FROM m_7");
        context.setMaxRows(10);
        context.setRuntimeContext(new LinkedHashMap<String, Object>(Map.of("runtimeClusterId", 50L)));
        context.setExecutionConfig(new LinkedHashMap<String, Object>(Map.of(
                "modelIds", List.of(7L), "scanMaxRows", 1000)));

        DataScriptExecutionResultView result = executor.execute(context);

        assertEquals(50L, captured.get().getRuntimeClusterId());
        assertEquals(List.of(7L), captured.get().getModelIds());
        assertEquals(1000, captured.get().getScanMaxRows());
        assertTrue(result.getSuccess());
        assertEquals(List.of("id"), result.getSqlResult().getColumns());
        assertEquals("embedded", result.getResultJson().get("executionMode"));
        assertEquals("worker warning", result.getLogs());
    }
}
