package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.ModelSyncTaskItemStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ModelSyncTaskItemView extends BaseDefinition {
    private Long taskId;
    private Integer seqNo;
    private String physicalLocator;
    private String modelNameSnapshot;
    private ModelSyncTaskItemStatus status;
    private String message;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime finishedAt;
    private Long durationMs;
}
