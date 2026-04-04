package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("collection_task_schedule")
public class CollectionTaskScheduleEntity extends BaseTenantEntity {
    private Long collectionTaskId;
    private String cronExpression;
    private Integer enabled;
    private String timezone;
    private LocalDateTime lastTriggeredAt;
}
