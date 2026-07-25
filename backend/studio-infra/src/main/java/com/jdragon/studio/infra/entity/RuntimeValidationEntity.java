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
@TableName(value = "studio_runtime_validation", autoResultMap = true)
public class RuntimeValidationEntity extends BaseProjectTenantEntity {
    private String resourceType;
    private Long resourceId;
    private Long runtimeClusterId;
    private Integer valid;
    private String issueCode;
    private String issueMessage;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detailsJson = new LinkedHashMap<String, Object>();

    private LocalDateTime validatedAt;
}
