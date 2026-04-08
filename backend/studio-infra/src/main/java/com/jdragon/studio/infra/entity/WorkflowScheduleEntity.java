package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_schedule")
public class WorkflowScheduleEntity extends BaseTenantEntity {
    private Long workflowDefinitionId;
    private String cronExpression;
    private Integer enabled;
    private String timezone;
    private LocalDateTime lastTriggeredAt;
}
