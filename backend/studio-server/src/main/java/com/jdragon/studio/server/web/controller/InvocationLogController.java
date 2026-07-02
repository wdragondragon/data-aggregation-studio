package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.service.OpenServiceInvocationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Invocation Logs", description = "Synchronous open invocation log APIs")
@RestController
@RequestMapping("/api/v1/invocation-logs")
public class InvocationLogController {

    private final OpenServiceInvocationLogService invocationLogService;

    public InvocationLogController(OpenServiceInvocationLogService invocationLogService) {
        this.invocationLogService = invocationLogService;
    }

    @Operation(summary = "Get invocation log page")
    @GetMapping("/{domain}/{accessLogId}")
    public Result<RunLogView> log(@PathVariable("domain") String domain,
                                  @PathVariable("accessLogId") Long accessLogId,
                                  @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                  @RequestParam(value = "pageSizeBytes", required = false) Integer pageSizeBytes) {
        return Result.success(invocationLogService.viewLog(domain, accessLogId, pageNo, pageSizeBytes));
    }

    @Operation(summary = "Download full invocation log")
    @GetMapping("/{domain}/{accessLogId}/download")
    public Result<RunLogView> download(@PathVariable("domain") String domain,
                                       @PathVariable("accessLogId") Long accessLogId) {
        return Result.success(invocationLogService.downloadLog(domain, accessLogId));
    }

    @Operation(summary = "Get invocation log section page")
    @GetMapping("/{domain}/{accessLogId}/sections/{sectionKey}")
    public Result<RunLogView> sectionLog(@PathVariable("domain") String domain,
                                         @PathVariable("accessLogId") Long accessLogId,
                                         @PathVariable("sectionKey") String sectionKey,
                                         @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                         @RequestParam(value = "pageSizeBytes", required = false) Integer pageSizeBytes) {
        return Result.success(invocationLogService.viewLogSection(domain, accessLogId, sectionKey, pageNo, pageSizeBytes));
    }

    @Operation(summary = "Download full invocation log section")
    @GetMapping("/{domain}/{accessLogId}/sections/{sectionKey}/download")
    public Result<RunLogView> downloadSection(@PathVariable("domain") String domain,
                                              @PathVariable("accessLogId") Long accessLogId,
                                              @PathVariable("sectionKey") String sectionKey) {
        return Result.success(invocationLogService.downloadLogSection(domain, accessLogId, sectionKey));
    }
}
