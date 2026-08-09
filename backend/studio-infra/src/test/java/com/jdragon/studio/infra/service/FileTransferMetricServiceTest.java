package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.model.FileTransferMetricDashboardView;
import com.jdragon.studio.dto.model.FileTransferMetricPointView;
import com.jdragon.studio.dto.model.RunMetricDashboardView;
import com.jdragon.studio.dto.model.request.FileTransferMetricQueryRequest;
import com.jdragon.studio.dto.model.request.RunMetricDashboardQueryRequest;
import com.jdragon.studio.infra.entity.FileTransferMetricSampleEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileTransferMetricServiceTest {

    @BeforeAll
    static void initTableInfo() {
        init(FileTransferRunEntity.class);
        init(FileTransferMetricSampleEntity.class);
    }

    @Test
    void dashboardAggregatesFileByteRetryAndSuccessfulFileDatasourceMetrics() {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferMetricSampleMapper sampleMapper = mock(FileTransferMetricSampleMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("tenant-a");
        when(accessService.requireCurrentProjectId()).thenReturn(10L);
        when(dataSourceService.listBasicNameMap(any())).thenReturn(Map.of(
                21L, "来源 OSS", 31L, "归档 OSS A", 32L, "归档 OSS B"));
        FileTransferRunEntity smallerFiles = run(1L, 21L, 31L, "SUCCESS", 100L, 100L, 10L);
        smallerFiles.setTotalFiles(4L);
        smallerFiles.setSuccessFiles(3L);
        smallerFiles.setSkippedFiles(1L);
        FileTransferRunEntity largerFile = run(2L, 21L, 32L,
                "PARTIAL_SUCCESS", 200L, 150L, 20L);
        when(runMapper.selectList(any())).thenReturn(List.of(smallerFiles, largerFile));
        FileTransferMetricSampleEntity sample = new FileTransferMetricSampleEntity();
        sample.setSampledAt(LocalDateTime.of(2026, 8, 7, 12, 0));
        sample.setTransferredBytes(250L);
        sample.setBytesPerSecond(30L);
        sample.setCompletedFiles(2L);
        sample.setFailedFiles(1L);
        when(sampleMapper.selectList(any())).thenReturn(List.of(sample));
        FileTransferMetricService service = new FileTransferMetricService(
                runMapper, sampleMapper, dataSourceService, securityService, accessService);

        FileTransferMetricDashboardView view = service.dashboard(new FileTransferMetricQueryRequest());

        assertThat(view.getRunCount()).isEqualTo(2L);
        assertThat(view.getTotalBytes()).isEqualTo(300L);
        assertThat(view.getTransferredBytes()).isEqualTo(250L);
        assertThat(view.getCurrentBytesPerSecond()).isZero();
        assertThat(view.getRetryCount()).isEqualTo(4L);
        assertThat(view.getActiveFiles()).isZero();
        assertThat(view.getSourceDatasourceTopN()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getId()).isEqualTo(21L);
                    assertThat(item.getLabel()).isEqualTo("来源 OSS");
                    assertThat(item.getCount()).isEqualTo(5L);
                });
        assertThat(view.getTargetDatasourceTopN()).extracting("id")
                .containsExactly(31L, 32L);
        assertThat(view.getTargetDatasourceTopN()).extracting("count")
                .containsExactly(4L, 1L);
        assertThat(view.getTrend()).hasSize(1);
    }

    @Test
    void commonRunMetricsExposeFileTransferTrendAndTopN() {
        FileTransferMetricService fileTransferMetricService = mock(FileTransferMetricService.class);
        FileTransferMetricDashboardView dashboard = new FileTransferMetricDashboardView();
        dashboard.setSourceDatasourceTopN(List.of());
        dashboard.setTargetDatasourceTopN(List.of());
        FileTransferMetricPointView point = new FileTransferMetricPointView();
        point.setSampledAt(LocalDateTime.of(2026, 8, 7, 12, 0));
        point.setTransferredBytes(1024L);
        point.setBytesPerSecond(128L);
        point.setCompletedFiles(3L);
        point.setFailedFiles(1L);
        dashboard.setTrend(List.of(point));
        when(fileTransferMetricService.dashboard(any())).thenReturn(dashboard);
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        when(collectionTaskService.listMetricBindings()).thenReturn(List.of());
        RunMetricsService service = new RunMetricsService(collectionTaskService,
                mock(DataSourceService.class),
                mock(RunRecordMapper.class), mock(StudioSecurityService.class),
                new RunMetricSummaryMapper(), fileTransferMetricService);
        RunMetricDashboardQueryRequest request = new RunMetricDashboardQueryRequest();
        request.setExecutionType("FILE_TRANSFER");

        RunMetricDashboardView view = service.query(request);

        assertThat(view.getFileTransfer()).isSameAs(dashboard);
        assertThat(view.getTrend().getSeries()).extracting("key")
                .containsExactly("transferredBytes", "bytesPerSecond", "completedFiles", "failedFiles");
        assertThat(view.getTrend().getSeries().get(0).getData()).containsExactly(1024L);
    }

    private FileTransferRunEntity run(Long id, Long sourceDatasourceId, Long targetDatasourceId,
                                      String status, Long totalBytes, Long transferredBytes,
                                      Long speed) {
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(id);
        run.setStatus(status);
        run.setSourceDatasourceId(sourceDatasourceId);
        run.setTargetDatasourceId(targetDatasourceId);
        run.setTotalFiles(2L);
        run.setSuccessFiles(1L);
        run.setSkippedFiles(0L);
        run.setFailedFiles("SUCCESS".equals(status) ? 0L : 1L);
        run.setConflictFiles(0L);
        run.setResumedFiles(1L);
        run.setPostActionFailedFiles(0L);
        run.setTotalBytes(totalBytes);
        run.setTransferredBytes(transferredBytes);
        run.setFailedBytes("SUCCESS".equals(status) ? 0L : 50L);
        run.setResumedBytes(10L);
        run.setCurrentBytesPerSecond(speed);
        run.setPeakBytesPerSecond(speed);
        run.setActiveFiles(1);
        run.setRetryCount(2);
        run.setStartedAt(LocalDateTime.now().minusSeconds(10));
        run.setEndedAt(LocalDateTime.now());
        return run;
    }

    private static void init(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }
}
