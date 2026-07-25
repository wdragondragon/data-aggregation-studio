package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class RuntimeValidationView {
    private Long projectId;
    private String resourceType;
    private Long resourceId;
    private String resourceName;
    private Long runtimeClusterId;
    private boolean valid;
    private String issueCode;
    private String issueMessage;
    private Map<String, Object> details = new LinkedHashMap<String, Object>();
    private LocalDateTime validatedAt;
}
