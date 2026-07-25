package com.jdragon.studio.worker.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.RuntimeFlinkSqlExecuteRequest;
import com.jdragon.studio.flink.service.FlinkSqlExecutionService;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Executes guarded Flink SQL inside the selected Worker runtime. */
@RestController
@RequestMapping("/internal/runtime/flink")
public class InternalFlinkExecutionController {

    private final FlinkSqlExecutionService executionService;
    private final RuntimeClusterMapper runtimeClusterMapper;
    private final StudioPlatformProperties properties;
    private final WorkerAuthorizationService workerAuthorizationService;

    public InternalFlinkExecutionController(FlinkSqlExecutionService executionService,
                                            RuntimeClusterMapper runtimeClusterMapper,
                                            StudioPlatformProperties properties,
                                            WorkerAuthorizationService workerAuthorizationService) {
        this.executionService = executionService;
        this.runtimeClusterMapper = runtimeClusterMapper;
        this.properties = properties;
        this.workerAuthorizationService = workerAuthorizationService;
    }

    @PostMapping("/sql/execute")
    public ResponseEntity<Result<FlinkQuestionResultView>> execute(
            @Valid @RequestBody RuntimeFlinkSqlExecuteRequest request) {
        if (!matchesRuntimeIdentity(request)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.error(StudioErrorCode.BUSINESS_ERROR,
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
            return ResponseEntity.ok(Result.success(executionService.execute(request.getExecution())));
        } catch (StudioException exception) {
            return ResponseEntity.status(statusFor(exception))
                    .body(Result.error(exception.getCode(), exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.error(StudioErrorCode.INTERNAL_SERVER_ERROR, exception.getMessage()));
        } finally {
            restoreContext(previous);
        }
    }

    private boolean matchesRuntimeIdentity(RuntimeFlinkSqlExecuteRequest request) {
        if (request == null || request.getExecution() == null
                || request.getTargetClusterId() == null
                || !StringUtils.hasText(request.getTargetClusterCode())
                || !StringUtils.hasText(request.getTenantId())
                || request.getProjectId() == null
                || !Objects.equals(request.getTargetClusterId(),
                request.getExecution().getRuntimeClusterId())) {
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

    private HttpStatus statusFor(StudioException exception) {
        if (StudioErrorCode.UNAUTHORIZED.equals(exception.getCode())) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (StudioErrorCode.FORBIDDEN.equals(exception.getCode())) {
            return HttpStatus.FORBIDDEN;
        }
        if (StudioErrorCode.NOT_FOUND.equals(exception.getCode())) {
            return HttpStatus.NOT_FOUND;
        }
        if (StudioErrorCode.INTERNAL_SERVER_ERROR.equals(exception.getCode())) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
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
