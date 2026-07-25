package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("datasource_connection_test_record")
public class DatasourceConnectionTestRecordEntity extends BaseTenantEntity {
    private Long runtimeClusterId;
    private String connectionFingerprint;
    private Long datasourceId;
    private String datasourceName;
    private String typeCode;
    private String probeRunId;
    private String probeMode;
    private String connectionStatus;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private Integer timeoutSeconds;
    private String message;
}
