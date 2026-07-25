package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.request.DataScriptExecutionRequest;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class DataDevelopmentWorkerExecutionService {

    private static final int DEFAULT_WAIT_TIMEOUT_SECONDS = 120;
    private static final int MAX_WAIT_TIMEOUT_SECONDS = 300;
    private static final long POLL_INTERVAL_MILLIS = 500L;

    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final WorkerAuthorizationService workerAuthorizationService;
    private final StudioSecurityService securityService;
    private final ObjectMapper objectMapper;
    private final DispatchProtectedPayloadService protectedPayloadService;
    private RuntimeValidationService runtimeValidationService;
    private RuntimeResourceRevisionService runtimeResourceRevisionService;

    public DataDevelopmentWorkerExecutionService(DispatchTaskMapper dispatchTaskMapper,
                                                 RunRecordMapper runRecordMapper,
                                                 WorkerAuthorizationService workerAuthorizationService,
                                                 StudioSecurityService securityService,
                                                 ObjectMapper objectMapper,
                                                 DispatchProtectedPayloadService protectedPayloadService) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.workerAuthorizationService = workerAuthorizationService;
        this.securityService = securityService;
        this.objectMapper = objectMapper;
        this.protectedPayloadService = protectedPayloadService;
    }

    @Autowired
    void setRuntimeValidationService(RuntimeValidationService runtimeValidationService) {
        this.runtimeValidationService = runtimeValidationService;
    }

    @Autowired
    void setRuntimeResourceRevisionService(RuntimeResourceRevisionService runtimeResourceRevisionService) {
        this.runtimeResourceRevisionService = runtimeResourceRevisionService;
    }

    public DataScriptExecutionResultView executeSavedScript(DataDevelopmentScriptEntity script,
                                                            ScriptType scriptType,
                                                            Long runtimeClusterId,
                                                            Map<String, Object> arguments,
                                                            Map<String, Object> executionConfig,
                                                            Integer maxRows,
                                                            Integer waitTimeoutSeconds) {
        Long runtimeProjectId = securityService.currentProjectId();
        if (runtimeProjectId == null) {
            runtimeProjectId = script.getProjectId();
        }
        if (runtimeValidationService != null
                && java.util.Objects.equals(script.getRuntimeClusterId(), runtimeClusterId)) {
            runtimeValidationService.assertResourceValid(StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT, script.getId());
        }
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setTenantId(script.getTenantId());
        task.setProjectId(runtimeProjectId);
        task.setExecutionType(DispatchExecutionType.DATA_SCRIPT_TEST.name());
        task.setNodeCode("data_script_test_" + script.getId() + "_" + System.currentTimeMillis());
        task.setStatus("QUEUED");
        task.setTargetClusterId(runtimeClusterId);
        task.setResourceRevision(runtimeResourceRevisionService == null
                ? (script.getUpdatedAt() == null ? null : script.getUpdatedAt().toString())
                : runtimeResourceRevisionService.scriptRevision(script.getId()));
        task.setAttempts(0);
        task.setMaxRetries(0);
        task.setTriggeredByUserId(securityService.currentUserId());
        task.setPayloadJson(buildPayload(script, scriptType, runtimeClusterId, runtimeProjectId));
        task.setProtectedPayloadCiphertext(protectedPayloadService.protect(
                buildProtectedConfig(arguments, executionConfig, maxRows)));
        insertDispatchTask(task);

        return waitForCompletion(task, scriptType, waitTimeoutSeconds);
    }

    public DataScriptExecutionResultView executeInlineScript(DataScriptExecutionRequest request,
                                                             Long runtimeProjectId,
                                                             Integer waitTimeoutSeconds) {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setTenantId(securityService.currentTenantId());
        task.setProjectId(runtimeProjectId);
        task.setExecutionType(DispatchExecutionType.DATA_SCRIPT_TEST.name());
        task.setNodeCode("data_script_inline_" + System.currentTimeMillis());
        task.setStatus("QUEUED");
        task.setTargetClusterId(request.getRuntimeClusterId());
        task.setAttempts(0);
        task.setMaxRetries(0);
        task.setTriggeredByUserId(securityService.currentUserId());

        LinkedHashMap<String, Object> protectedConfig = new LinkedHashMap<String, Object>();
        protectedConfig.put("inline", Boolean.TRUE);
        protectedConfig.put("scriptType", request.getScriptType().name());
        protectedConfig.put("datasourceId", request.getDatasourceId());
        protectedConfig.put("environmentId", request.getEnvironmentId());
        protectedConfig.put("content", request.getContent());
        protectedConfig.put("arguments", request.getArguments());
        protectedConfig.put("executionConfig", request.getExecutionConfig());
        protectedConfig.put("maxRows", request.getMaxRows());

        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executionType", DispatchExecutionType.DATA_SCRIPT_TEST.name());
        payload.put("nodeType", NodeType.DATA_SCRIPT.name());
        payload.put("projectId", runtimeProjectId);
        payload.put("runtimeClusterId", request.getRuntimeClusterId());
        payload.put("protectedInput", Boolean.TRUE);
        payload.put("scriptType", request.getScriptType().name());
        task.setPayloadJson(payload);
        task.setProtectedPayloadCiphertext(protectedPayloadService.protect(protectedConfig));
        insertDispatchTask(task);
        return waitForCompletion(task, request.getScriptType(), waitTimeoutSeconds);
    }

    private void insertDispatchTask(DispatchTaskEntity task) {
        if (task == null || task.getTargetClusterId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "runtimeClusterId is required before a script can be dispatched");
        }
        dispatchTaskMapper.insert(task);
    }

    private Map<String, Object> buildPayload(DataDevelopmentScriptEntity script,
                                             ScriptType scriptType,
                                             Long runtimeClusterId,
                                             Long runtimeProjectId) {
        LinkedHashMap<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("scriptId", script.getId());

        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executionType", DispatchExecutionType.DATA_SCRIPT_TEST.name());
        payload.put("nodeType", NodeType.DATA_SCRIPT.name());
        payload.put("projectId", runtimeProjectId);
        payload.put("runtimeClusterId", runtimeClusterId);
        payload.put("config", config);
        payload.put("protectedInput", Boolean.TRUE);
        payload.put("scriptType", scriptType.name());
        payload.put("scriptName", script.getFileName());
        return payload;
    }

    private Map<String, Object> buildProtectedConfig(Map<String, Object> arguments,
                                                     Map<String, Object> executionConfig,
                                                     Integer maxRows) {
        LinkedHashMap<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("arguments", arguments == null ? new LinkedHashMap<String, Object>() : arguments);
        if (executionConfig != null && !executionConfig.isEmpty()) {
            config.put("executionConfig", new LinkedHashMap<String, Object>(executionConfig));
        }
        config.put("maxRows", maxRows);
        return config;
    }

    private DataScriptExecutionResultView waitForCompletion(DispatchTaskEntity submittedTask,
                                                            ScriptType scriptType,
                                                            Integer waitTimeoutSeconds) {
        int timeoutSeconds = normalizeWaitTimeout(waitTimeoutSeconds);
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        DispatchTaskEntity latestTask = submittedTask;
        RunRecordEntity latestRunRecord = null;
        while (System.currentTimeMillis() <= deadline) {
            latestTask = selectTaskStatus(submittedTask.getId());
            if (latestTask == null) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Dispatch task not found: " + submittedTask.getId());
            }
            if (isTerminalStatus(latestTask.getStatus())) {
                latestTask = selectTaskResult(submittedTask.getId());
                if (latestTask == null) {
                    throw new StudioException(StudioErrorCode.NOT_FOUND, "Dispatch task not found: " + submittedTask.getId());
                }
                boolean needsRunResult = latestTask.getPayloadJson() == null
                        || latestTask.getPayloadJson().isEmpty();
                latestRunRecord = latestTask.getRunRecordId() == null
                        ? null
                        : selectRunRecordSummary(latestTask.getRunRecordId(), needsRunResult);
                return toResult(latestTask, latestRunRecord, scriptType);
            }
            latestRunRecord = latestTask.getRunRecordId() == null
                    ? null
                    : selectRunRecordSummary(latestTask.getRunRecordId(), false);
            sleepQuietly();
        }
        return timeoutResult(latestTask, latestRunRecord, scriptType);
    }

    private DispatchTaskEntity selectTaskStatus(Long taskId) {
        return dispatchTaskMapper.selectOne(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getId, taskId)
                .select(DispatchTaskEntity::getId,
                        DispatchTaskEntity::getStatus,
                        DispatchTaskEntity::getRunRecordId));
    }

    private DispatchTaskEntity selectTaskResult(Long taskId) {
        return dispatchTaskMapper.selectOne(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getId, taskId)
                .select(DispatchTaskEntity::getId,
                        DispatchTaskEntity::getStatus,
                        DispatchTaskEntity::getRunRecordId,
                        DispatchTaskEntity::getPayloadJson));
    }

    private RunRecordEntity selectRunRecordSummary(Long runRecordId, boolean includeResultJson) {
        LambdaQueryWrapper<RunRecordEntity> wrapper = new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getId, runRecordId);
        if (includeResultJson) {
            wrapper.select(RunRecordEntity::getId,
                    RunRecordEntity::getLogStatus,
                    RunRecordEntity::getMessage,
                    RunRecordEntity::getStartedAt,
                    RunRecordEntity::getEndedAt,
                    RunRecordEntity::getResultJson);
        } else {
            wrapper.select(RunRecordEntity::getId,
                    RunRecordEntity::getLogStatus,
                    RunRecordEntity::getMessage,
                    RunRecordEntity::getStartedAt,
                    RunRecordEntity::getEndedAt);
        }
        return runRecordMapper.selectOne(wrapper);
    }

    private DataScriptExecutionResultView toResult(DispatchTaskEntity task,
                                                   RunRecordEntity runRecord,
                                                   ScriptType fallbackScriptType) {
        Map<String, Object> payload = resolveResultPayload(task, runRecord);
        DataScriptExecutionResultView result = new DataScriptExecutionResultView();
        result.setDispatchTaskId(task.getId());
        result.setRunRecordId(task.getRunRecordId());
        result.setLogStatus(runRecord == null ? null : runRecord.getLogStatus());
        result.setScriptType(resolveScriptType(payload, fallbackScriptType));
        String status = asString(payload.get("status"));
        if (!hasText(status)) {
            status = task.getStatus();
        }
        result.setStatus(status);
        result.setSuccess(Boolean.valueOf("SUCCESS".equalsIgnoreCase(task.getStatus()) && !"FAILED".equalsIgnoreCase(status)));
        result.setMessage(resolveMessage(payload, runRecord, task.getStatus()));
        result.setExecutionMs(resolveExecutionMs(payload, runRecord));
        result.setDatasourceName(asString(payload.get("datasourceName")));
        result.setLogs(asString(payload.get("logs")));
        Map<String, Object> resultJson = asMap(payload.get("resultJson"));
        if (resultJson == null) {
            resultJson = asMap(payload.get("summary"));
        }
        if (resultJson != null) {
            result.setResultJson(resultJson);
        }
        Object sqlResult = payload.get("sqlResult");
        if (sqlResult != null) {
            result.setSqlResult(objectMapper.convertValue(sqlResult, SqlExecutionResultView.class));
        }
        return result;
    }

    private DataScriptExecutionResultView timeoutResult(DispatchTaskEntity task,
                                                        RunRecordEntity runRecord,
                                                        ScriptType scriptType) {
        DataScriptExecutionResultView result = new DataScriptExecutionResultView();
        result.setDispatchTaskId(task.getId());
        result.setRunRecordId(task.getRunRecordId());
        result.setLogStatus(runRecord == null ? null : runRecord.getLogStatus());
        result.setScriptType(scriptType);
        result.setSuccess(false);
        result.setStatus(hasText(task.getStatus()) ? task.getStatus() : "QUEUED");
        result.setMessage("Script execution is still running");
        result.setExecutionMs(resolveExecutionMs(null, runRecord));
        return result;
    }

    private Map<String, Object> resolveResultPayload(DispatchTaskEntity task, RunRecordEntity runRecord) {
        if (task != null && task.getPayloadJson() != null && !task.getPayloadJson().isEmpty()) {
            return task.getPayloadJson();
        }
        if (runRecord != null && runRecord.getResultJson() != null && !runRecord.getResultJson().isEmpty()) {
            return runRecord.getResultJson();
        }
        return new LinkedHashMap<String, Object>();
    }

    private String resolveMessage(Map<String, Object> payload, RunRecordEntity runRecord, String taskStatus) {
        String message = asString(payload.get("message"));
        if (hasText(message)) {
            return message;
        }
        message = asString(payload.get("error"));
        if (hasText(message)) {
            return message;
        }
        if (runRecord != null && hasText(runRecord.getMessage())) {
            return runRecord.getMessage();
        }
        return taskStatus;
    }

    private Long resolveExecutionMs(Map<String, Object> payload, RunRecordEntity runRecord) {
        if (payload != null) {
            Object durationMs = payload.get("durationMs");
            if (durationMs instanceof Number) {
                return Long.valueOf(((Number) durationMs).longValue());
            }
            if (durationMs instanceof String && hasText((String) durationMs)) {
                try {
                    return Long.valueOf(Long.parseLong(((String) durationMs).trim()));
                } catch (NumberFormatException ignored) {
                    // Fall through to run record timestamps.
                }
            }
        }
        if (runRecord == null || runRecord.getStartedAt() == null) {
            return null;
        }
        LocalDateTime endedAt = runRecord.getEndedAt() == null ? LocalDateTime.now() : runRecord.getEndedAt();
        return Long.valueOf(Duration.between(runRecord.getStartedAt(), endedAt).toMillis());
    }

    private ScriptType resolveScriptType(Map<String, Object> payload, ScriptType fallbackScriptType) {
        String scriptType = asString(payload.get("scriptType"));
        if (hasText(scriptType)) {
            try {
                return ScriptType.valueOf(scriptType);
            } catch (IllegalArgumentException ignored) {
                return fallbackScriptType;
            }
        }
        return fallbackScriptType;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int normalizeWaitTimeout(Integer waitTimeoutSeconds) {
        if (waitTimeoutSeconds == null) {
            return DEFAULT_WAIT_TIMEOUT_SECONDS;
        }
        return Math.max(1, Math.min(MAX_WAIT_TIMEOUT_SECONDS, waitTimeoutSeconds.intValue()));
    }

    private boolean isTerminalStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Interrupted while waiting for script execution", exception);
        }
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
