package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DatasourceClusterHealthView {
    private Long runtimeClusterId;
    private String runtimeClusterCode;
    private String runtimeClusterName;
    private DataSourceConnectionStatus connectionStatus = DataSourceConnectionStatus.UNKNOWN;
    private LocalDateTime lastConnectionTestAt;
    private String lastConnectionTestMessage;
    private Long lastConnectionTestDurationMs;
    private Boolean connectionTesting = Boolean.FALSE;
    private Boolean connectionStale = Boolean.FALSE;
    private LocalDateTime nextConnectionProbeAt;
}
