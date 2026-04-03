package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_edge")
public class WorkflowEdgeEntity extends BaseTenantEntity {
    private Long workflowVersionId;
    private String fromNodeCode;
    private String toNodeCode;
    private String conditionType;
}
