package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.EnvironmentDependencyOptionView;
import com.jdragon.studio.dto.model.EnvironmentDependencyView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.EnvironmentDependencySaveRequest;
import com.jdragon.studio.infra.service.EnvironmentDependencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Tag(name = "Environment Dependencies", description = "Script environment dependency APIs")
@RestController
@RequestMapping("/api/v1/environment-dependencies")
public class EnvironmentDependencyController {

    private final EnvironmentDependencyService environmentDependencyService;

    public EnvironmentDependencyController(EnvironmentDependencyService environmentDependencyService) {
        this.environmentDependencyService = environmentDependencyService;
    }

    @Operation(summary = "Query environment dependencies")
    @PostMapping("/queryPage")
    public Result<PageView<EnvironmentDependencyView>> queryPage(@RequestParam(value = "pageNum", required = false) Integer pageNum,
                                                                 @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                                 @RequestParam(value = "keyword", required = false) String keyword,
                                                                 @RequestParam(value = "enabled", required = false) Boolean enabled) {
        return Result.success(environmentDependencyService.queryPage(pageNum, pageSize, keyword, enabled));
    }

    @Operation(summary = "List selectable environment dependencies")
    @GetMapping("/options")
    public Result<List<EnvironmentDependencyOptionView>> options(@RequestParam(value = "enabledOnly", required = false, defaultValue = "true") Boolean enabledOnly) {
        return Result.success(environmentDependencyService.options(enabledOnly));
    }

    @Operation(summary = "Get environment dependency")
    @GetMapping("/{id}")
    public Result<EnvironmentDependencyView> get(@PathVariable("id") Long id) {
        return Result.success(environmentDependencyService.get(id));
    }

    @Operation(summary = "Create or update environment dependency")
    @PostMapping(value = "/saveOrUpdateCheck", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<EnvironmentDependencyView> saveOrUpdateCheck(@Valid @RequestBody EnvironmentDependencySaveRequest request) {
        return Result.success(environmentDependencyService.saveOrUpdateCheck(request));
    }

    @Operation(summary = "Create or update environment dependency with artifact upload")
    @PostMapping(value = "/saveOrUpdateCheck", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<EnvironmentDependencyView> saveOrUpdateCheckMultipart(@RequestParam(value = "id", required = false) Long id,
                                                                        @RequestParam("name") String name,
                                                                        @RequestParam(value = "version", required = false) String version,
                                                                        @RequestParam(value = "scriptType", required = false) String scriptType,
                                                                        @RequestParam(value = "enabled", required = false) Boolean enabled,
                                                                        @RequestParam(value = "description", required = false) String description,
                                                                        @RequestParam(value = "file", required = false) MultipartFile file,
                                                                        @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        List<MultipartFile> uploadFiles = new ArrayList<MultipartFile>();
        if (file != null) {
            uploadFiles.add(file);
        }
        if (files != null) {
            uploadFiles.addAll(files);
        }
        return Result.success(environmentDependencyService.saveOrUpdateCheck(id, name, version, scriptType, enabled, description, uploadFiles));
    }

    @Operation(summary = "Download uploaded dependency file")
    @GetMapping("/{dependencyId}/files/{fileId}/download")
    public ResponseEntity<byte[]> downloadFile(@PathVariable("dependencyId") Long dependencyId,
                                               @PathVariable("fileId") Long fileId) {
        EnvironmentDependencyService.DependencyFileDownload download = environmentDependencyService.downloadFile(dependencyId, fileId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.getFileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(download.getContentType()))
                .body(download.getBytes());
    }

    @Operation(summary = "Delete uploaded dependency file")
    @DeleteMapping("/{dependencyId}/files/{fileId}")
    public Result<Void> deleteFile(@PathVariable("dependencyId") Long dependencyId,
                                   @PathVariable("fileId") Long fileId) {
        environmentDependencyService.deleteFile(dependencyId, fileId);
        return Result.success(null);
    }

    @Operation(summary = "Enable environment dependency")
    @PostMapping("/{id}/enable")
    public Result<EnvironmentDependencyView> enable(@PathVariable("id") Long id) {
        return Result.success(environmentDependencyService.enable(id));
    }

    @Operation(summary = "Disable environment dependency")
    @PostMapping("/{id}/disable")
    public Result<EnvironmentDependencyView> disable(@PathVariable("id") Long id) {
        return Result.success(environmentDependencyService.disable(id));
    }

    @Operation(summary = "Delete environment dependency")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        environmentDependencyService.delete(id);
        return Result.success(null);
    }
}
