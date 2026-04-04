package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class CollectionTaskDefinitionView extends BaseDefinition {
    private String name;
    private CollectionTaskType taskType;
    private CollectionTaskStatus status;
    private Integer sourceCount;
    private List<CollectionTaskSourceBinding> sourceBindings = new ArrayList<CollectionTaskSourceBinding>();
    private CollectionTaskTargetBinding targetBinding;
    private List<FieldMappingDefinition> fieldMappings = new ArrayList<FieldMappingDefinition>();
    private Map<String, Object> executionOptions = new LinkedHashMap<String, Object>();
    private CollectionTaskScheduleDefinition schedule;
}
