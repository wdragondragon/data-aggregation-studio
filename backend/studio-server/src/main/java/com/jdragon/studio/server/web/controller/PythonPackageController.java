package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.EnvironmentDependencyListView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.PythonPackageSummaryView;
import com.jdragon.studio.infra.service.EnvironmentDependencyService;
import com.jdragon.studio.infra.service.PythonPackageDownloadCountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "Python Packages", description = "Tenant Python private package management APIs")
@RestController
@RequestMapping("/api/v1/python-packages")
public class PythonPackageController {

    private final EnvironmentDependencyService dependencyService;
    private final PythonPackageDownloadCountService downloadCountService;

    public PythonPackageController(EnvironmentDependencyService dependencyService,
                                   PythonPackageDownloadCountService downloadCountService) {
        this.dependencyService = dependencyService;
        this.downloadCountService = downloadCountService;
    }

    @Operation(summary = "Get today's total Python package download count")
    @GetMapping("/download-count/today")
    public Result<Long> todayDownloadCount() {
        return Result.success(Long.valueOf(downloadCountService.getToday()));
    }

    @Operation(summary = "Query Python packages grouped by normalized package name")
    @PostMapping("/queryPage")
    public Result<PageView<PythonPackageSummaryView>> queryPage(
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "enabled", required = false) Boolean enabled) {
        return Result.success(dependencyService.queryPythonPackages(pageNum, pageSize, keyword, enabled));
    }

    @Operation(summary = "Export exact Python package requirements")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(value = "keyword", required = false) String keyword,
                                         @RequestParam(value = "enabled", required = false) Boolean enabled) {
        byte[] content = dependencyService.exportPythonRequirements(keyword, enabled)
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("requirements.txt", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(content);
    }

    @Operation(summary = "List all versions of a Python package")
    @GetMapping("/{packageName}/versions")
    public Result<List<EnvironmentDependencyListView>> versions(@PathVariable("packageName") String packageName) {
        return Result.success(dependencyService.pythonPackageVersions(packageName));
    }
}
