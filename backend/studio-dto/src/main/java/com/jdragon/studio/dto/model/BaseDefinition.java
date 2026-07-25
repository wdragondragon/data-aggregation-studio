package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BaseDefinition {
    private Long id;
    private String tenantId;
    private Long projectId;
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** False when the saved runtime cluster no longer matches its datasource bindings. */
    private Boolean runtimeValid = Boolean.TRUE;
    private String runtimeValidationMessage;
}

