package com.jdragon.studio.dto.model.dto;

import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DatasourceConnectionTestRecordView {
    private DataSourceConnectionStatus status;
    private LocalDateTime testedAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private String probeMode;
    private Integer timeoutSeconds;
    private String message;
    private Long datasourceId;
    private String datasourceName;
}
