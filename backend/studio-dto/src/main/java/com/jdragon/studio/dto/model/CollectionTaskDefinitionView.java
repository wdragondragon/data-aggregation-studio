package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.enums.CollectionTaskExecutionMode;
import com.jdragon.studio.dto.enums.StreamingDesiredState;
import com.jdragon.studio.dto.enums.StreamingObservedState;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class CollectionTaskDefinitionView extends BaseDefinition {
    private Long runtimeClusterId;
    private String runtimeClusterName;
    private String name;
    private CollectionTaskType taskType;
    private CollectionTaskStatus status;
    private CollectionTaskExecutionMode executionMode;
    private CollectionTaskStreamingOptions streamingOptions;
    private StreamingDesiredState desiredState;
    private StreamingObservedState observedState;
    private Long streamingGeneration;
    private Long currentStreamRunId;
    private Long currentStreamAttemptId;
    private Integer sourceCount;
    private List<CollectionTaskSourceBinding> sourceBindings = new ArrayList<CollectionTaskSourceBinding>();
    private CollectionTaskTargetBinding targetBinding;
    private List<FieldMappingDefinition> fieldMappings = new ArrayList<FieldMappingDefinition>();
    private Map<String, Object> executionOptions = new LinkedHashMap<String, Object>();
    private CollectionTaskScheduleDefinition schedule;
}
