package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.ManagedFileAuditView;
import com.jdragon.studio.dto.model.ManagedFileMigrationIssueView;
import com.jdragon.studio.dto.model.ManagedFileReferenceView;
import com.jdragon.studio.dto.model.ManagedFileView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.service.ManagedFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "Managed Files", description = "Project-scoped encrypted managed file APIs")
@RestController
@RequestMapping("/api/v1/managed-files")
public class ManagedFileController {

    private final ManagedFileService managedFileService;

    public ManagedFileController(ManagedFileService managedFileService) {
        this.managedFileService = managedFileService;
    }

    @Operation(summary = "Upload a managed file")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ManagedFileView> upload(@RequestParam("file") MultipartFile file,
                                          @RequestParam("policyCode") String policyCode) {
        return Result.success(managedFileService.upload(file, policyCode));
    }

    @Operation(summary = "Query managed files")
    @PostMapping("/queryPage")
    public Result<PageView<ManagedFileView>> queryPage(
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "policyCode", required = false) String policyCode,
            @RequestParam(value = "status", required = false) String status) {
        return Result.success(managedFileService.queryPage(pageNum, pageSize, policyCode, status));
    }

    @Operation(summary = "Get managed file metadata")
    @GetMapping("/{id}")
    public Result<ManagedFileView> get(@PathVariable("id") Long id) {
        return Result.success(managedFileService.get(id));
    }

    @Operation(summary = "List managed file references")
    @GetMapping("/{id}/references")
    public Result<List<ManagedFileReferenceView>> references(@PathVariable("id") Long id) {
        return Result.success(managedFileService.references(id));
    }

    @Operation(summary = "Download a managed file")
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable("id") Long id) {
        ManagedFileService.Download download = managedFileService.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.getFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(download.getContentType()))
                .body(download.getBytes());
    }

    @Operation(summary = "Delete an unreferenced managed file")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        managedFileService.requestDelete(id);
        return Result.success(null);
    }

    @Operation(summary = "Query managed file audits")
    @PostMapping("/audits/queryPage")
    public Result<PageView<ManagedFileAuditView>> queryAudits(
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return Result.success(managedFileService.queryAudits(pageNum, pageSize, fileId));
    }

    @Operation(summary = "List datasource fields that still use legacy local file paths")
    @GetMapping("/migration-issues")
    public Result<List<ManagedFileMigrationIssueView>> migrationIssues() {
        return Result.success(managedFileService.migrationIssues());
    }
}
