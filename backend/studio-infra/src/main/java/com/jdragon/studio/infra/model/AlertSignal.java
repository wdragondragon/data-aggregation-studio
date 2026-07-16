package com.jdragon.studio.infra.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
public class AlertSignal {
    private String tenantId;
    private Long projectId;
    private String signalType;
    private String subjectType;
    private Long subjectId;
    private String subjectKey;
    private String subjectName;
    private Long ownerUserId;
    private boolean success;
    private String status;
    private Long failureCount;
    private String sourceId;
    private String sourceEventKey;
    private String targetPath;
    private LocalDateTime occurredAt;
    private Map<String, Object> evidence = new LinkedHashMap<String, Object>();
}
