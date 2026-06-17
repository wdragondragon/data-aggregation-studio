package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.model.dto.DatasourceConnectionTestRecordView;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataSourceDefinition extends BaseDefinition {
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
    private Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
    private Map<String, Object> businessMetadata = new LinkedHashMap<String, Object>();
}

