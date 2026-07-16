package com.jdragon.studio.infra.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
public class AlertObservation {
    private boolean active;
    private String subjectType;
    private String subjectKey;
    private Long subjectId;
    private String subjectName;
    private String targetPath;
    private String summary;
    private Map<String, Object> evidence = new LinkedHashMap<String, Object>();
    private String sourceType;
    private String sourceId;
    private String sourceEventKey;
    private Long ownerUserId;
    private LocalDateTime observedAt;
}
