package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.FileTransferRunView;
import com.jdragon.studio.infra.entity.FileTransferTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferRunTriggerLockTest {

    @Test
    @SuppressWarnings("unchecked")
    void presetTriggerRunsInsideNonReentrantClusterLock() {
        Fixture fixture = fixture();
        when(fixture.clusterLock.executeIfAcquiredNonReentrant(anyString(), anyLong(), anyBoolean(),
                any(Supplier.class), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<FileTransferRunView>) invocation.getArgument(3)).get());

        fixture.service.triggerTask(700L, "MANUAL_TASK", null);

        verify(fixture.clusterLock).executeIfAcquiredNonReentrant(
                org.mockito.ArgumentMatchers.eq("file-transfer-task-run:700"),
                org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.eq(true), any(Supplier.class), any(Supplier.class));
        verify(fixture.runMapper).insert(any(FileTransferRunEntity.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentPresetTriggerFailsBeforeCreatingRun() {
        Fixture fixture = fixture();
        when(fixture.clusterLock.executeIfAcquiredNonReentrant(anyString(), anyLong(), anyBoolean(),
                any(Supplier.class), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<FileTransferRunView>) invocation.getArgument(4)).get());

        assertThatThrownBy(() -> fixture.service.triggerTask(700L, "MANUAL_TASK", null))
                .isInstanceOf(StudioException.class)
                .hasMessage("File transfer task trigger is already in progress");
        verify(fixture.taskService, never()).requireOnlineForExecution(anyLong());
        verify(fixture.runMapper, never()).insert(any(FileTransferRunEntity.class));
    }

    private Fixture fixture() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferTaskService taskService = mock(FileTransferTaskService.class);
        ClusterLockService clusterLock = mock(ClusterLockService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        FileTransferTaskDefinitionEntity task = new FileTransferTaskDefinitionEntity();
        task.setId(700L);
        task.setTenantId("default");
        task.setProjectId(10L);
        task.setName("daily-files");
        task.setRuntimeClusterId(11L);
        task.setSourceRuntimeClusterId(11L);
        task.setSourceDatasourceId(21L);
        task.setTargetRuntimeClusterId(11L);
        task.setTargetDatasourceId(22L);
        when(taskService.requireOnlineForExecution(700L)).thenReturn(task);
        when(taskService.publishedSnapshot(700L)).thenReturn(new LinkedHashMap<>());
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(securityService.currentTenantId()).thenReturn("default");

        FileTransferRunService service = new FileTransferRunService(
                runMapper, mock(FileTransferRunItemMapper.class), mock(DispatchTaskMapper.class),
                taskService, mock(DataSourceService.class), mock(RuntimeClusterSelectionService.class),
                mock(ProjectResourceAccessService.class), securityService,
                mock(UnstructuredManagementService.class), clusterLock, new ObjectMapper());
        return new Fixture(service, runMapper, taskService, clusterLock);
    }

    private record Fixture(FileTransferRunService service,
                           FileTransferRunMapper runMapper,
                           FileTransferTaskService taskService,
                           ClusterLockService clusterLock) {
    }
}
