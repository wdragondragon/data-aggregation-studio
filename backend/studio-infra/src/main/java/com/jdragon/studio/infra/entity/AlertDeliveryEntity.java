package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "studio_alert_delivery", autoResultMap = true)
public class AlertDeliveryEntity extends BaseProjectTenantEntity {
    private Long eventId;
    private Long incidentId;
    private String deliveryKey;
    private String channelType;
    private Long channelId;
    private String channelNameSnapshot;
    private Long recipientUserId;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime lastAttemptAt;
    private Integer httpStatus;
    private String responseExcerpt;
    private String errorMessage;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();
}
