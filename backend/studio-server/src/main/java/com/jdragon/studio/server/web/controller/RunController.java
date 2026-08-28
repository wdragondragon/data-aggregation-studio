package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.dto.model.RunListView;
import com.jdragon.studio.dto.model.RunRecordListView;
import com.jdragon.studio.dto.model.RunRecordPageView;
import com.jdragon.studio.dto.model.RunRecordView;
import com.jdragon.studio.dto.model.RunTerminationView;
import com.jdragon.studio.infra.service.RunService;
import com.jdragon.studio.infra.service.RunTerminationService;
import com.jdragon.studio.server.web.service.RunLogProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.time.LocalDateTime;

@Tag(name = "Runs", description = "Workflow run and dispatch APIs")
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunService runService;
    private final RunLogProxyService runLogProxyService;
    private final RunTerminationService runTerminationService;

    public RunController(RunService runService,
                         RunLogProxyService runLogProxyService,
                         RunTerminationService runTerminationService) {
        this.runService = runService;
        this.runLogProxyService = runLogProxyService;
        this.runTerminationService = runTerminationService;
    }

    @Operation(summary = "List queued tasks and run records")
    @GetMapping
    public Result<RunListView> list(@RequestParam(value = "collectionTaskId", required = false) Long collectionTaskId,
                                    @RequestParam(value = "qualityTaskId", required = false) Long qualityTaskId,
                                    @RequestParam(value = "workflowDefinitionId", required = false) Long workflowDefinitionId,
                                    @RequestParam(value = "requestedClusterId", required = false) Long requestedClusterId,
                                    @RequestParam(value = "actualClusterId", required = false) Long actualClusterId,
                                    @RequestParam(value = "startTime", required = false)
                                    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                                    @RequestParam(value = "endTime", required = false)
                                    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
                                    @RequestParam(value = "includeRunRecords", required = false) Boolean includeRunRecords) {
        return Result.success(runService.list(collectionTaskId, qualityTaskId, workflowDefinitionId,
                requestedClusterId, actualClusterId, startTime, endTime, includeRunRecords));
    }

    @Operation(summary = "List run records by page")
    @GetMapping("/page")
    public Result<RunRecordPageView> listPage(@RequestParam(value = "collectionTaskId", required = false) Long collectionTaskId,
                                              @RequestParam(value = "qualityTaskId", required = false) Long qualityTaskId,
                                              @RequestParam(value = "workflowDefinitionId", required = false) Long workflowDefinitionId,
                                              @RequestParam(value = "collectionTaskOnly", required = false) Boolean collectionTaskOnly,
                                              @RequestParam(value = "qualityTaskOnly", required = false) Boolean qualityTaskOnly,
                                              @RequestParam(value = "status", required = false) String status,
                                              @RequestParam(value = "requestedClusterId", required = false) Long requestedClusterId,
                                              @RequestParam(value = "actualClusterId", required = false) Long actualClusterId,
                                              @RequestParam(value = "startTime", required = false)
                                              @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                                              @RequestParam(value = "endTime", required = false)
                                              @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
                                              @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                              @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(runService.listRunRecords(collectionTaskId, qualityTaskId, workflowDefinitionId,
                collectionTaskOnly, qualityTaskOnly, status, requestedClusterId, actualClusterId,
                startTime, endTime, pageNo, pageSize));
    }

    @Operation(summary = "Get run record detail")
    @GetMapping("/{id}")
    public Result<RunRecordView> get(@PathVariable("id") Long id) {
        return Result.success(runService.get(id));
    }

    @Operation(summary = "Get run record summary")
    @GetMapping("/{id}/summary")
    public Result<RunRecordListView> summary(@PathVariable("id") Long id) {
        return Result.success(runService.getSummary(id));
    }

    @Operation(summary = "Get run log tail")
    @GetMapping("/{id}/log")
    public Result<RunLogView> log(@PathVariable("id") Long id,
                                  @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                  @RequestParam(value = "pageSizeBytes", required = false) Integer pageSizeBytes) {
        return Result.success(runLogProxyService.viewLog(id, pageNo, pageSizeBytes));
    }

    @Operation(summary = "Download full run log")
    @GetMapping("/{id}/log/download")
    public Result<RunLogView> download(@PathVariable("id") Long id) {
        return Result.success(runLogProxyService.downloadLog(id));
    }

    @Operation(summary = "Stream all run log chunks as a ZIP archive")
    @GetMapping("/{id}/log/archive")
    public ResponseEntity<StreamingResponseBody> archive(@PathVariable("id") Long id) {
        String name = "run-" + id + ".zip";
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        StreamingResponseBody body = output -> runLogProxyService.streamArchive(id, output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @Operation(summary = "Terminate a queued or running run record")
    @PostMapping("/{id}/terminate")
    public Result<RunTerminationView> terminate(@PathVariable("id") Long id) {
        return Result.success(runTerminationService.terminateRunRecord(id));
    }
}
