package com.jdragon.studio.dto.model.dto;

import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DatasourceConnectionTrendPointView {
    private DataSourceConnectionStatus status;
    private LocalDateTime testedAt;
    private LocalDateTime endedAt;
}
