package com.jdragon.studio.dto.model.system;

import com.jdragon.studio.dto.model.BaseDefinition;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class SystemProjectWorkerView extends BaseDefinition {
    private String workerGroupCode;
    private String workerCode;
    private String workerInstanceId;
    private String workerKind;
    private String hostName;
    private String podName;
    private String nodeName;
    private Integer onlineInstanceCount;
    private Integer recentInstanceCount;
    private String status;
    private String displayStatus;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime latestHeartbeatAt;
    private Boolean boundToProject;
    private Boolean enabled;
    private List<SystemWorkerInstanceView> instances = new ArrayList<SystemWorkerInstanceView>();
}
