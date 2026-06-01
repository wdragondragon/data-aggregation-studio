package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OpsCenterWorkerGroupView {
    private String workerGroupCode;
    private String displayStatus;
    private Boolean boundToProject;
    private Boolean enabled;
    private Integer onlineInstanceCount;
    private Integer recentInstanceCount;
    private LocalDateTime latestHeartbeatAt;
    private String latestWorkerCode;
    private String latestWorkerInstanceId;
    private String latestPodName;
    private String latestNodeName;
    private LocalDateTime leaseExpiresAt;
}
