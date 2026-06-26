package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class QueuedTaskListView extends BaseDefinition {
    private String executionType;
    private Long workflowRunId;
    private Long workflowDefinitionId;
    private Long workflowVersionId;
    private String workflowName;
    private Long collectionTaskId;
    private String collectionTaskName;
    private Long qualityTaskId;
    private String qualityTaskName;
    private String nodeCode;
    private String status;
    private String workerGroupCode;
    private String leaseOwner;
    private Integer attempts;
    private Integer maxRetries;
}
