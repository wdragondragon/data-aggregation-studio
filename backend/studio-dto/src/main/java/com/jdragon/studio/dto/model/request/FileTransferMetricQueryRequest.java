package com.jdragon.studio.dto.model.request;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileTransferMetricQueryRequest {
    private Long taskId;
    private Long runtimeClusterId;
    private Long datasourceId;
    private String channel;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer topN;
}
