package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class FileTransferMetricDashboardView {
    private Long runCount;
    private Long successRunCount;
    private Long failedRunCount;
    private Long totalFiles;
    private Long successFiles;
    private Long failedFiles;
    private Long skippedFiles;
    private Long conflictFiles;
    private Long resumedFiles;
    private Long postActionFailedFiles;
    private Long totalBytes;
    private Long transferredBytes;
    private Long failedBytes;
    private Long resumedBytes;
    private Long currentBytesPerSecond;
    private Long averageBytesPerSecond;
    private Long peakBytesPerSecond;
    private Long estimatedRemainingSeconds;
    private Long activeFiles;
    private Long retryCount;
    private List<FileTransferMetricPointView> trend = new ArrayList<FileTransferMetricPointView>();
    private List<RunMetricTopNItemView> sourceDatasourceTopN = new ArrayList<RunMetricTopNItemView>();
    private List<RunMetricTopNItemView> targetDatasourceTopN = new ArrayList<RunMetricTopNItemView>();
}
