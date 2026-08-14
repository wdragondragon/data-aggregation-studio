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
@TableName(value = "file_transfer_event_outbox", autoResultMap = true)
public class FileTransferEventOutboxEntity extends BaseProjectTenantEntity {
    private String eventType;
    private Long runId;
    private Long itemId;
    private LocalDateTime occurredAt;
    private Integer payloadVersion;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();
}
