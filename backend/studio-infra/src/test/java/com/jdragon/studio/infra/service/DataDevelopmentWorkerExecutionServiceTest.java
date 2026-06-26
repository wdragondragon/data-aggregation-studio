package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataDevelopmentWorkerExecutionServiceTest {

    @Test
    void shouldCreateDataScriptTestTaskAndMapCompletedPayload() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        DataDevelopmentWorkerExecutionService service = new DataDevelopmentWorkerExecutionService(
                dispatchTaskMapper,
                runRecordMapper,
                workerAuthorizationService,
                securityService);

        when(securityService.currentProjectId()).thenReturn(10L);
        when(securityService.currentUserId()).thenReturn(20L);
        doAnswer(invocation -> {
            DispatchTaskEntity task = invocation.getArgument(0);
            task.setId(1001L);
            return 1;
        }).when(dispatchTaskMapper).insert(org.mockito.ArgumentMatchers.any(DispatchTaskEntity.class));

        DispatchTaskEntity completedTask = new DispatchTaskEntity();
        completedTask.setId(1001L);
        completedTask.setRunRecordId(2001L);
        completedTask.setStatus("SUCCESS");
        completedTask.setPayloadJson(successPayload());
        when(dispatchTaskMapper.selectById(1001L)).thenReturn(completedTask);

        RunRecordEntity runRecord = new RunRecordEntity();
        runRecord.setId(2001L);
        runRecord.setStatus("SUCCESS");
        runRecord.setLogStatus("READY");
        runRecord.setStartedAt(LocalDateTime.now().minusSeconds(2));
        runRecord.setEndedAt(LocalDateTime.now());
        when(runRecordMapper.selectById(2001L)).thenReturn(runRecord);

        DataDevelopmentScriptEntity script = new DataDevelopmentScriptEntity();
        script.setId(301L);
        script.setTenantId("default");
        script.setProjectId(10L);
        script.setFileName("demo.java");
        script.setScriptType(ScriptType.JAVA.name());

        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("batchSize", 100);
        DataScriptExecutionResultView result = service.executeSavedScript(script, ScriptType.JAVA, arguments, 50, 1);

        verify(workerAuthorizationService).assertProjectHasAvailableWorker("default", 10L);
        ArgumentCaptor<DispatchTaskEntity> taskCaptor = ArgumentCaptor.forClass(DispatchTaskEntity.class);
        verify(dispatchTaskMapper).insert(taskCaptor.capture());
        DispatchTaskEntity insertedTask = taskCaptor.getValue();
        assertThat(insertedTask.getExecutionType()).isEqualTo(DispatchExecutionType.DATA_SCRIPT_TEST.name());
        assertThat(insertedTask.getNodeCode()).startsWith("data_script_test_301_");
        assertThat(insertedTask.getStatus()).isEqualTo("QUEUED");
        assertThat(insertedTask.getTriggeredByUserId()).isEqualTo(20L);
        assertThat(insertedTask.getProjectId()).isEqualTo(10L);
        assertThat(insertedTask.getPayloadJson()).containsEntry("nodeType", NodeType.DATA_SCRIPT.name());

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) insertedTask.getPayloadJson().get("config");
        assertThat(config).containsEntry("scriptId", 301L)
                .containsEntry("scriptName", "demo.java")
                .containsEntry("scriptType", "JAVA")
                .containsEntry("maxRows", 50);
        assertThat(config.get("arguments")).isEqualTo(arguments);

        verify(dispatchTaskMapper).selectById(eq(1001L));
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getDispatchTaskId()).isEqualTo(1001L);
        assertThat(result.getRunRecordId()).isEqualTo(2001L);
        assertThat(result.getLogStatus()).isEqualTo("READY");
        assertThat(result.getScriptType()).isEqualTo(ScriptType.JAVA);
        assertThat(result.getMessage()).isEqualTo("Java script executed successfully");
        assertThat(result.getLogs()).isEqualTo("script log");
        assertThat(result.getExecutionMs()).isEqualTo(35L);
        assertThat(result.getResultJson()).containsEntry("rows", 12);
    }

    private Map<String, Object> successPayload() {
        Map<String, Object> resultJson = new LinkedHashMap<String, Object>();
        resultJson.put("rows", 12);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", "SUCCESS");
        payload.put("scriptType", "JAVA");
        payload.put("message", "Java script executed successfully");
        payload.put("logs", "script log");
        payload.put("durationMs", 35L);
        payload.put("resultJson", resultJson);
        return payload;
    }
}
