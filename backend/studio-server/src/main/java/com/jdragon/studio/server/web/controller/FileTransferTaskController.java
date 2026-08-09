package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferRunView;
import com.jdragon.studio.dto.model.FileTransferSelectionPreviewView;
import com.jdragon.studio.dto.model.FileTransferTaskDefinitionView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.FileTransferPreviewRequest;
import com.jdragon.studio.dto.model.request.FileTransferTaskSaveRequest;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.FileTransferRunService;
import com.jdragon.studio.infra.service.FileTransferTaskService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeRouter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/file-transfer-tasks")
public class FileTransferTaskController {

    private final FileTransferTaskService taskService;
    private final FileTransferRunService runService;
    private final DataSourceService dataSourceService;
    private final RuntimeDatasourceProbeRouter runtimeDatasourceProbeRouter;

    public FileTransferTaskController(FileTransferTaskService taskService,
                                      FileTransferRunService runService,
                                      DataSourceService dataSourceService,
                                      RuntimeDatasourceProbeRouter runtimeDatasourceProbeRouter) {
        this.taskService = taskService;
        this.runService = runService;
        this.dataSourceService = dataSourceService;
        this.runtimeDatasourceProbeRouter = runtimeDatasourceProbeRouter;
    }

    @GetMapping
    public Result<PageView<FileTransferTaskDefinitionView>> list(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status) {
        return Result.success(taskService.listPage(pageNo, pageSize, keyword, status));
    }

    @GetMapping("/{id}")
    public Result<FileTransferTaskDefinitionView> get(@PathVariable("id") Long id) {
        return Result.success(taskService.get(id));
    }

    @PostMapping
    public Result<FileTransferTaskDefinitionView> create(
            @Valid @RequestBody FileTransferTaskSaveRequest request) {
        request.setId(null);
        return Result.success(taskService.save(request));
    }

    @PutMapping("/{id}")
    public Result<FileTransferTaskDefinitionView> update(@PathVariable("id") Long id,
                                                         @Valid @RequestBody FileTransferTaskSaveRequest request) {
        request.setId(id);
        return Result.success(taskService.save(request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        taskService.delete(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/validate")
    public Result<Map<String, Object>> validate(@PathVariable("id") Long id) {
        return Result.success(taskService.validate(id));
    }

    @PostMapping("/{id}/preview-selection")
    public Result<FileTransferSelectionPreviewView> preview(
            @PathVariable("id") Long id,
            @RequestBody(required = false) FileTransferPreviewRequest request) {
        FileTransferTaskDefinitionView task = taskService.get(id);
        taskService.validate(id);
        DataSourceDefinition datasource = dataSourceService.requireRunnableForExecution(task.getSourceDatasourceId());
        int limit = request == null || request.getLimit() == null
                ? 200 : Math.max(1, Math.min(1000, request.getLimit()));
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        spec.put("selection", task.getSelection());
        spec.put("mapping", task.getMapping());
        spec.put("policy", task.getPolicy());
        spec.put("runtime", task.getRuntime());
        spec.put("timeZone", task.getSchedule() == null || task.getSchedule().getTimezone() == null
                ? "Asia/Shanghai" : task.getSchedule().getTimezone());
        return Result.success(runtimeDatasourceProbeRouter.previewFileSelection(datasource,
                task.getSourceRuntimeClusterId(), spec,
                request == null ? null : request.getParameters(), limit));
    }

    @PostMapping("/{id}/publish")
    public Result<FileTransferTaskDefinitionView> publish(@PathVariable("id") Long id) {
        return Result.success(taskService.publish(id));
    }

    @PostMapping("/{id}/offline")
    public Result<FileTransferTaskDefinitionView> offline(@PathVariable("id") Long id) {
        return Result.success(taskService.offline(id));
    }

    @PostMapping("/{id}/trigger")
    public Result<FileTransferRunView> trigger(@PathVariable("id") Long id) {
        return Result.success(runService.triggerTask(id, "MANUAL_TASK", null));
    }
}
