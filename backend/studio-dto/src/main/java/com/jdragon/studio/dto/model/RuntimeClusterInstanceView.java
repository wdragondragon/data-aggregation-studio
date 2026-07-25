package com.jdragon.studio.dto.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeClusterInstanceView {
    private String instanceId;
    private String bootId;
    private String workerGroupCode;
    private String version;
    private String summary;
    private LocalDateTime heartbeatAt;
    private String status;
    private boolean online;
}
