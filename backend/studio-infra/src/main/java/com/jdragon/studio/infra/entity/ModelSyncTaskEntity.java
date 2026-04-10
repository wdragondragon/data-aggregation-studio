package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_sync_task")
public class ModelSyncTaskEntity extends BaseProjectTenantEntity {
    private Long datasourceId;
    private String datasourceType;
    private String datasourceNameSnapshot;
    private Integer batchNo;
    private String name;
    private String source;
    private String status;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer stoppedCount;
    private Integer progressPercent;
    private Integer stopRequested;
    private Long createdBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String lastError;
}
