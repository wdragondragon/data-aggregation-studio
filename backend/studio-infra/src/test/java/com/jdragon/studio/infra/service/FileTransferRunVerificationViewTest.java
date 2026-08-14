package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.FileTransferRunItemView;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileTransferRunVerificationViewTest {

    private final FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
    private final FileTransferRunService service = new FileTransferRunService(
            runMapper,
            mock(FileTransferRunItemMapper.class),
            mock(DispatchTaskMapper.class),
            mock(FileTransferTaskService.class),
            mock(DataSourceService.class),
            mock(RuntimeClusterSelectionService.class),
            mock(ProjectResourceAccessService.class),
            mock(StudioSecurityService.class),
            mock(UnstructuredManagementService.class),
            mock(ClusterLockService.class),
            new ObjectMapper());

    @Test
    void historicalRunWithoutVerificationFieldsDefaultsToStrong() {
        FileTransferRunEntity run = run(1L, Map.of());
        when(runMapper.selectById(1L)).thenReturn(run);

        FileTransferRunItemView view = service.toItemViews(List.of(item(1L, 32L * 1024L * 1024L))).get(0);

        assertThat(view.getVerificationModeConfigured()).isEqualTo("STRONG");
        assertThat(view.getVerificationModeEffective()).isEqualTo("STRONG");
        assertThat(view.getVerificationFrameCount()).isEqualTo(16);
        assertThat(view.getVerificationFrameSizeBytes()).isEqualTo(1024L * 1024L);
    }

    @Test
    void partialRunDerivesPerFileEffectiveMode() {
        Map<String, Object> policy = Map.of(
                "verificationMode", "PARTIAL",
                "verificationFrameCount", 4,
                "verificationFrameSizeBytes", 64L * 1024L);
        FileTransferRunEntity run = run(2L, policy);
        when(runMapper.selectById(2L)).thenReturn(run);

        List<FileTransferRunItemView> views = service.toItemViews(List.of(
                item(2L, 2L * 1024L * 1024L),
                item(2L, 256L * 1024L)));

        assertThat(views).extracting(FileTransferRunItemView::getVerificationModeConfigured)
                .containsExactly("PARTIAL", "PARTIAL");
        assertThat(views).extracting(FileTransferRunItemView::getVerificationModeEffective)
                .containsExactly("PARTIAL", "STRONG");
        assertThat(views).extracting(FileTransferRunItemView::getVerificationFrameCount)
                .containsOnly(4);
        assertThat(views).extracting(FileTransferRunItemView::getVerificationFrameSizeBytes)
                .containsOnly(64L * 1024L);
    }

    private FileTransferRunEntity run(Long id, Map<String, Object> policy) {
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(id);
        run.setResolvedSpecJson(Map.of("policy", policy));
        return run;
    }

    private FileTransferRunItemEntity item(Long runId, Long fileSize) {
        FileTransferRunItemEntity item = new FileTransferRunItemEntity();
        item.setRunId(runId);
        item.setFileSize(fileSize);
        item.setStatus("QUEUED");
        return item;
    }
}
