package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.ScriptEnvironmentListView;
import com.jdragon.studio.dto.model.ScriptEnvironmentView;
import com.jdragon.studio.dto.model.request.ScriptEnvironmentSaveRequest;
import com.jdragon.studio.infra.service.ScriptEnvironmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;

@Tag(name = "Script Environments", description = "Java script environment APIs")
@RestController
@RequestMapping("/api/v1/script-environments")
public class ScriptEnvironmentController {

    private final ScriptEnvironmentService scriptEnvironmentService;

    public ScriptEnvironmentController(ScriptEnvironmentService scriptEnvironmentService) {
        this.scriptEnvironmentService = scriptEnvironmentService;
    }

    @Operation(summary = "Query script environments")
    @PostMapping("/queryPage")
    public Result<PageView<ScriptEnvironmentListView>> queryPage(@RequestParam(value = "pageNum", required = false) Integer pageNum,
                                                                 @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                                 @RequestParam(value = "keyword", required = false) String keyword,
                                                                 @RequestParam(value = "enabled", required = false) Boolean enabled) {
        return Result.success(scriptEnvironmentService.queryPage(pageNum, pageSize, keyword, enabled));
    }

    @Operation(summary = "List selectable script environments")
    @GetMapping("/options")
    public Result<List<ScriptEnvironmentView>> options(@RequestParam(value = "enabledOnly", required = false, defaultValue = "true") Boolean enabledOnly) {
        return Result.success(scriptEnvironmentService.options(enabledOnly));
    }

    @Operation(summary = "Get script environment")
    @GetMapping("/{id}")
    public Result<ScriptEnvironmentView> get(@PathVariable("id") Long id) {
        return Result.success(scriptEnvironmentService.get(id));
    }

    @Operation(summary = "Create or update script environment")
    @PostMapping("/saveOrUpdateCheck")
    public Result<ScriptEnvironmentView> saveOrUpdateCheck(@Valid @RequestBody ScriptEnvironmentSaveRequest request) {
        return Result.success(scriptEnvironmentService.saveOrUpdateCheck(request));
    }

    @Operation(summary = "Enable script environment")
    @PostMapping("/{id}/enable")
    public Result<ScriptEnvironmentView> enable(@PathVariable("id") Long id) {
        return Result.success(scriptEnvironmentService.enable(id));
    }

    @Operation(summary = "Disable script environment")
    @PostMapping("/{id}/disable")
    public Result<ScriptEnvironmentView> disable(@PathVariable("id") Long id) {
        return Result.success(scriptEnvironmentService.disable(id));
    }

    @Operation(summary = "Refresh script environment")
    @PostMapping("/{id}/refresh")
    public Result<ScriptEnvironmentView> refresh(@PathVariable("id") Long id) {
        return Result.success(scriptEnvironmentService.refresh(id));
    }
}
