package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.system.SystemProjectMemberRequestView;
import com.jdragon.studio.dto.model.system.SystemProjectMemberView;
import com.jdragon.studio.dto.model.system.SystemProjectView;
import com.jdragon.studio.dto.model.system.SystemProjectWorkerView;
import com.jdragon.studio.dto.model.system.SystemTenantMemberView;
import com.jdragon.studio.dto.model.system.SystemTenantView;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.ProjectMemberRequestEntity;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.ResourceShareEntity;
import com.jdragon.studio.infra.entity.TenantEntity;
import com.jdragon.studio.infra.entity.TenantMemberEntity;
import com.jdragon.studio.infra.service.SystemManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "System Management", description = "Tenant, project, membership and worker management APIs")
@RestController
@RequestMapping("/api/v1/system")
public class SystemManagementController {

    private final SystemManagementService systemManagementService;

    public SystemManagementController(SystemManagementService systemManagementService) {
        this.systemManagementService = systemManagementService;
    }

    @Operation(summary = "List accessible tenants")
    @GetMapping("/tenants")
    public Result<List<SystemTenantView>> listTenants() {
        return Result.success(systemManagementService.listTenants());
    }

    @Operation(summary = "Create or update tenant")
    @PostMapping("/tenants")
    public Result<TenantEntity> saveTenant(@RequestBody TenantEntity entity) {
        return Result.success(systemManagementService.saveTenant(entity));
    }

    @Operation(summary = "Delete tenant")
    @DeleteMapping("/tenants/{id}")
    public Result<Void> deleteTenant(@PathVariable("id") Long id) {
        systemManagementService.deleteTenant(id);
        return Result.success(null);
    }

    @Operation(summary = "List accessible projects in current tenant")
    @GetMapping("/projects")
    public Result<List<SystemProjectView>> listProjects() {
        return Result.success(systemManagementService.listProjects());
    }

    @Operation(summary = "Create or update project")
    @PostMapping("/projects")
    public Result<ProjectEntity> saveProject(@RequestBody ProjectEntity entity) {
        return Result.success(systemManagementService.saveProject(entity));
    }

    @Operation(summary = "Delete project")
    @DeleteMapping("/projects/{id}")
    public Result<Void> deleteProject(@PathVariable("id") Long id) {
        systemManagementService.deleteProject(id);
        return Result.success(null);
    }

    @Operation(summary = "List tenant members")
    @GetMapping("/tenant-members")
    public Result<List<SystemTenantMemberView>> listTenantMembers() {
        return Result.success(systemManagementService.listTenantMembers());
    }

    @Operation(summary = "Create or update tenant member")
    @PostMapping("/tenant-members")
    public Result<TenantMemberEntity> saveTenantMember(@RequestBody TenantMemberEntity entity) {
        return Result.success(systemManagementService.saveTenantMember(entity));
    }

    @Operation(summary = "Delete tenant member")
    @DeleteMapping("/tenant-members/{id}")
    public Result<Void> deleteTenantMember(@PathVariable("id") Long id) {
        systemManagementService.deleteTenantMember(id);
        return Result.success(null);
    }

    @Operation(summary = "List project members")
    @GetMapping("/project-members")
    public Result<List<SystemProjectMemberView>> listProjectMembers(@RequestParam(value = "projectId", required = false) Long projectId) {
        return Result.success(systemManagementService.listProjectMembers(projectId));
    }

    @Operation(summary = "Create or update project member")
    @PostMapping("/project-members")
    public Result<ProjectMemberEntity> saveProjectMember(@RequestBody ProjectMemberEntity entity) {
        return Result.success(systemManagementService.saveProjectMember(entity));
    }

    @Operation(summary = "Delete project member")
    @DeleteMapping("/project-members/{id}")
    public Result<Void> deleteProjectMember(@PathVariable("id") Long id) {
        systemManagementService.deleteProjectMember(id);
        return Result.success(null);
    }

    @Operation(summary = "List project membership requests")
    @GetMapping("/project-member-requests")
    public Result<List<SystemProjectMemberRequestView>> listProjectMemberRequests(@RequestParam(value = "projectId", required = false) Long projectId) {
        return Result.success(systemManagementService.listProjectMemberRequests(projectId));
    }

    @Operation(summary = "Create or update project membership request")
    @PostMapping("/project-member-requests")
    public Result<ProjectMemberRequestEntity> saveProjectMemberRequest(@RequestBody ProjectMemberRequestEntity entity) {
        return Result.success(systemManagementService.saveProjectMemberRequest(entity));
    }

    @Operation(summary = "Delete project membership request")
    @DeleteMapping("/project-member-requests/{id}")
    public Result<Void> deleteProjectMemberRequest(@PathVariable("id") Long id) {
        systemManagementService.deleteProjectMemberRequest(id);
        return Result.success(null);
    }

    @Operation(summary = "List current project worker bindings")
    @GetMapping("/project-workers")
    public Result<List<SystemProjectWorkerView>> listProjectWorkers(@RequestParam(value = "projectId", required = false) Long projectId) {
        return Result.success(systemManagementService.listProjectWorkers(projectId));
    }

    @Operation(summary = "Create or update project worker binding")
    @PostMapping("/project-workers")
    public Result<ProjectWorkerBindingEntity> saveProjectWorkerBinding(@RequestBody ProjectWorkerBindingEntity entity) {
        return Result.success(systemManagementService.saveProjectWorkerBinding(entity));
    }

    @Operation(summary = "Delete project worker binding")
    @DeleteMapping("/project-workers/{id}")
    public Result<Void> deleteProjectWorkerBinding(@PathVariable("id") Long id) {
        systemManagementService.deleteProjectWorkerBinding(id);
        return Result.success(null);
    }

    @Operation(summary = "List resource shares")
    @GetMapping("/resource-shares")
    public Result<List<ResourceShareEntity>> listResourceShares(@RequestParam(value = "resourceType", required = false) String resourceType,
                                                                @RequestParam(value = "projectId", required = false) Long projectId) {
        return Result.success(systemManagementService.listResourceShares(resourceType, projectId));
    }

    @Operation(summary = "Create or update resource share")
    @PostMapping("/resource-shares")
    public Result<ResourceShareEntity> saveResourceShare(@RequestBody ResourceShareEntity entity) {
        return Result.success(systemManagementService.saveResourceShare(entity));
    }

    @Operation(summary = "Delete resource share")
    @DeleteMapping("/resource-shares/{id}")
    public Result<Void> deleteResourceShare(@PathVariable("id") Long id) {
        systemManagementService.deleteResourceShare(id);
        return Result.success(null);
    }
}
