package com.jdragon.studio.test;

import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.infra.service.DataDevelopmentService;
import com.jdragon.studio.worker.runtime.DataDevelopmentNodeExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataDevelopmentNodeExecutorRegressionTest {

    @Test
    void shouldExecuteJavaDataScriptNodeWithArguments() {
        DataDevelopmentService service = mock(DataDevelopmentService.class);
        DataDevelopmentNodeExecutor executor = new DataDevelopmentNodeExecutor(service);

        WorkflowNodeDefinition definition = new WorkflowNodeDefinition();
        definition.setNodeCode("java_script_1");
        definition.setNodeName("Java Script");
        definition.setNodeType(NodeType.DATA_SCRIPT);
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("scriptId", 101L);
        config.put("scriptName", "demo.java");
        config.put("scriptType", "JAVA");
        config.put("arguments", "{\"batchSize\":100,\"source\":\"workflow\"}");
        definition.setConfig(config);

        DataScriptExecutionResultView executionResult = new DataScriptExecutionResultView();
        executionResult.setScriptType(ScriptType.JAVA);
        executionResult.setSuccess(true);
        executionResult.setStatus("SUCCESS");
        executionResult.setMessage("Java workflow script executed");
        executionResult.setLogs("script started\nscript finished");
        executionResult.getResultJson().put("rows", 12);

        when(service.executeScript(eq(101L), eq(null), any(Map.class), any(Map.class))).thenReturn(executionResult);

        Map<String, Object> runtimeContext = new LinkedHashMap<String, Object>();
        runtimeContext.put("workflowRunId", 9001L);

        Map<String, Object> result = executor.execute(definition, runtimeContext);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(service).executeScript(eq(101L), eq(null), argumentsCaptor.capture(), eq(runtimeContext));
        assertThat(argumentsCaptor.getValue()).containsEntry("batchSize", 100).containsEntry("source", "workflow");

        assertThat(result).containsEntry("scriptId", 101L);
        assertThat(result).containsEntry("scriptType", "JAVA");
        assertThat(result).containsEntry("message", "Java workflow script executed");
        assertThat(result).containsEntry("logs", "script started\nscript finished");
        assertThat(result).containsEntry("summary", executionResult.getResultJson());
        assertThat(result).containsEntry("resultJson", executionResult.getResultJson());
    }

    @Test
    void shouldExecutePythonDataScriptNodeWithArguments() {
        DataDevelopmentService service = mock(DataDevelopmentService.class);
        DataDevelopmentNodeExecutor executor = new DataDevelopmentNodeExecutor(service);

        WorkflowNodeDefinition definition = new WorkflowNodeDefinition();
        definition.setNodeCode("python_script_1");
        definition.setNodeName("Python Script");
        definition.setNodeType(NodeType.DATA_SCRIPT);
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("scriptId", 202L);
        config.put("scriptName", "demo.py");
        config.put("scriptType", "PYTHON");
        config.put("arguments", "{\"batchSize\":64,\"mode\":\"adhoc\"}");
        definition.setConfig(config);

        DataScriptExecutionResultView executionResult = new DataScriptExecutionResultView();
        executionResult.setScriptType(ScriptType.PYTHON);
        executionResult.setSuccess(true);
        executionResult.setStatus("SUCCESS");
        executionResult.setMessage("Python workflow script executed");
        executionResult.setLogs("python started\npython finished");
        executionResult.getResultJson().put("rows", 5);

        when(service.executeScript(eq(202L), eq(null), any(Map.class), any(Map.class))).thenReturn(executionResult);

        Map<String, Object> runtimeContext = new LinkedHashMap<String, Object>();
        runtimeContext.put("workflowRunId", 9002L);

        Map<String, Object> result = executor.execute(definition, runtimeContext);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(service).executeScript(eq(202L), eq(null), argumentsCaptor.capture(), eq(runtimeContext));
        assertThat(argumentsCaptor.getValue()).containsEntry("batchSize", 64).containsEntry("mode", "adhoc");

        assertThat(result).containsEntry("scriptId", 202L);
        assertThat(result).containsEntry("scriptType", "PYTHON");
        assertThat(result).containsEntry("message", "Python workflow script executed");
        assertThat(result).containsEntry("logs", "python started\npython finished");
        assertThat(result).containsEntry("summary", executionResult.getResultJson());
        assertThat(result).containsEntry("resultJson", executionResult.getResultJson());
    }
}
