package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.OpsCenterHealthStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class OpsCenterOverviewView {
    private OpsCenterHealthStatus healthStatus;
    private String healthMessage;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<String> healthReasons = new ArrayList<String>();
    private List<OpsCenterMetricCardView> metrics = new ArrayList<OpsCenterMetricCardView>();
    private Long runTotal;
    private Long failedRuns;
    private Long runningRuns;
    private Long slowRuns;
    private Long queuedTasks;
    private Long runningQueueTasks;
    private Long serviceFailures;
    private Long serviceSlowCalls;
    private Long ingestionFailures;
    private Long ingestionSlowCalls;
    private Long logFailures;
    private Integer onlineWorkerInstances;
    private Integer boundWorkerGroups;
}
