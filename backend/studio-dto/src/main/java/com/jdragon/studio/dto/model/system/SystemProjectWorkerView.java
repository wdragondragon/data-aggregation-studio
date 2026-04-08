package com.jdragon.studio.dto.model.system;

import com.jdragon.studio.dto.model.BaseDefinition;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class SystemProjectWorkerView extends BaseDefinition {
    private String workerCode;
    private String workerKind;
    private String hostName;
    private String status;
    private LocalDateTime lastHeartbeatAt;
    private Boolean boundToProject;
    private Boolean enabled;
}
