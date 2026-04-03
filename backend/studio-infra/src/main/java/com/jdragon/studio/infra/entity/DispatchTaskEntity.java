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
@TableName(value = "dispatch_task", autoResultMap = true)
public class DispatchTaskEntity extends BaseTenantEntity {
    private Long workflowDefinitionId;
    private Long workflowVersionId;
    private String nodeCode;
    private String status;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private Integer attempts;
    private Integer maxRetries;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();
}
