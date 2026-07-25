package com.jdragon.studio.dto.model.request;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AlertIncidentQueryRequest {
    private String keyword;
    private String status;
    private String severity;
    private String ruleType;
    private String subjectType;
    private Boolean activeOnly;
    private Long requestedClusterId;
    private Long actualClusterId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer pageNo;
    private Integer pageSize;
}
