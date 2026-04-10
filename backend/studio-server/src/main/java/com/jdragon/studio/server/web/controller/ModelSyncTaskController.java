package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.ModelSyncTaskItemView;
import com.jdragon.studio.dto.model.ModelSyncTaskView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.ModelSyncTaskCreateRequest;
import com.jdragon.studio.infra.service.ModelSyncTaskService;
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

@Tag(name = "Model Sync Tasks", description = "Async model sync task APIs")
@RestController
@RequestMapping("/api/v1/model-sync-tasks")
public class ModelSyncTaskController {

    private final ModelSyncTaskService modelSyncTaskService;

    public ModelSyncTaskController(ModelSyncTaskService modelSyncTaskService) {
        this.modelSyncTaskService = modelSyncTaskService;
    }

    @Operation(summary = "Create and start a model sync task")
    @PostMapping
    public Result<ModelSyncTaskView> create(@Valid @RequestBody ModelSyncTaskCreateRequest request) {
        return Result.success(modelSyncTaskService.createAndStart(request));
    }

    @Operation(summary = "List model sync tasks")
    @GetMapping
    public Result<PageView<ModelSyncTaskView>> list(@RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                    @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
                                                    @RequestParam(value = "datasourceType", required = false) String datasourceType,
                                                    @RequestParam(value = "datasourceId", required = false) Long datasourceId,
                                                    @RequestParam(value = "status", required = false) String status) {
        return Result.success(modelSyncTaskService.list(pageNo, pageSize, datasourceType, datasourceId, status));
    }

    @Operation(summary = "Get model sync task detail")
    @GetMapping("/{taskId}")
    public Result<ModelSyncTaskView> get(@PathVariable("taskId") Long taskId) {
        return Result.success(modelSyncTaskService.get(taskId));
    }

    @Operation(summary = "List model sync task items")
    @GetMapping("/{taskId}/items")
    public Result<PageView<ModelSyncTaskItemView>> listItems(@PathVariable("taskId") Long taskId,
                                                             @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                             @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
                                                             @RequestParam(value = "keyword", required = false) String keyword,
                                                             @RequestParam(value = "status", required = false) String status) {
        return Result.success(modelSyncTaskService.listItems(taskId, pageNo, pageSize, keyword, status));
    }

    @Operation(summary = "Request model sync task stop")
    @PostMapping("/{taskId}/stop")
    public Result<ModelSyncTaskView> stop(@PathVariable("taskId") Long taskId) {
        return Result.success(modelSyncTaskService.stop(taskId));
    }

    @Operation(summary = "Delete model sync task record")
    @DeleteMapping("/{taskId}")
    public Result<Void> delete(@PathVariable("taskId") Long taskId) {
        modelSyncTaskService.delete(taskId);
        return Result.success(null);
    }
}
