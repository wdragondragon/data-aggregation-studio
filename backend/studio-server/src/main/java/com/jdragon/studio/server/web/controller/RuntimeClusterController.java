package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.RuntimeClusterProjectAuthorizationView;
import com.jdragon.studio.dto.model.RuntimeClusterInstanceView;
import com.jdragon.studio.dto.model.RuntimeClusterView;
import com.jdragon.studio.dto.model.RuntimeEndpointView;
import com.jdragon.studio.dto.model.RuntimeValidationView;
import com.jdragon.studio.dto.model.request.RuntimeClusterHeartbeatRequest;
import com.jdragon.studio.dto.model.request.RuntimeClusterProjectAuthorizationRequest;
import com.jdragon.studio.dto.model.request.RuntimeClusterSaveRequest;
import com.jdragon.studio.dto.model.request.RuntimeEndpointSaveRequest;
import com.jdragon.studio.dto.model.request.RuntimeValidationQueryRequest;
import com.jdragon.studio.infra.service.RuntimeClusterService;
import com.jdragon.studio.infra.service.RuntimeValidationService;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.util.StringUtils;

@Tag(name = "Runtime Clusters", description = "Runtime cluster registry, endpoints and project authorizations")
@RestController
@RequestMapping("/api/v1/runtime-clusters")
public class RuntimeClusterController {
    private final RuntimeClusterService service;
    private final RuntimeValidationService runtimeValidationService;
    private final StudioPlatformProperties properties;
    public RuntimeClusterController(RuntimeClusterService service,
                                    RuntimeValidationService runtimeValidationService,
                                    StudioPlatformProperties properties) {
        this.service = service;
        this.runtimeValidationService = runtimeValidationService;
        this.properties = properties;
    }
    @Operation(summary = "List manageable runtime clusters") @GetMapping
    public Result<List<RuntimeClusterView>> list() { return Result.success(service.list()); }
    @Operation(summary = "List project runtime cluster options") @GetMapping("/options")
    public Result<List<RuntimeClusterView>> options(@RequestParam(value="projectId",required=false) Long projectId) { return Result.success(service.options(projectId)); }
    @Operation(summary = "Get runtime cluster") @GetMapping("/{id}")
    public Result<RuntimeClusterView> get(@PathVariable("id") Long id) { return Result.success(service.get(id)); }
    @Operation(summary = "Create or update runtime cluster") @PostMapping
    public Result<RuntimeClusterView> save(@RequestBody RuntimeClusterSaveRequest request) { return Result.success(service.save(request)); }
    @Operation(summary = "Enable runtime cluster") @PostMapping("/{id}/enable")
    public Result<RuntimeClusterView> enable(@PathVariable("id") Long id) { return Result.success(service.enable(id)); }
    @Operation(summary = "Disable runtime cluster") @PostMapping("/{id}/disable")
    public Result<RuntimeClusterView> disable(@PathVariable("id") Long id) { return Result.success(service.disable(id)); }
    @Operation(summary = "Delete an unused runtime cluster") @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) { service.delete(id); return Result.success(null); }
    @Operation(summary = "List runtime cluster instances") @GetMapping("/{id}/instances")
    public Result<List<RuntimeClusterInstanceView>> instances(@PathVariable("id") Long id) { return Result.success(service.instances(id)); }
    @Operation(summary = "List cluster endpoints") @GetMapping("/{id}/endpoints")
    public Result<List<RuntimeEndpointView>> endpoints(@PathVariable("id") Long id) { return Result.success(service.endpoints(id)); }
    @Operation(summary = "Save cluster endpoint") @PostMapping("/endpoints")
    public Result<RuntimeEndpointView> saveEndpoint(@RequestBody RuntimeEndpointSaveRequest request) { return Result.success(service.saveEndpoint(request)); }
    @Operation(summary = "Test cluster endpoint") @PostMapping("/endpoints/{id}/test")
    public Result<RuntimeEndpointView> testEndpoint(@PathVariable("id") Long id) { return Result.success(service.testEndpoint(id)); }
    @Operation(summary = "Disable cluster endpoint") @PostMapping("/endpoints/{id}/disable")
    public Result<RuntimeEndpointView> disableEndpoint(@PathVariable("id") Long id) { return Result.success(service.disableEndpoint(id)); }
    @Operation(summary = "Delete a disabled cluster endpoint") @DeleteMapping("/endpoints/{id}")
    public Result<Void> deleteEndpoint(@PathVariable("id") Long id) { service.deleteEndpoint(id); return Result.success(null); }
    @Operation(summary = "List project cluster authorizations") @GetMapping("/project-authorizations")
    public Result<List<RuntimeClusterProjectAuthorizationView>> authorizations(@RequestParam("projectId") Long projectId) { return Result.success(service.projectAuthorizations(projectId)); }
    @Operation(summary = "Save project cluster authorization") @PostMapping("/project-authorizations")
    public Result<RuntimeClusterProjectAuthorizationView> saveAuthorization(@RequestBody RuntimeClusterProjectAuthorizationRequest request) { return Result.success(service.saveProjectAuthorization(request)); }
    @Operation(summary = "Query invalid runtime resource configurations") @PostMapping("/validations/query")
    public Result<List<RuntimeValidationView>> queryInvalidValidations(
            @RequestBody(required = false) RuntimeValidationQueryRequest request) {
        return Result.success(runtimeValidationService.queryInvalid(
                request == null ? null : request.getResourceType(),
                request == null ? null : request.getResourceIds()));
    }
    @Operation(summary = "Report a runtime instance heartbeat") @PostMapping("/internal/heartbeat")
    public Result<Void> heartbeat(@RequestHeader(value = StudioConstants.INTERNAL_API_TOKEN_HEADER, required = false) String token,
                                  @RequestBody RuntimeClusterHeartbeatRequest request) {
        String expected = properties.getInternalApiToken();
        if (!StringUtils.hasText(token) || !StringUtils.hasText(expected)
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Internal runtime authentication failed");
        }
        service.heartbeat(request); return Result.success(null);
    }
}
