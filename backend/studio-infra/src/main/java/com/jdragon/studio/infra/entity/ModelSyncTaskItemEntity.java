package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_sync_task_item")
public class ModelSyncTaskItemEntity extends BaseProjectTenantEntity {
    private Long taskId;
    private Integer seqNo;
    private String physicalLocator;
    private String modelNameSnapshot;
    private String status;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
}
