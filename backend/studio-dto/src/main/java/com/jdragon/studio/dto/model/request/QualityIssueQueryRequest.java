package com.jdragon.studio.dto.model.request;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QualityIssueQueryRequest {
    private Long datasourceId;
    private Long modelId;
    private String ruleDimension;
    private String granularity;
    private String taskStatus;
    private String severity;
    private String status;
    private Long assigneeUserId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
