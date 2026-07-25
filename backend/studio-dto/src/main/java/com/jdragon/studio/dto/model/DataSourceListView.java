package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.model.dto.DatasourceConnectionTrendPointView;
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
    private List<Long> applicableClusterIds = new ArrayList<Long>();
    private List<RuntimeClusterView> applicableClusters = new ArrayList<RuntimeClusterView>();
    private List<DatasourceClusterHealthView> clusterHealth = new ArrayList<DatasourceClusterHealthView>();
    private List<DatasourceConnectionTrendPointView> recentConnectionTests = new ArrayList<DatasourceConnectionTrendPointView>();
}
