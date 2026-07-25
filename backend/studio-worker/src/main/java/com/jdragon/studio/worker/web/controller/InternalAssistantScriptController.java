package com.jdragon.studio.worker.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.request.RuntimeAssistantScriptExecuteRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.AssistantScriptSkillExecutionService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Executes registered assistant script entrypoints on the selected Worker. */
@RestController
@RequestMapping("/internal/runtime/assistant/scripts")
public class InternalAssistantScriptController {

    private final AssistantScriptSkillExecutionService executionService;
    private final RuntimeClusterMapper runtimeClusterMapper;
    private final StudioPlatformProperties properties;
    private final WorkerAuthorizationService workerAuthorizationService;

    public InternalAssistantScriptController(AssistantScriptSkillExecutionService executionService,
                                             RuntimeClusterMapper runtimeClusterMapper,
                                             StudioPlatformProperties properties,
                                             WorkerAuthorizationService workerAuthorizationService) {
        this.executionService = executionService;
        this.runtimeClusterMapper = runtimeClusterMapper;
        this.properties = properties;
        this.workerAuthorizationService = workerAuthorizationService;
    }

    @PostMapping("/execute")
    public ResponseEntity<Result<Map<String, Object>>> execute(
            @Valid @RequestBody RuntimeAssistantScriptExecuteRequest request) {
        if (!matchesRuntimeIdentity(request)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.error(StudioErrorCode.SERVICE_UNAVAILABLE,
                            "Runtime process identity does not match the requested target cluster"));
        }
        if (!workerAuthorizationService.isRuntimeClusterAuthorizedForProject(
                request.getTenantId(), request.getProjectId(), request.getTargetClusterId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Result.error(StudioErrorCode.FORBIDDEN,
                            "Runtime cluster is not authorized for the requested project"));
        }

        StudioRequestContext previous = StudioRequestContextHolder.getContext();
        StudioRequestContext context = new StudioRequestContext();
        context.setTenantId(request.getTenantId());
        context.setProjectId(request.getProjectId());
        context.setUserId(request.getUserId());
        context.setUsername(request.getUsername());
        StudioRequestContextHolder.setContext(context);
        try {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("entrypointId", request.getEntrypointId());
            params.put("input", new LinkedHashMap<String, Object>(request.getInput()));
            return ResponseEntity.ok(Result.success(executionService.execute(params)));
        } catch (StudioException exception) {
            return ResponseEntity.status(statusFor(exception.getCode()))
                    .body(Result.error(exception.getCode(), exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Result.error(StudioErrorCode.BAD_REQUEST, exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.error(StudioErrorCode.INTERNAL_SERVER_ERROR, exception.getMessage()));
        } finally {
            restoreContext(previous);
        }
    }

    private boolean matchesRuntimeIdentity(RuntimeAssistantScriptExecuteRequest request) {
        if (request == null || request.getTargetClusterId() == null
                || !StringUtils.hasText(request.getTargetClusterCode())
                || !StringUtils.hasText(request.getTenantId())
                || request.getProjectId() == null) {
            return false;
        }
        RuntimeClusterEntity cluster = runtimeClusterMapper.selectById(request.getTargetClusterId());
        return cluster != null
                && Integer.valueOf(1).equals(cluster.getEnabled())
                && request.getTenantId().equals(cluster.getTenantId())
                && request.getTargetClusterCode().equalsIgnoreCase(cluster.getCode())
                && StringUtils.hasText(properties.getRuntimeClusterCode())
                && properties.getRuntimeClusterCode().trim().equalsIgnoreCase(cluster.getCode());
    }

    private HttpStatus statusFor(String code) {
        if (StudioErrorCode.UNAUTHORIZED.equals(code)) return HttpStatus.UNAUTHORIZED;
        if (StudioErrorCode.FORBIDDEN.equals(code)) return HttpStatus.FORBIDDEN;
        if (StudioErrorCode.NOT_FOUND.equals(code)) return HttpStatus.NOT_FOUND;
        if (StudioErrorCode.SERVICE_UNAVAILABLE.equals(code)) return HttpStatus.SERVICE_UNAVAILABLE;
        if (StudioErrorCode.INTERNAL_SERVER_ERROR.equals(code)) return HttpStatus.INTERNAL_SERVER_ERROR;
        return HttpStatus.BAD_REQUEST;
    }

    private void restoreContext(StudioRequestContext previous) {
        if (previous == null) {
            StudioRequestContextHolder.clear();
        } else {
            StudioRequestContextHolder.setContext(previous);
        }
    }
}
