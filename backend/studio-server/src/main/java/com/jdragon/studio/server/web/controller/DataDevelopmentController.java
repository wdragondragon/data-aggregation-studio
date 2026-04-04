package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataDevelopmentDirectoryView;
import com.jdragon.studio.dto.model.DataDevelopmentScriptView;
import com.jdragon.studio.dto.model.DataDevelopmentTreeNode;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.request.DataDevelopmentDirectorySaveRequest;
import com.jdragon.studio.dto.model.request.DataDevelopmentMoveRequest;
import com.jdragon.studio.dto.model.request.DataDevelopmentScriptSaveRequest;
import com.jdragon.studio.dto.model.request.SqlExecutionRequest;
import com.jdragon.studio.infra.service.DataDevelopmentService;
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

import javax.validation.Valid;
import java.util.List;

@Tag(name = "Data Development", description = "Data development directory and script APIs")
@RestController
@RequestMapping("/api/v1/data-development")
public class DataDevelopmentController {

    private final DataDevelopmentService dataDevelopmentService;

    public DataDevelopmentController(DataDevelopmentService dataDevelopmentService) {
        this.dataDevelopmentService = dataDevelopmentService;
    }

    @Operation(summary = "Load data development tree")
    @GetMapping("/tree")
    public Result<List<DataDevelopmentTreeNode>> tree() {
        return Result.success(dataDevelopmentService.tree());
    }

    @Operation(summary = "List directories")
    @GetMapping("/directories")
    public Result<List<DataDevelopmentDirectoryView>> directories() {
        return Result.success(dataDevelopmentService.listDirectories());
    }

    @Operation(summary = "Create or update directory")
    @PostMapping("/directories")
    public Result<DataDevelopmentDirectoryView> saveDirectory(@Valid @RequestBody DataDevelopmentDirectorySaveRequest request) {
        return Result.success(dataDevelopmentService.saveDirectory(request));
    }

    @Operation(summary = "Move directory")
    @PostMapping("/directories/{id}/move")
    public Result<Void> moveDirectory(@PathVariable("id") Long id,
                                      @RequestBody(required = false) DataDevelopmentMoveRequest request) {
        dataDevelopmentService.moveDirectory(id, request);
        return Result.success(null);
    }

    @Operation(summary = "Delete directory recursively")
    @DeleteMapping("/directories/{id}")
    public Result<Void> deleteDirectory(@PathVariable("id") Long id) {
        dataDevelopmentService.deleteDirectory(id);
        return Result.success(null);
    }

    @Operation(summary = "List scripts")
    @GetMapping("/scripts")
    public Result<List<DataDevelopmentScriptView>> scripts(@RequestParam(value = "scriptType", required = false) ScriptType scriptType) {
        return Result.success(dataDevelopmentService.listScripts(scriptType));
    }

    @Operation(summary = "Get script detail")
    @GetMapping("/scripts/{id}")
    public Result<DataDevelopmentScriptView> script(@PathVariable("id") Long id) {
        return Result.success(dataDevelopmentService.getScript(id));
    }

    @Operation(summary = "Create or update script")
    @PostMapping("/scripts")
    public Result<DataDevelopmentScriptView> saveScript(@Valid @RequestBody DataDevelopmentScriptSaveRequest request) {
        return Result.success(dataDevelopmentService.saveScript(request));
    }

    @Operation(summary = "Move script")
    @PostMapping("/scripts/{id}/move")
    public Result<Void> moveScript(@PathVariable("id") Long id,
                                   @RequestBody(required = false) DataDevelopmentMoveRequest request) {
        dataDevelopmentService.moveScript(id, request);
        return Result.success(null);
    }

    @Operation(summary = "Delete script")
    @DeleteMapping("/scripts/{id}")
    public Result<Void> deleteScript(@PathVariable("id") Long id) {
        dataDevelopmentService.deleteScript(id);
        return Result.success(null);
    }

    @Operation(summary = "List SQL-capable datasources")
    @GetMapping("/datasources")
    public Result<List<DataSourceDefinition>> datasources() {
        return Result.success(dataDevelopmentService.listSqlCapableDatasources());
    }

    @Operation(summary = "Execute SQL in editor")
    @PostMapping("/sql/execute")
    public Result<SqlExecutionResultView> execute(@Valid @RequestBody SqlExecutionRequest request) {
        return Result.success(dataDevelopmentService.execute(request));
    }
}
