package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_transfer_event_consumer_cursor")
public class FileTransferEventConsumerCursorEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String instanceId;
    private String tenantId;
    private Long projectId;
    private Long lastEventId;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
