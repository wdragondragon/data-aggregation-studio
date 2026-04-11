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
@TableName(value = "studio_notification", autoResultMap = true)
public class NotificationEntity extends BaseStudioEntity {
    private Long recipientUserId;
    private String tenantId;
    private Long projectId;
    private String category;
    private String title;
    private String content;
    private String targetType;
    private Long targetId;
    private String targetPath;
    private String targetTenantId;
    private Long targetProjectId;
    private String dedupeKey;
    private LocalDateTime readAt;
    private LocalDateTime archivedAt;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();
}
