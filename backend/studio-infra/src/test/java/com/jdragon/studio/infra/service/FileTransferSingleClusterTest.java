package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.request.FileTransferManualItemRequest;
import com.jdragon.studio.dto.model.request.FileTransferManualRunRequest;
import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class FileTransferSingleClusterTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(FileTransferRunEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    FileTransferRunEntity.class);
        }
    }

    @Test
    void rejectsLegacyManualRequestWhoseEndpointsUseDifferentClusters() {
        Fixture fixture = fixture();
        FileTransferManualItemRequest item = item();
        item.setSourceRuntimeClusterId(11L);
        item.setTargetRuntimeClusterId(12L);
        FileTransferManualRunRequest request = new FileTransferManualRunRequest();
        request.setItems(List.of(item));

        assertThatThrownBy(() -> fixture.service.createManualRun(request))
                .isInstanceOfSatisfying(StudioException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(StudioErrorCode.FILE_TRANSFER_CROSS_CLUSTER_DISABLED));
    }

    @Test
    void writesCanonicalRuntimeClusterForNewManualRun() {
        Fixture fixture = fixture();
        FileTransferManualItemRequest item = item();
        FileTransferManualRunRequest request = new FileTransferManualRunRequest();
        request.setRuntimeClusterId(11L);
        request.setItems(List.of(item));

        fixture.service.createManualRun(request);

        ArgumentCaptor<FileTransferRunEntity> captor = ArgumentCaptor.forClass(FileTransferRunEntity.class);
        verify(fixture.runMapper).insert(captor.capture());
        FileTransferRunEntity run = captor.getValue();
        assertThat(run.getRuntimeClusterId()).isEqualTo(11L);
        assertThat(run.getSourceRuntimeClusterId()).isEqualTo(11L);
        assertThat(run.getTargetRuntimeClusterId()).isEqualTo(11L);
        assertThat(run.getChannel()).isEqualTo("LOCAL_WORKER");
        assertThat(request.getItems().get(0).getRuntimeClusterId()).isEqualTo(11L);
        verify(fixture.permissionService).assertPermission(11L, 21L,
                "/source/file.bin", UnstructuredAclPermission.DOWNLOAD);
        verify(fixture.permissionService).assertPermission(11L, 22L,
                "/target/file.bin", UnstructuredAclPermission.EDIT);
    }

    @Test
    void historicalCrossClusterRunCannotResume() {
        Fixture fixture = fixture();
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(100L);
        run.setTenantId("default");
        run.setProjectId(10L);
        run.setTriggerType("MANUAL");
        run.setStatus("FAILED");
        run.setSourceRuntimeClusterId(11L);
        run.setTargetRuntimeClusterId(12L);
        when(fixture.runMapper.selectById(100L)).thenReturn(run);

        assertThatThrownBy(() -> fixture.service.resume(100L))
                .isInstanceOfSatisfying(StudioException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(StudioErrorCode.FILE_TRANSFER_CROSS_CLUSTER_DISABLED));
    }

    @Test
    void deniedSourceCannotBeAddedToManualQueue() {
        Fixture fixture = fixture();
        when(fixture.permissionService.assertPermission(11L, 21L,
                "/source/file.bin", UnstructuredAclPermission.DOWNLOAD))
                .thenThrow(new StudioException(StudioErrorCode.FORBIDDEN, "source denied"));
        FileTransferManualRunRequest request = new FileTransferManualRunRequest();
        request.setRuntimeClusterId(11L);
        request.setItems(List.of(item()));

        assertThatThrownBy(() -> fixture.service.createManualRun(request))
                .isInstanceOfSatisfying(StudioException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(StudioErrorCode.FORBIDDEN));
        verify(fixture.runMapper, never()).insert(any(FileTransferRunEntity.class));
    }

    @Test
    void activeQueueQueryIsFilteredBeforePagination() {
        Fixture fixture = fixture();
        when(fixture.runMapper.selectPage(any(), any()))
                .thenReturn(new Page<FileTransferRunEntity>(1, 200));

        fixture.service.listPage(1, 200, null, null, "MANUAL", "ACTIVE");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<FileTransferRunEntity>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fixture.runMapper).selectPage(any(), query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("status IN");
        assertThat(query.getValue().getParamNameValuePairs().values())
                .contains("MANUAL", "QUEUED", "RUNNING", "PAUSED");
    }

    @Test
    void rejectsUnknownQueueStatusGroup() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.listPage(1, 10, null, null,
                "MANUAL", "HISTORY"))
                .isInstanceOfSatisfying(StudioException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(StudioErrorCode.BAD_REQUEST));
    }

    private FileTransferManualItemRequest item() {
        FileTransferManualItemRequest item = new FileTransferManualItemRequest();
        item.setSourceDatasourceId(21L);
        item.setSourcePath("/source/file.bin");
        item.setTargetDatasourceId(22L);
        item.setTargetPath("/target/file.bin");
        item.setRecursive(Boolean.FALSE);
        return item;
    }

    private Fixture fixture() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(projectAccess.requireCurrentProjectId()).thenReturn(10L);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(99L);
        UnstructuredManagementService permissionService = permissionService();
        FileTransferRunService service = new FileTransferRunService(
                runMapper,
                mock(FileTransferRunItemMapper.class),
                mock(DispatchTaskMapper.class),
                mock(FileTransferTaskService.class),
                mock(DataSourceService.class),
                mock(RuntimeClusterSelectionService.class),
                projectAccess,
                securityService,
                permissionService,
                mock(ClusterLockService.class),
                new ObjectMapper());
        return new Fixture(service, runMapper, permissionService);
    }

    private UnstructuredManagementService permissionService() {
        UnstructuredManagementService permissionService = mock(UnstructuredManagementService.class);
        when(permissionService.assertPermission(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        return permissionService;
    }

    private record Fixture(FileTransferRunService service, FileTransferRunMapper runMapper,
                           UnstructuredManagementService permissionService) {
    }
}
