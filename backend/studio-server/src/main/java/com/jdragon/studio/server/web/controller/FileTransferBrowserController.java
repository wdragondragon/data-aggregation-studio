package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FileTransferBrowserPageView;
import com.jdragon.studio.dto.model.request.FileTransferBrowserRequest;
import com.jdragon.studio.infra.service.UnstructuredManagementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/file-transfer/browser")
public class FileTransferBrowserController {

    private final UnstructuredManagementService unstructuredManagementService;

    public FileTransferBrowserController(UnstructuredManagementService unstructuredManagementService) {
        this.unstructuredManagementService = unstructuredManagementService;
    }

    @PostMapping("/list")
    public Result<FileTransferBrowserPageView> list(@Valid @RequestBody FileTransferBrowserRequest request) {
        return Result.success(unstructuredManagementService.browse(
                request.getRuntimeClusterId(), request.getDatasourceId(), request.getPath(),
                request.getCursor(), request.getPageSize()));
    }
}
