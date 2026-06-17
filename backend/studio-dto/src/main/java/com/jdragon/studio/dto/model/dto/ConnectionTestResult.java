package com.jdragon.studio.dto.model.dto;

import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ConnectionTestResult {
    private boolean success;
    private String message;
    private DataSourceConnectionStatus status;
    private Long durationMs;
    private Boolean testing;
    private Boolean stale;
    private Boolean busy;
    private LocalDateTime lastTestAt;
    private LocalDateTime nextProbeAt;
    private Integer timeoutSeconds;
    private List<DatasourceConnectionTestRecordView> recentConnectionTests = new ArrayList<DatasourceConnectionTestRecordView>();
}

