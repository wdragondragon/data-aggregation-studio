package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("datasource_connection_health")
public class DatasourceConnectionHealthEntity extends BaseTenantEntity {
    /** Null is retained only for pre-migration historical snapshots and is never used for new probes. */
    private Long runtimeClusterId;
    private String connectionFingerprint;
    private String connectionStatus;
    private LocalDateTime lastConnectionTestAt;
    private String lastConnectionTestMessage;
    private Long lastConnectionTestDurationMs;
    private String probeState;
    private String probeOwner;
    private String probeRunId;
    private LocalDateTime probeStartedAt;
    private LocalDateTime probeLeaseUntil;
    private Integer failureCount;
    private LocalDateTime nextProbeAt;
}
