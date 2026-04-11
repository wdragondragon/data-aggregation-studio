package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_follow_subscription")
public class FollowSubscriptionEntity extends BaseStudioEntity {
    private String tenantId;
    private Long projectId;
    private Long userId;
    private String targetType;
    private Long targetId;
    private Integer enabled;
}
