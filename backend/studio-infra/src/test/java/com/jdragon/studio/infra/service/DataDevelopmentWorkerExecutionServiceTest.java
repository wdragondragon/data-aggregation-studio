package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.request.DataScriptExecutionRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DataDevelopmentWorkerExecutionServiceTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(DispatchTaskEntity.class);
        initTableInfo(RunRecordEntity.class);
    }

    @Test
    void shouldCreateDataScriptTestTaskAndMapCompletedPayload() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        DispatchProtectedPayloadService protectedPayloadService = protectedPayloadService(objectMapper);
        DataDevelopmentWorkerExecutionService service = new DataDevelopmentWorkerExecutionService(
                dispatchTaskMapper,
                runRecordMapper,
                workerAuthorizationService,
                securityService,
                objectMapper,
                protectedPayloadService);
        RuntimeResourceRevisionService revisionService = mock(RuntimeResourceRevisionService.class);
        RuntimeValidationService runtimeValidationService = mock(RuntimeValidationService.class);
        service.setRuntimeResourceRevisionService(revisionService);
        service.setRuntimeValidationService(runtimeValidationService);

        when(securityService.currentProjectId()).thenReturn(10L);
        when(securityService.currentUserId()).thenReturn(20L);
        when(revisionService.scriptRevision(301L)).thenReturn("script-revision-301");
        when(dispatchTaskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        doAnswer(invocation -> {
            DispatchTaskEntity task = invocation.getArgument(0);
            task.setId(1001L);
            return 1;
        }).when(dispatchTaskMapper).insert(org.mockito.ArgumentMatchers.any(DispatchTaskEntity.class));

        DispatchTaskEntity statusTask = new DispatchTaskEntity();
        statusTask.setId(1001L);
        statusTask.setRunRecordId(2001L);
        statusTask.setStatus("SUCCESS");

        DispatchTaskEntity completedTask = new DispatchTaskEntity();
        completedTask.setId(1001L);
        completedTask.setRunRecordId(2001L);
        completedTask.setStatus("SUCCESS");
        completedTask.setPayloadJson(successPayload());
        when(dispatchTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(statusTask, completedTask);

        RunRecordEntity runRecord = new RunRecordEntity();
        runRecord.setId(2001L);
        runRecord.setStatus("SUCCESS");
        runRecord.setLogStatus("READY");
        runRecord.setStartedAt(LocalDateTime.now().minusSeconds(2));
        runRecord.setEndedAt(LocalDateTime.now());
        when(runRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(runRecord);

        DataDevelopmentScriptEntity script = new DataDevelopmentScriptEntity();
        script.setId(301L);
        script.setTenantId("default");
        script.setProjectId(10L);
        script.setRuntimeClusterId(46L);
        script.setFileName("demo.java");
        script.setScriptType(ScriptType.JAVA.name());

        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("batchSize", 100);
        arguments.put("accessToken", "script-secret-token");
        DataScriptExecutionResultView result = service.executeSavedScript(
                script, ScriptType.JAVA, 50L, arguments, null, 50, 1);

        verifyNoInteractions(workerAuthorizationService);
        verifyNoInteractions(runtimeValidationService);
        ArgumentCaptor<DispatchTaskEntity> taskCaptor = ArgumentCaptor.forClass(DispatchTaskEntity.class);
        verify(dispatchTaskMapper).insert(taskCaptor.capture());
        DispatchTaskEntity insertedTask = taskCaptor.getValue();
        assertThat(insertedTask.getExecutionType()).isEqualTo(DispatchExecutionType.DATA_SCRIPT_TEST.name());
        assertThat(insertedTask.getNodeCode()).isEqualTo("data_script_test_301");
        assertThat(insertedTask.getStatus()).isEqualTo("QUEUED");
        assertThat(insertedTask.getTriggeredByUserId()).isEqualTo(20L);
        assertThat(insertedTask.getProjectId()).isEqualTo(10L);
        assertThat(insertedTask.getTargetClusterId()).isEqualTo(50L);
        assertThat(insertedTask.getResourceRevision()).isEqualTo("script-revision-301");
        assertThat(insertedTask.getPayloadJson()).containsEntry("nodeType", NodeType.DATA_SCRIPT.name());

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) insertedTask.getPayloadJson().get("config");
        assertThat(config).containsOnly(Map.entry("scriptId", 301L));
        assertThat(insertedTask.getPayloadJson().toString()).doesNotContain("script-secret-token", "accessToken");
        assertThat(insertedTask.getProtectedPayloadCiphertext()).isNotBlank()
                .doesNotContain("script-secret-token");
        Map<String, Object> protectedConfig = protectedPayloadService.unprotect(
                insertedTask.getProtectedPayloadCiphertext());
        assertThat(protectedConfig).containsEntry("maxRows", 50);
        assertThat(protectedConfig.get("arguments")).isEqualTo(arguments);

        ArgumentCaptor<LambdaQueryWrapper<DispatchTaskEntity>> taskQueryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dispatchTaskMapper, times(2)).selectOne(taskQueryCaptor.capture());
        List<LambdaQueryWrapper<DispatchTaskEntity>> taskQueries = taskQueryCaptor.getAllValues();
        assertThat(sqlSelect(taskQueries.get(0)))
                .contains("id", "status", "run_record_id")
                .doesNotContain("payload_json");
        assertThat(sqlSelect(taskQueries.get(1)))
                .contains("id", "status", "run_record_id", "payload_json");

        ArgumentCaptor<LambdaQueryWrapper<RunRecordEntity>> runQueryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runRecordMapper).selectOne(runQueryCaptor.capture());
        assertThat(sqlSelect(runQueryCaptor.getValue()))
                .contains("id", "log_status", "message", "started_at", "ended_at")
                .doesNotContain("payload_json", "result_json", "log_file_path", "log_object_key", "log_object_bucket");

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
        assertThat(result.getSqlResult()).isNotNull();
        assertThat(result.getSqlResult().getColumns()).containsExactly("id");
        assertThat(result.getSqlResult().getRows()).containsExactly(Map.of("id", 1));
    }

    @Test
    void shouldRejectDispatchWithoutExplicitRuntimeCluster() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        DataDevelopmentWorkerExecutionService service = new DataDevelopmentWorkerExecutionService(
                dispatchTaskMapper,
                mock(RunRecordMapper.class),
                mock(WorkerAuthorizationService.class),
                mock(StudioSecurityService.class),
                new ObjectMapper(),
                protectedPayloadService(new ObjectMapper()));
        DataDevelopmentScriptEntity script = new DataDevelopmentScriptEntity();
        script.setId(301L);
        script.setTenantId("default");
        script.setProjectId(10L);
        script.setFileName("demo.py");

        assertThatThrownBy(() -> service.executeSavedScript(
                script, ScriptType.PYTHON, null, null, null, null, 1))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("runtimeClusterId is required");
        verifyNoInteractions(dispatchTaskMapper);
    }

    @Test
    void shouldRejectSavedScriptWhenItsPreviousDispatchIsActive() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        DataDevelopmentWorkerExecutionService service = new DataDevelopmentWorkerExecutionService(
                dispatchTaskMapper,
                mock(RunRecordMapper.class),
                mock(WorkerAuthorizationService.class),
                securityService,
                new ObjectMapper(),
                protectedPayloadService(new ObjectMapper()));
        when(securityService.currentProjectId()).thenReturn(10L);
        when(securityService.currentUserId()).thenReturn(20L);
        when(dispatchTaskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        DataDevelopmentScriptEntity script = new DataDevelopmentScriptEntity();
        script.setId(301L);
        script.setTenantId("default");
        script.setProjectId(10L);
        script.setRuntimeClusterId(46L);
        script.setFileName("demo.py");
        script.setScriptType(ScriptType.PYTHON.name());

        assertThatThrownBy(() -> service.executeSavedScript(
                script, ScriptType.PYTHON, 46L, null, null, null, 1))
                .isInstanceOf(StudioException.class)
                .hasMessage("Data script already has an active run");
        verify(dispatchTaskMapper).selectCount(any(LambdaQueryWrapper.class));
        verifyNoMoreInteractions(dispatchTaskMapper);
    }

    @Test
    void shouldProtectInlineScriptContentAndArgumentsOutsidePublicPayload() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        DispatchProtectedPayloadService protectedPayloadService = protectedPayloadService(objectMapper);
        DataDevelopmentWorkerExecutionService service = new DataDevelopmentWorkerExecutionService(
                dispatchTaskMapper,
                mock(RunRecordMapper.class),
                mock(WorkerAuthorizationService.class),
                securityService,
                objectMapper,
                protectedPayloadService);
        when(securityService.currentTenantId()).thenReturn("tenant-a");
        when(securityService.currentUserId()).thenReturn(20L);
        doAnswer(invocation -> {
            DispatchTaskEntity task = invocation.getArgument(0);
            task.setId(1002L);
            return 1;
        }).when(dispatchTaskMapper).insert(any(DispatchTaskEntity.class));
        DispatchTaskEntity statusTask = new DispatchTaskEntity();
        statusTask.setId(1002L);
        statusTask.setStatus("SUCCESS");
        DispatchTaskEntity completedTask = new DispatchTaskEntity();
        completedTask.setId(1002L);
        completedTask.setStatus("SUCCESS");
        completedTask.setPayloadJson(Map.of("status", "SUCCESS", "scriptType", "SQL"));
        when(dispatchTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(statusTask, completedTask);

        DataScriptExecutionRequest request = new DataScriptExecutionRequest();
        request.setRuntimeClusterId(50L);
        request.setScriptType(ScriptType.SQL);
        request.setDatasourceId(301L);
        request.setContent("select 'inline-secret'");
        request.setArguments(Map.of("accessToken", "inline-token"));
        request.setExecutionConfig(Map.of("sessionSecret", "session-token"));
        request.setMaxRows(20);

        service.executeInlineScript(request, 10L, 1);

        ArgumentCaptor<DispatchTaskEntity> taskCaptor = ArgumentCaptor.forClass(DispatchTaskEntity.class);
        verify(dispatchTaskMapper).insert(taskCaptor.capture());
        DispatchTaskEntity insertedTask = taskCaptor.getValue();
        String publicPayload = insertedTask.getPayloadJson().toString();
        assertThat(publicPayload).doesNotContain("inline-secret", "inline-token", "session-token", "accessToken");
        assertThat(insertedTask.getPayloadJson()).containsEntry("protectedInput", Boolean.TRUE);
        Map<String, Object> protectedConfig = protectedPayloadService.unprotect(
                insertedTask.getProtectedPayloadCiphertext());
        assertThat(protectedConfig).containsEntry("content", "select 'inline-secret'")
                .containsEntry("maxRows", 20);
        assertThat(protectedConfig.get("arguments")).isEqualTo(Map.of("accessToken", "inline-token"));
    }

    private String sqlSelect(LambdaQueryWrapper<?> wrapper) {
        return String.valueOf(wrapper.getSqlSelect()).toLowerCase(Locale.ROOT);
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private DispatchProtectedPayloadService protectedPayloadService(ObjectMapper objectMapper) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setEncryptionSecret("dispatch-protected-payload-test-secret");
        return new DispatchProtectedPayloadService(new EncryptionService(properties), objectMapper);
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
        Map<String, Object> sqlResult = new LinkedHashMap<String, Object>();
        sqlResult.put("query", true);
        sqlResult.put("columns", List.of("id"));
        sqlResult.put("rows", List.of(Map.of("id", 1)));
        payload.put("sqlResult", sqlResult);
        return payload;
    }
}
