package com.jdragon.studio.dto.model.system;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SystemWorkerInstanceView {
    private String workerGroupCode;
    private String workerCode;
    private String workerInstanceId;
    private String workerKind;
    private String hostName;
    private String podName;
    private String nodeName;
    private String status;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime leaseExpiresAt;
    private Boolean online;
}
