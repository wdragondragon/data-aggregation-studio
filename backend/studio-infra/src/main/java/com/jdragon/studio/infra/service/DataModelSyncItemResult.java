package com.jdragon.studio.infra.service;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DataModelSyncItemResult {
    private String physicalLocator;
    private String modelName;
    private boolean success;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
}
