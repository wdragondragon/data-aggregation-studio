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
@TableName(value = "studio_alert_channel", autoResultMap = true)
public class AlertChannelEntity extends BaseProjectTenantEntity {
    private String name;
    private String channelType;
    private String endpointCiphertext;
    private String headersCiphertext;
    private String signingSecretCiphertext;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configJson = new LinkedHashMap<String, Object>();

    private Integer enabled;
    private LocalDateTime lastTestedAt;
    private String lastTestStatus;
    private String lastTestMessage;
    private Long createdBy;
    private Long updatedBy;
}
