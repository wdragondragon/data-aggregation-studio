package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.QualityTaskDefinitionView;
import com.jdragon.studio.dto.model.QualityTaskPreviewView;
import com.jdragon.studio.dto.model.QualityTaskValidationView;
import com.jdragon.studio.dto.model.request.QualityTaskSaveRequest;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.QualityTaskService;
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

@Tag(name = "Quality Tasks", description = "Quality task management APIs")
@RestController
@RequestMapping("/api/v1/quality-tasks")
public class QualityTaskController {

    private final QualityTaskService qualityTaskService;
    private final DispatchService dispatchService;

    public QualityTaskController(QualityTaskService qualityTaskService,
                                 DispatchService dispatchService) {
        this.qualityTaskService = qualityTaskService;
        this.dispatchService = dispatchService;
    }

    @Operation(summary = "List quality tasks")
    @GetMapping
    public Result<PageView<QualityTaskDefinitionView>> list(@RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                        @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                        @RequestParam(value = "keyword", required = false) String keyword,
                                                        @RequestParam(value = "status", required = false) String status,
                                                        @RequestParam(value = "ruleDimension", required = false) String ruleDimension,
                                                        @RequestParam(value = "granularity", required = false) String granularity) {
        return Result.success(qualityTaskService.list(pageNo, pageSize, keyword, status, ruleDimension, granularity));
    }

    @Operation(summary = "List online quality tasks")
    @GetMapping("/online")
    public Result<List<QualityTaskDefinitionView>> listOnline() {
        return Result.success(qualityTaskService.listOnline());
    }

    @Operation(summary = "Get quality task detail")
    @GetMapping("/{id}")
    public Result<QualityTaskDefinitionView> get(@PathVariable("id") Long id) {
        return Result.success(qualityTaskService.get(id));
    }

    @Operation(summary = "Create or update quality task")
    @PostMapping
    public Result<QualityTaskDefinitionView> save(@Valid @RequestBody QualityTaskSaveRequest request) {
        return Result.success(qualityTaskService.save(request));
    }

    @Operation(summary = "Preview resolved SQL")
    @PostMapping("/preview")
    public Result<QualityTaskPreviewView> preview(@RequestBody QualityTaskSaveRequest request) {
        return Result.success(qualityTaskService.preview(request));
    }

    @Operation(summary = "Validate quality task SQL against datasource")
    @PostMapping("/validate")
    public Result<QualityTaskValidationView> validate(@RequestBody QualityTaskSaveRequest request) {
        return Result.success(qualityTaskService.validate(request));
    }

    @Operation(summary = "Publish quality task")
    @PostMapping("/{id}/online")
    public Result<QualityTaskDefinitionView> publish(@PathVariable("id") Long id) {
        return Result.success(qualityTaskService.publish(id));
    }

    @Operation(summary = "Update quality task schedule")
    @PostMapping("/{id}/schedule")
    public Result<QualityTaskDefinitionView> updateSchedule(@PathVariable("id") Long id,
                                                            @RequestBody(required = false) CollectionTaskScheduleDefinition schedule) {
        return Result.success(qualityTaskService.updateSchedule(id, schedule));
    }

    @Operation(summary = "Trigger quality task")
    @PostMapping("/{id}/trigger")
    public Result<QualityTaskDefinitionView> trigger(@PathVariable("id") Long id) {
        dispatchService.triggerQualityTask(id);
        return Result.success(qualityTaskService.get(id));
    }

    @Operation(summary = "Delete quality task")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        qualityTaskService.delete(id);
        return Result.success(null);
    }
}

