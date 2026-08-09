package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.model.FileTransferMetricDashboardView;
import com.jdragon.studio.dto.model.FileTransferMetricPointView;
import com.jdragon.studio.dto.model.RunMetricTopNItemView;
import com.jdragon.studio.dto.model.request.FileTransferMetricQueryRequest;
import com.jdragon.studio.infra.entity.FileTransferMetricSampleEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FileTransferMetricService {

    private final FileTransferRunMapper runMapper;
    private final FileTransferMetricSampleMapper sampleMapper;
    private final DataSourceService dataSourceService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;

    public FileTransferMetricService(FileTransferRunMapper runMapper,
                                     FileTransferMetricSampleMapper sampleMapper,
                                     DataSourceService dataSourceService,
                                     StudioSecurityService securityService,
                                     ProjectResourceAccessService projectResourceAccessService) {
        this.runMapper = runMapper;
        this.sampleMapper = sampleMapper;
        this.dataSourceService = dataSourceService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    public FileTransferMetricDashboardView dashboard(FileTransferMetricQueryRequest request) {
        FileTransferMetricQueryRequest query = request == null
                ? new FileTransferMetricQueryRequest() : request;
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        LambdaQueryWrapper<FileTransferRunEntity> runsQuery = new LambdaQueryWrapper<FileTransferRunEntity>()
                .eq(FileTransferRunEntity::getTenantId, tenantId)
                .eq(FileTransferRunEntity::getProjectId, projectId);
        if (query.getTaskId() != null) runsQuery.eq(FileTransferRunEntity::getTaskId, query.getTaskId());
        if (query.getRuntimeClusterId() != null) runsQuery.eq(FileTransferRunEntity::getTargetRuntimeClusterId, query.getRuntimeClusterId());
        if (query.getDatasourceId() != null) runsQuery.and(wrapper -> wrapper
                .eq(FileTransferRunEntity::getSourceDatasourceId, query.getDatasourceId())
                .or().eq(FileTransferRunEntity::getTargetDatasourceId, query.getDatasourceId()));
        if (hasText(query.getChannel())) runsQuery.eq(FileTransferRunEntity::getChannel, query.getChannel());
        if (hasText(query.getStatus())) runsQuery.eq(FileTransferRunEntity::getStatus, query.getStatus());
        if (query.getStartedAt() != null) runsQuery.ge(FileTransferRunEntity::getCreatedAt, query.getStartedAt());
        if (query.getEndedAt() != null) runsQuery.le(FileTransferRunEntity::getCreatedAt, query.getEndedAt());
        List<FileTransferRunEntity> runs = runMapper.selectList(runsQuery);

        FileTransferMetricDashboardView dashboard = new FileTransferMetricDashboardView();
        dashboard.setRunCount((long) runs.size());
        dashboard.setSuccessRunCount(runs.stream().filter(value -> "SUCCESS".equalsIgnoreCase(value.getStatus())).count());
        dashboard.setFailedRunCount(runs.stream().filter(value -> "FAILED".equalsIgnoreCase(value.getStatus())
                || "PARTIAL_SUCCESS".equalsIgnoreCase(value.getStatus())).count());
        dashboard.setTotalFiles(runs.stream().mapToLong(value -> number(value.getTotalFiles())).sum());
        dashboard.setSuccessFiles(runs.stream().mapToLong(value -> number(value.getSuccessFiles())
                + number(value.getSkippedFiles())).sum());
        dashboard.setFailedFiles(runs.stream().mapToLong(value -> number(value.getFailedFiles())).sum());
        dashboard.setSkippedFiles(runs.stream().mapToLong(value -> number(value.getSkippedFiles())).sum());
        dashboard.setConflictFiles(runs.stream().mapToLong(value -> number(value.getConflictFiles())).sum());
        dashboard.setResumedFiles(runs.stream().mapToLong(value -> number(value.getResumedFiles())).sum());
        dashboard.setPostActionFailedFiles(runs.stream()
                .mapToLong(value -> number(value.getPostActionFailedFiles())).sum());
        dashboard.setTotalBytes(runs.stream().mapToLong(value -> number(value.getTotalBytes())).sum());
        dashboard.setTransferredBytes(runs.stream().mapToLong(value -> number(value.getTransferredBytes())).sum());
        dashboard.setFailedBytes(runs.stream().mapToLong(value -> number(value.getFailedBytes())).sum());
        dashboard.setResumedBytes(runs.stream().mapToLong(value -> number(value.getResumedBytes())).sum());
        dashboard.setCurrentBytesPerSecond(runs.stream()
                .filter(value -> !terminal(value.getStatus()))
                .mapToLong(value -> number(value.getCurrentBytesPerSecond())).sum());
        dashboard.setAverageBytesPerSecond(averageBytesPerSecond(runs));
        dashboard.setPeakBytesPerSecond(runs.stream().mapToLong(value -> number(value.getPeakBytesPerSecond())).max().orElse(0L));
        dashboard.setActiveFiles(runs.stream()
                .filter(value -> !terminal(value.getStatus()))
                .mapToLong(value -> intNumber(value.getActiveFiles())).sum());
        dashboard.setRetryCount(runs.stream().mapToLong(value -> intNumber(value.getRetryCount())).sum());
        long remaining = Math.max(0L, dashboard.getTotalBytes() - dashboard.getTransferredBytes());
        long speed = number(dashboard.getCurrentBytesPerSecond());
        dashboard.setEstimatedRemainingSeconds(speed <= 0L ? null : (remaining + speed - 1L) / speed);
        int topN = query.getTopN() == null ? 10 : Math.max(1, Math.min(100, query.getTopN()));
        Map<Long, String> datasourceNames = dataSourceService.listBasicNameMap(datasourceIds(runs));
        dashboard.setSourceDatasourceTopN(topDatasources(runs, true, topN, datasourceNames));
        dashboard.setTargetDatasourceTopN(topDatasources(runs, false, topN, datasourceNames));
        dashboard.setTrend(loadTrend(tenantId, projectId, runs));
        return dashboard;
    }

    private long averageBytesPerSecond(List<FileTransferRunEntity> runs) {
        long bytes = 0L;
        long elapsedMillis = 0L;
        for (FileTransferRunEntity run : runs) {
            if (run.getStartedAt() == null) {
                continue;
            }
            java.time.LocalDateTime end = run.getEndedAt() == null
                    ? java.time.LocalDateTime.now() : run.getEndedAt();
            elapsedMillis += Math.max(0L, java.time.Duration.between(run.getStartedAt(), end).toMillis());
            bytes += number(run.getTransferredBytes());
        }
        return elapsedMillis <= 0L ? 0L : bytes * 1000L / elapsedMillis;
    }

    private List<RunMetricTopNItemView> topDatasources(List<FileTransferRunEntity> runs,
                                                       boolean source, int topN,
                                                       Map<Long, String> datasourceNames) {
        Map<Long, Long> values = new LinkedHashMap<Long, Long>();
        for (FileTransferRunEntity run : runs) {
            Long datasourceId = source ? run.getSourceDatasourceId() : run.getTargetDatasourceId();
            if (datasourceId != null) {
                long successfulFiles = number(run.getSuccessFiles()) + number(run.getSkippedFiles());
                values.merge(datasourceId, successfulFiles, Long::sum);
            }
        }
        List<RunMetricTopNItemView> result = new ArrayList<RunMetricTopNItemView>();
        values.forEach((id, count) -> {
            RunMetricTopNItemView item = new RunMetricTopNItemView();
            item.setId(id);
            String name = datasourceNames == null ? null : datasourceNames.get(id);
            item.setName(name == null || name.isBlank() ? "未知数据源" : name);
            item.setLabel(item.getName());
            item.setTypeCode("FILE");
            item.setCount(count);
            result.add(item);
        });
        result.sort(Comparator.comparing(RunMetricTopNItemView::getCount,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return result.size() <= topN ? result : new ArrayList<RunMetricTopNItemView>(result.subList(0, topN));
    }

    private Set<Long> datasourceIds(List<FileTransferRunEntity> runs) {
        Set<Long> ids = new LinkedHashSet<Long>();
        for (FileTransferRunEntity run : runs) {
            if (run.getSourceDatasourceId() != null) ids.add(run.getSourceDatasourceId());
            if (run.getTargetDatasourceId() != null) ids.add(run.getTargetDatasourceId());
        }
        return ids;
    }

    private List<FileTransferMetricPointView> loadTrend(String tenantId, Long projectId,
                                                        List<FileTransferRunEntity> runs) {
        Set<Long> runIds = new LinkedHashSet<Long>();
        for (FileTransferRunEntity run : runs) {
            runIds.add(run.getId());
        }
        if (runIds.isEmpty()) {
            return new ArrayList<FileTransferMetricPointView>();
        }
        List<FileTransferMetricSampleEntity> samples = sampleMapper.selectList(
                new LambdaQueryWrapper<FileTransferMetricSampleEntity>()
                        .eq(FileTransferMetricSampleEntity::getTenantId, tenantId)
                        .eq(FileTransferMetricSampleEntity::getProjectId, projectId)
                        .in(FileTransferMetricSampleEntity::getRunId, runIds)
                        .orderByAsc(FileTransferMetricSampleEntity::getSampledAt)
                        .last("limit 2000"));
        List<FileTransferMetricPointView> trend = new ArrayList<FileTransferMetricPointView>();
        for (FileTransferMetricSampleEntity sample : samples) {
            FileTransferMetricPointView point = new FileTransferMetricPointView();
            point.setSampledAt(sample.getSampledAt());
            point.setTransferredBytes(sample.getTransferredBytes());
            point.setBytesPerSecond(sample.getBytesPerSecond());
            point.setCompletedFiles(sample.getCompletedFiles());
            point.setFailedFiles(sample.getFailedFiles());
            point.setActiveFiles(sample.getActiveFiles());
            point.setRetryCount(sample.getRetryCount());
            trend.add(point);
        }
        return trend;
    }

    private long number(Long value) {
        return value == null ? 0L : value;
    }

    private boolean terminal(String status) {
        return List.of("SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELED")
                .contains(status == null ? "" : status.trim().toUpperCase());
    }

    private long intNumber(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
