package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferBrowserPageView;
import com.jdragon.studio.dto.model.request.FileTransferBrowserRequest;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;
import com.jdragon.studio.infra.service.RuntimeClusterSelectionService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeRouter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/file-transfer/browser")
public class FileTransferBrowserController {

    private final DataSourceService dataSourceService;
    private final RuntimeClusterSelectionService runtimeClusterSelectionService;
    private final DatasourceClusterBindingService datasourceClusterBindingService;
    private final RuntimeDatasourceProbeRouter runtimeDatasourceProbeRouter;

    public FileTransferBrowserController(DataSourceService dataSourceService,
                                         RuntimeClusterSelectionService runtimeClusterSelectionService,
                                         DatasourceClusterBindingService datasourceClusterBindingService,
                                         RuntimeDatasourceProbeRouter runtimeDatasourceProbeRouter) {
        this.dataSourceService = dataSourceService;
        this.runtimeClusterSelectionService = runtimeClusterSelectionService;
        this.datasourceClusterBindingService = datasourceClusterBindingService;
        this.runtimeDatasourceProbeRouter = runtimeDatasourceProbeRouter;
    }

    @PostMapping("/list")
    public Result<FileTransferBrowserPageView> list(@Valid @RequestBody FileTransferBrowserRequest request) {
        DataSourceDefinition datasource = dataSourceService.requireRunnableForExecution(request.getDatasourceId());
        runtimeClusterSelectionService.resolveForSave(datasource.getProjectId(), request.getRuntimeClusterId());
        datasourceClusterBindingService.assertDatasourceApplicable(
                request.getDatasourceId(), request.getRuntimeClusterId());
        return Result.success(runtimeDatasourceProbeRouter.browse(datasource,
                request.getRuntimeClusterId(), request.getPath(), request.getCursor(), request.getPageSize()));
    }
}
