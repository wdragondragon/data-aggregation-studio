package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_definition")
public class WorkflowDefinitionEntity extends BaseTenantEntity {
    private String code;
    private String name;
    private Long currentVersionId;
    private Integer published;
}
