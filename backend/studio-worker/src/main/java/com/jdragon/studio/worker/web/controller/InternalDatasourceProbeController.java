package com.jdragon.studio.worker.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.enums.RuntimeDatasourceProbeMode;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.RuntimeDatasourceHydrationResultView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryOptionResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.dto.model.request.RuntimeDatasourceProbeRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DatasourceClusterBindingEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeExecutor;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Internal-only datasource execution with explicit cluster, tenant, project and binding checks. */
@RestController
@RequestMapping("/internal/runtime/datasource")
public class InternalDatasourceProbeController {

    private final RuntimeDatasourceProbeExecutor executor;
    private final StudioPlatformProperties properties;
    private final RuntimeClusterMapper runtimeClusterMapper;
    private final WorkerAuthorizationService workerAuthorizationService;
    private final DatasourceClusterBindingMapper datasourceClusterBindingMapper;
    private final DataSourceService dataSourceService;

    public InternalDatasourceProbeController(RuntimeDatasourceProbeExecutor executor,
                                             StudioPlatformProperties properties,
                                             RuntimeClusterMapper runtimeClusterMapper,
                                             WorkerAuthorizationService workerAuthorizationService,
                                             DatasourceClusterBindingMapper datasourceClusterBindingMapper,
                                             DataSourceService dataSourceService) {
        this.executor = executor;
        this.properties = properties;
        this.runtimeClusterMapper = runtimeClusterMapper;
        this.workerAuthorizationService = workerAuthorizationService;
        this.datasourceClusterBindingMapper = datasourceClusterBindingMapper;
        this.dataSourceService = dataSourceService;
    }

