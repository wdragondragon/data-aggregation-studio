package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("collection_task_metric_binding")
public class CollectionTaskMetricBindingEntity extends BaseProjectTenantEntity {
    private Long collectionTaskId;
    private String taskNameSnapshot;
    private String taskType;
    private String taskStatus;
    private Integer sourceCount;
    private String bindingRole;
    private String sourceAlias;
    private Long datasourceId;
    private String datasourceName;
    private String datasourceTypeCode;
    private Long modelId;
    private String modelName;
    private String modelPhysicalLocator;
}
