package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.request.FileTransferManualItemRequest;
import com.jdragon.studio.dto.model.request.FileTransferManualRunRequest;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferSingleClusterTest {

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
        FileTransferRunService service = new FileTransferRunService(
                runMapper,
                mock(FileTransferRunItemMapper.class),
                mock(DispatchTaskMapper.class),
                mock(FileTransferTaskService.class),
                mock(DataSourceService.class),
                mock(RuntimeClusterSelectionService.class),
                projectAccess,
                securityService,
                new ObjectMapper());
        return new Fixture(service, runMapper);
    }

    private record Fixture(FileTransferRunService service, FileTransferRunMapper runMapper) {
    }
}
