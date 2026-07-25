package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class OpsCenterWorkerGroupView {
    private String workerGroupCode;
    private String displayStatus;
    private Boolean boundToProject;
    private Boolean enabled;
    private Integer onlineInstanceCount;
    private Integer recentInstanceCount;
    private List<Long> runtimeClusterIds = new ArrayList<Long>();
    private List<String> runtimeClusterCodes = new ArrayList<String>();
    private LocalDateTime latestHeartbeatAt;
    private String latestWorkerCode;
    private String latestWorkerInstanceId;
    private String latestPodName;
    private String latestNodeName;
    private LocalDateTime leaseExpiresAt;
}
