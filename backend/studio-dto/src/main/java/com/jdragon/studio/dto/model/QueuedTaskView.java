package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class QueuedTaskView extends BaseDefinition {
    private String executionType;
    private Long workflowRunId;
    private Long workflowDefinitionId;
    private Long workflowVersionId;
    private String workflowName;
    private Long collectionTaskId;
    private String collectionTaskName;
    private String nodeCode;
    private String status;
    private String leaseOwner;
    private Integer attempts;
    private Integer maxRetries;
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();
}
