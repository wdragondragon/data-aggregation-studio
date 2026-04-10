package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.ModelSyncTaskSource;
import com.jdragon.studio.dto.enums.ModelSyncTaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ModelSyncTaskView extends BaseDefinition {
    private Long datasourceId;
    private String datasourceType;
    private String datasourceNameSnapshot;
    private Integer batchNo;
    private String name;
    private ModelSyncTaskSource source;
    private ModelSyncTaskStatus status;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer stoppedCount;
    private Integer progressPercent;
    private Boolean stopRequested;
    private Long createdBy;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime finishedAt;
    private Long durationMs;
    private String lastError;
}
