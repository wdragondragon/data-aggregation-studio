package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.request.CollectionTaskSaveRequest;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchService;
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

@Tag(name = "Collection Tasks", description = "Collection task management APIs")
@RestController
@RequestMapping("/api/v1/collection-tasks")
public class CollectionTaskController {

    private final CollectionTaskService collectionTaskService;
    private final DispatchService dispatchService;

    public CollectionTaskController(CollectionTaskService collectionTaskService,
                                    DispatchService dispatchService) {
        this.collectionTaskService = collectionTaskService;
        this.dispatchService = dispatchService;
    }

    @Operation(summary = "List collection tasks")
    @GetMapping
    public Result<List<CollectionTaskDefinitionView>> list(@RequestParam(value = "name", required = false) String name,
                                                           @RequestParam(value = "targetDatasource", required = false) String targetDatasource,
                                                           @RequestParam(value = "targetModel", required = false) String targetModel) {
        return Result.success(collectionTaskService.list(name, targetDatasource, targetModel));
    }

    @Operation(summary = "List online collection tasks")
    @GetMapping("/online")
    public Result<List<CollectionTaskDefinitionView>> listOnline() {
        return Result.success(collectionTaskService.listOnline());
    }

    @Operation(summary = "Get collection task detail")
    @GetMapping("/{id}")
    public Result<CollectionTaskDefinitionView> get(@PathVariable("id") Long id) {
        return Result.success(collectionTaskService.get(id));
    }

    @Operation(summary = "Create or update collection task")
    @PostMapping
    public Result<CollectionTaskDefinitionView> save(@Valid @RequestBody CollectionTaskSaveRequest request) {
        return Result.success(collectionTaskService.save(request));
    }

    @Operation(summary = "Publish collection task")
    @PostMapping("/{id}/online")
    public Result<CollectionTaskDefinitionView> publish(@PathVariable("id") Long id) {
        return Result.success(collectionTaskService.publish(id));
    }

    @Operation(summary = "Update collection task schedule")
    @PostMapping("/{id}/schedule")
    public Result<CollectionTaskDefinitionView> updateSchedule(@PathVariable("id") Long id,
                                                               @RequestBody(required = false) CollectionTaskScheduleDefinition request) {
        return Result.success(collectionTaskService.updateSchedule(id, request));
    }

    @Operation(summary = "Trigger collection task")
    @PostMapping("/{id}/trigger")
    public Result<CollectionTaskDefinitionView> trigger(@PathVariable("id") Long id) {
        dispatchService.triggerCollectionTask(id);
        return Result.success(collectionTaskService.get(id));
    }

    @Operation(summary = "Delete collection task")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        collectionTaskService.delete(id);
        return Result.success(null);
    }
}
