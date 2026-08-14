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
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isNull;

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
    void resumeContinuesTheExistingRunningDispatchWithoutCreatingAnotherOwner() {
        Fixture fixture = fixture();
        FileTransferRunEntity run = resumableRun("PAUSED");
        FileTransferRunEntity resumedRun = resumableRun("RUNNING");
        resumedRun.setMessage("Resume requested; active transfer continues");
        when(fixture.runMapper.selectById(100L)).thenReturn(run, resumedRun, resumedRun);
        when(fixture.dispatchTaskMapper.selectCount(any()))
                .thenReturn(1L, 1L);
        when(fixture.runMapper.update(isNull(), any())).thenReturn(1);

        var resumed = fixture.service.resume(100L);

        assertThat(resumed.getStatus()).isEqualTo("RUNNING");
        assertThat(resumed.getMessage()).isEqualTo("Resume requested; active transfer continues");
        verify(fixture.dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }

    @Test
    void resumeQueuedRunRepairsMissingDispatchIdempotently() {
        Fixture fixture = fixture();
        FileTransferRunEntity run = resumableRun("QUEUED");
        FileTransferRunEntity resumedRun = resumableRun("QUEUED");
        resumedRun.setMessage("Resume requested; checkpoint recovery queued");
        when(fixture.runMapper.selectById(100L)).thenReturn(run, resumedRun, resumedRun);
        when(fixture.dispatchTaskMapper.selectCount(any()))
                .thenReturn(0L, 0L);
        when(fixture.runMapper.update(isNull(), any())).thenReturn(1);

        var resumed = fixture.service.resume(100L);

        assertThat(resumed.getStatus()).isEqualTo("QUEUED");
        assertThat(resumed.getMessage()).isEqualTo("Resume requested; checkpoint recovery queued");
        ArgumentCaptor<DispatchTaskEntity> dispatch = ArgumentCaptor.forClass(DispatchTaskEntity.class);
        verify(fixture.dispatchTaskMapper).insert(dispatch.capture());
        assertThat(dispatch.getValue().getFileTransferRunId()).isEqualTo(100L);
        assertThat(dispatch.getValue().getTargetClusterId()).isEqualTo(11L);
        assertThat(dispatch.getValue().getStatus()).isEqualTo("QUEUED");
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

    private FileTransferRunEntity resumableRun(String status) {
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(100L);
        run.setTenantId("default");
        run.setProjectId(10L);
        run.setTriggerType("MANUAL");
        run.setStatus(status);
        run.setRuntimeClusterId(11L);
        run.setSourceRuntimeClusterId(11L);
        run.setTargetRuntimeClusterId(11L);
        return run;
    }

    private Fixture fixture() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(projectAccess.requireCurrentProjectId()).thenReturn(10L);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(99L);
        UnstructuredManagementService permissionService = permissionService();
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        FileTransferStateMutationService mutationService = new FileTransferStateMutationService(
                runMapper, itemMapper, mock(FileTransferMetricSampleMapper.class),
                mock(FileTransferOutboxWriter.class));
        mutationService.setDispatchTaskMapper(dispatchTaskMapper);
        FileTransferRunService service = new FileTransferRunService(
                runMapper,
                itemMapper,
                dispatchTaskMapper,
                mock(FileTransferTaskService.class),
                mock(DataSourceService.class),
                mock(RuntimeClusterSelectionService.class),
                projectAccess,
                securityService,
                permissionService,
                passThroughLockService(),
                new ObjectMapper(),
                mutationService);
        return new Fixture(service, runMapper, dispatchTaskMapper, permissionService);
    }

    private ClusterLockService passThroughLockService() {
        ClusterLockService lockService = mock(ClusterLockService.class);
        when(lockService.executeIfAcquiredNonReentrant(any(), anyLong(), anyBoolean(), any(), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(3)).get());
        return lockService;
    }

    private UnstructuredManagementService permissionService() {
        UnstructuredManagementService permissionService = mock(UnstructuredManagementService.class);
        when(permissionService.assertPermission(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        return permissionService;
    }

    private record Fixture(FileTransferRunService service, FileTransferRunMapper runMapper,
                           DispatchTaskMapper dispatchTaskMapper,
                           UnstructuredManagementService permissionService) {
    }
}