    @PostMapping("/probe")
    public Result<ConnectionTestResult> probe(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, true, datasource -> executor.test(datasource));
    }

    @PostMapping("/discover")
    public Result<ModelDiscoveryResult> discover(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false, datasource -> executor.discover(
                datasource, request.getKeyword(), request.getPageNo(), request.getPageSize()));
    }

    @PostMapping("/discover-options")
    public Result<ModelDiscoveryOptionResult> discoverOptions(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false, datasource -> executor.discoverOptions(
                datasource, request.getKeyword(), request.getPageNo(), request.getPageSize()));
    }

    @PostMapping("/hydrate")
    public Result<RuntimeDatasourceHydrationResultView> hydrate(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false,
                datasource -> executor.hydrate(datasource, request.getPhysicalLocators()));
    }

    @PostMapping("/preview")
    public Result<List<Map<String, Object>>> preview(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false,
                datasource -> executor.preview(datasource, request.getModel(), request.getLimit()));
    }

    @PostMapping("/query")
    public Result<SqlExecutionResultView> query(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false, datasource -> executor.query(
                datasource, request.getSql(), request.getParameters(), request.getMaxRows()));
    }

    private <T> Result<T> execute(String token,
                                  RuntimeDatasourceProbeRequest request,
                                  boolean draftAllowed,
                                  Function<DataSourceDefinition, T> action) {
        validateInternalToken(token);
        validateRuntimeIdentity(request);
        if (!workerAuthorizationService.isRuntimeClusterAuthorizedForProject(
                request.getTenantId(), request.getProjectId(), request.getTargetClusterId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Runtime cluster is not authorized for the requested project");
        }

        StudioRequestContext previous = StudioRequestContextHolder.getContext();
        StudioRequestContext context = new StudioRequestContext();
        context.setTenantId(request.getTenantId());
        context.setProjectId(request.getProjectId());
        context.setUserId(request.getUserId());
        context.setUsername(request.getUsername());
        StudioRequestContextHolder.setContext(context);
        try {
            DataSourceDefinition datasource = resolveDatasource(request, draftAllowed);
            return Result.success(action.apply(datasource));
        } catch (StudioException exception) {
            return Result.error(exception.getCode(), exception.getMessage());
        } finally {
            restoreContext(previous);
        }
    }

    private void validateInternalToken(String token) {
        String expected = properties.getInternalApiToken();
        if (!StringUtils.hasText(token) || !StringUtils.hasText(expected)
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Internal runtime authentication failed");
        }
    }

    private void validateRuntimeIdentity(RuntimeDatasourceProbeRequest request) {
        if (request == null || request.getTargetClusterId() == null
                || !StringUtils.hasText(request.getTargetClusterCode())
                || !StringUtils.hasText(request.getTenantId())
                || request.getProjectId() == null || request.getMode() == null
                || request.getDatasource() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Datasource runtime identity is incomplete");
        }
        RuntimeClusterEntity cluster = runtimeClusterMapper.selectById(request.getTargetClusterId());
        boolean matches = cluster != null
                && Integer.valueOf(1).equals(cluster.getEnabled())
                && request.getTenantId().equals(cluster.getTenantId())
                && request.getTargetClusterCode().equalsIgnoreCase(cluster.getCode())
                && StringUtils.hasText(properties.getRuntimeClusterCode())
                && properties.getRuntimeClusterCode().trim().equalsIgnoreCase(cluster.getCode());
        if (!matches) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Target runtime cluster identity does not match this worker");
        }
    }

    private DataSourceDefinition resolveDatasource(RuntimeDatasourceProbeRequest request,
                                                   boolean draftAllowed) {
        DataSourceDefinition supplied = request.getDatasource();
        if (!Objects.equals(request.getTenantId(), supplied.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Datasource belongs to another tenant");
        }
        if (RuntimeDatasourceProbeMode.DRAFT_FORM == request.getMode()) {
            return resolveDraftDatasource(request, supplied, draftAllowed);
        }
        if (supplied.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Stored datasource id is required for runtime execution");
        }
        DataSourceDefinition canonical = dataSourceService.getInternal(supplied.getId());
        if (canonical == null
                || !Objects.equals(canonical.getTenantId(), request.getTenantId())
                || !Objects.equals(canonical.getId(), supplied.getId())
                || !Objects.equals(canonical.getProjectId(), supplied.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Datasource is not available");
        }
        assertStoredBinding(canonical.getId(), request);
        return canonical;
    }

    private DataSourceDefinition resolveDraftDatasource(RuntimeDatasourceProbeRequest request,
                                                        DataSourceDefinition supplied,
                                                        boolean draftAllowed) {
        if (!draftAllowed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Draft datasource payloads are only accepted by the connection probe endpoint");
        }
        if (!Objects.equals(request.getProjectId(), supplied.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Datasource draft belongs to another project");
        }
        if (supplied.getApplicableClusterIds() == null
                || !supplied.getApplicableClusterIds().contains(request.getTargetClusterId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Datasource draft is not applicable to the requested runtime cluster");
        }
        if (supplied.getId() != null) {
            DataSourceDefinition canonical = dataSourceService.getInternal(supplied.getId());
            if (canonical == null
                    || !Objects.equals(canonical.getTenantId(), request.getTenantId())
                    || !Objects.equals(canonical.getProjectId(), request.getProjectId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Datasource is not available");
            }
        }
        return supplied;
    }

    private void assertStoredBinding(Long datasourceId, RuntimeDatasourceProbeRequest request) {
        Long count = datasourceClusterBindingMapper.selectCount(
                new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                        .eq(DatasourceClusterBindingEntity::getTenantId, request.getTenantId())
                        .eq(DatasourceClusterBindingEntity::getDatasourceId, datasourceId)
                        .eq(DatasourceClusterBindingEntity::getRuntimeClusterId, request.getTargetClusterId())
                        .eq(DatasourceClusterBindingEntity::getEnabled, 1));
        if (count == null || count.longValue() == 0L) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Datasource is not applicable to the requested runtime cluster");
        }
    }

    private void restoreContext(StudioRequestContext previous) {
        if (previous == null) {
            StudioRequestContextHolder.clear();
        } else {
            StudioRequestContextHolder.setContext(previous);
        }
    }
}
