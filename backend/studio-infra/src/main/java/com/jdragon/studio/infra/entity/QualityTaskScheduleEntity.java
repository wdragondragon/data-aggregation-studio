package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quality_task_schedule")
public class QualityTaskScheduleEntity extends BaseProjectTenantEntity {
    private Long qualityTaskId;
    private String cronExpression;
    private Integer enabled;
    private String timezone;
    private LocalDateTime lastTriggeredAt;
}
