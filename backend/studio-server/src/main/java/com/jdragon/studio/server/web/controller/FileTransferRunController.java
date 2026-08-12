package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FileTransferRunItemView;
import com.jdragon.studio.dto.model.FileTransferRunView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.FileTransferManualItemRequest;
import com.jdragon.studio.dto.model.request.FileTransferManualRunRequest;
import com.jdragon.studio.infra.service.FileTransferEventService;
import com.jdragon.studio.infra.service.FileTransferRunService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/file-transfer/runs")
public class FileTransferRunController {

    private final FileTransferRunService runService;
    private final FileTransferEventService eventService;

    public FileTransferRunController(FileTransferRunService runService,
                                     FileTransferEventService eventService) {
        this.runService = runService;
        this.eventService = eventService;
    }

    @PostMapping("/manual")
    public Result<FileTransferRunView> createManual(
            @Valid @RequestBody FileTransferManualRunRequest request) {
        return changed(runService.createManualRun(request));
    }

    @PostMapping("/{runId}/items")
    public Result<FileTransferRunView> addItems(@PathVariable("runId") Long runId,
                                                @Valid @RequestBody List<FileTransferManualItemRequest> items) {
        return changed(runService.addManualItems(runId, items));
    }

    @GetMapping
    public Result<PageView<FileTransferRunView>> list(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "taskId", required = false) Long taskId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "triggerType", required = false) String triggerType,
            @RequestParam(value = "statusGroup", required = false) String statusGroup) {
        return Result.success(runService.listPage(pageNo, pageSize, taskId, status, triggerType, statusGroup));
    }

    @GetMapping("/{runId}")
    public Result<FileTransferRunView> get(@PathVariable("runId") Long runId) {
        return Result.success(runService.get(runId));
    }

    @GetMapping("/{runId}/items")
    public Result<PageView<FileTransferRunItemView>> items(
            @PathVariable("runId") Long runId,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(runService.listItems(runId, pageNo, pageSize));
    }

    @PostMapping("/{runId}/pause")
    public Result<FileTransferRunView> pause(@PathVariable("runId") Long runId) {
        return changed(runService.pause(runId));
    }

    @PostMapping("/{runId}/resume")
    public Result<FileTransferRunView> resume(@PathVariable("runId") Long runId) {
        return changed(runService.resume(runId));
    }

    @PostMapping("/{runId}/cancel")
    public Result<FileTransferRunView> cancel(@PathVariable("runId") Long runId) {
        return changed(runService.cancel(runId));
    }

    @PostMapping("/{runId}/items/{itemId}/retry")
    public Result<FileTransferRunView> retry(@PathVariable("runId") Long runId,
                                             @PathVariable("itemId") Long itemId) {
        return changed(runService.retryItem(runId, itemId));
    }

    @DeleteMapping("/{runId}")
    public Result<Void> remove(@PathVariable("runId") Long runId) {
        runService.removeManualRun(runId);
        eventService.publishRunRemoved(runId);
        return Result.success(null);
    }

    @DeleteMapping("/{runId}/items/{itemId}")
    public Result<Void> removeItem(@PathVariable("runId") Long runId,
                                   @PathVariable("itemId") Long itemId) {
        runService.removeManualItem(runId, itemId);
        eventService.publishItemRemoved(runId, itemId);
        eventService.publishRunChanged(runId);
        return Result.success(null);
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return eventService.connect();
    }

    private Result<FileTransferRunView> changed(FileTransferRunView run) {
        if (run != null && run.getId() != null) {
            eventService.publishRunChanged(run.getId());
        }
        return Result.success(run);
    }
}
