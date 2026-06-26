package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CollectionTaskListView extends BaseDefinition {
    private String name;
    private CollectionTaskType taskType;
    private CollectionTaskStatus status;
    private Integer sourceCount;
    private String targetDatasourceName;
    private String targetDatasourceTypeCode;
    private String targetModelName;
    private String targetModelPhysicalLocator;
    private CollectionTaskScheduleDefinition schedule;
}
