package com.jdragon.studio.dto.model.dto;

import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import lombok.Data;

@Data
public class ConnectionTestResult {
    private boolean success;
    private String message;
    private DataSourceConnectionStatus status;
    private Long durationMs;
}

