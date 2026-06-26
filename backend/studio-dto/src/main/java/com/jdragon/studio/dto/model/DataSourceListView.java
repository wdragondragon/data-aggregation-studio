package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.model.dto.DatasourceConnectionTestRecordView;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataSourceListView extends BaseDefinition {
    private String name;
    private String typeCode;
    private Long schemaVersionId;
    private Boolean enabled;
    private Boolean executable;
    private String connectionFingerprint;
    private DataSourceConnectionStatus connectionStatus;
    private LocalDateTime lastConnectionTestAt;
    private String lastConnectionTestMessage;
    private Long lastConnectionTestDurationMs;
    private Boolean connectionTesting;
    private Boolean connectionStale;
    private LocalDateTime nextConnectionProbeAt;
    private Integer manualConnectionTestTimeoutSeconds;
    private Integer scheduledConnectionTestTimeoutSeconds;
    private List<DatasourceConnectionTestRecordView> recentConnectionTests = new ArrayList<DatasourceConnectionTestRecordView>();
}
