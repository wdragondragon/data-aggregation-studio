package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.EnvironmentDependencyView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.EnvironmentDependencySaveRequest;
import com.jdragon.studio.infra.service.EnvironmentDependencyService;
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

import jakarta.validation.Valid;
import java.util.List;

@Tag(name = "Environment Dependencies", description = "Script environment dependency APIs")
@RestController
@RequestMapping("/api/v1/environment-dependencies")
public class EnvironmentDependencyController {

    private final EnvironmentDependencyService environmentDependencyService;

    public EnvironmentDependencyController(EnvironmentDependencyService environmentDependencyService) {
        this.environmentDependencyService = environmentDependencyService;
    }

    @Operation(summary = "Query environment dependencies")
    @PostMapping("/queryPage")
    public Result<PageView<EnvironmentDependencyView>> queryPage(@RequestParam(value = "pageNum", required = false) Integer pageNum,
                                                                 @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                                 @RequestParam(value = "keyword", required = false) String keyword,
                                                                 @RequestParam(value = "enabled", required = false) Boolean enabled) {
        return Result.success(environmentDependencyService.queryPage(pageNum, pageSize, keyword, enabled));
    }

    @Operation(summary = "List selectable environment dependencies")
    @GetMapping("/options")
    public Result<List<EnvironmentDependencyView>> options(@RequestParam(value = "enabledOnly", required = false, defaultValue = "true") Boolean enabledOnly) {
        return Result.success(environmentDependencyService.options(enabledOnly));
    }

    @Operation(summary = "Get environment dependency")
    @GetMapping("/{id}")
    public Result<EnvironmentDependencyView> get(@PathVariable("id") Long id) {
        return Result.success(environmentDependencyService.get(id));
    }

    @Operation(summary = "Create or update environment dependency")
    @PostMapping("/saveOrUpdateCheck")
    public Result<EnvironmentDependencyView> saveOrUpdateCheck(@Valid @RequestBody EnvironmentDependencySaveRequest request) {
        return Result.success(environmentDependencyService.saveOrUpdateCheck(request));
    }

    @Operation(summary = "Enable environment dependency")
    @PostMapping("/{id}/enable")
    public Result<EnvironmentDependencyView> enable(@PathVariable("id") Long id) {
        return Result.success(environmentDependencyService.enable(id));
    }

    @Operation(summary = "Disable environment dependency")
    @PostMapping("/{id}/disable")
    public Result<EnvironmentDependencyView> disable(@PathVariable("id") Long id) {
        return Result.success(environmentDependencyService.disable(id));
    }

    @Operation(summary = "Delete environment dependency")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        environmentDependencyService.delete(id);
        return Result.success(null);
    }
}
