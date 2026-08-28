package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.enums.CollectionTaskExecutionMode;
import com.jdragon.studio.dto.enums.StreamingDesiredState;
import com.jdragon.studio.dto.enums.StreamingObservedState;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CollectionTaskListView extends BaseDefinition {
    private Long runtimeClusterId;
    private String runtimeClusterName;
    private String name;
    private CollectionTaskType taskType;
    private CollectionTaskStatus status;
    private CollectionTaskExecutionMode executionMode;
    private StreamingDesiredState desiredState;
    private StreamingObservedState observedState;
    private Long streamingGeneration;
    private Long currentStreamRunId;
    private Long currentStreamAttemptId;
    private Integer sourceCount;
    private String targetDatasourceName;
    private String targetDatasourceTypeCode;
    private String targetModelName;
    private String targetModelPhysicalLocator;
    private CollectionTaskScheduleDefinition schedule;
}
