package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.request.FileTransferTaskSaveRequest;
import com.jdragon.studio.infra.entity.FileTransferTaskDefinitionEntity;
import com.jdragon.studio.infra.mapper.FileTransferTaskDefinitionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferTaskAclTest {

    @Test
    void saveChecksSourceDownloadAndTargetEditPermissions() {
        Fixture fixture = fixture();

        fixture.service.save(request());

        verify(fixture.permissionService).assertPermission(
                11L, 21L, "/source", UnstructuredAclPermission.DOWNLOAD);
        verify(fixture.permissionService).assertPermission(
                11L, 22L, "/target", UnstructuredAclPermission.EDIT);
        verify(fixture.mapper).insert(any(FileTransferTaskDefinitionEntity.class));
    }

    @Test
    void deniedTargetCannotBeSavedAsPresetTask() {
        Fixture fixture = fixture();
        when(fixture.permissionService.assertPermission(
                11L, 22L, "/target", UnstructuredAclPermission.EDIT))
                .thenThrow(new StudioException(StudioErrorCode.FORBIDDEN, "target denied"));

        assertThatThrownBy(() -> fixture.service.save(request()))
                .isInstanceOfSatisfying(StudioException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(StudioErrorCode.FORBIDDEN));
        verify(fixture.mapper, never()).insert(any(FileTransferTaskDefinitionEntity.class));
    }

    @Test
    void executionIsDeniedAfterSourcePermissionIsRevoked() {
        Fixture fixture = fixture();
        FileTransferTaskDefinitionEntity task = onlineTask();
        when(fixture.mapper.selectById(701L)).thenReturn(task);
        when(fixture.permissionService.assertPermission(
                11L, 21L, "/source", UnstructuredAclPermission.DOWNLOAD))
                .thenThrow(new StudioException(StudioErrorCode.FORBIDDEN, "source permission revoked"));

        assertThatThrownBy(() -> fixture.service.requireOnlineForExecution(701L))
                .isInstanceOfSatisfying(StudioException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(StudioErrorCode.FORBIDDEN));
        verify(fixture.permissionService, never()).assertPermission(
                11L, 22L, "/target", UnstructuredAclPermission.EDIT);
    }

    private Fixture fixture() {
        FileTransferTaskDefinitionMapper mapper = mock(FileTransferTaskDefinitionMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        RuntimeClusterSelectionService clusterSelection = mock(RuntimeClusterSelectionService.class);
        ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        UnstructuredManagementService permissionService = mock(UnstructuredManagementService.class);
        DatasourceTypeCapabilityService capabilityService = mock(DatasourceTypeCapabilityService.class);
        when(projectAccess.requireCurrentProjectId()).thenReturn(10L);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(99L);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(dataSourceService.getInternalForProject(10L, 21L)).thenReturn(datasource(21L, "source"));
        when(dataSourceService.getInternalForProject(10L, 22L)).thenReturn(datasource(22L, "target"));
        when(permissionService.assertPermission(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        FileTransferTaskService service = new FileTransferTaskService(
                mapper, dataSourceService, clusterSelection, projectAccess, securityService,
                permissionService, capabilityService, new ObjectMapper());
        return new Fixture(service, mapper, permissionService);
    }

    private FileTransferTaskSaveRequest request() {
        FileTransferTaskSaveRequest request = new FileTransferTaskSaveRequest();
        request.setName("daily-files");
        request.setCode("daily_files");
        request.setRuntimeClusterId(11L);
        request.setSourceDatasourceId(21L);
        request.setTargetDatasourceId(22L);
        request.setSelection(Map.of("rootPath", "/source"));
        request.setMapping(Map.of("targetRootPath", "/target"));
        return request;
    }

    private DataSourceDefinition datasource(Long id, String name) {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(id);
        datasource.setTenantId("default");
        datasource.setProjectId(10L);
        datasource.setName(name);
        datasource.setTypeCode("oss");
        return datasource;
    }

    private FileTransferTaskDefinitionEntity onlineTask() {
        FileTransferTaskDefinitionEntity task = new FileTransferTaskDefinitionEntity();
        task.setId(701L);
        task.setTenantId("default");
        task.setProjectId(10L);
        task.setStatus("ONLINE");
        task.setPublishedVersion(1);
        task.setPublishedSnapshotJson(Map.of("schemaVersion", 1));
        task.setRuntimeClusterId(11L);
        task.setSourceRuntimeClusterId(11L);
        task.setTargetRuntimeClusterId(11L);
        task.setSourceDatasourceId(21L);
        task.setTargetDatasourceId(22L);
        task.setSelectionJson(Map.of("rootPath", "/source"));
        task.setMappingJson(Map.of("targetRootPath", "/target"));
        return task;
    }

    private record Fixture(FileTransferTaskService service,
                           FileTransferTaskDefinitionMapper mapper,
                           UnstructuredManagementService permissionService) {
    }
}
