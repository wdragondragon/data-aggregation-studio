package com.jdragon.studio.infra.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.Map;

@Value
@Builder
public class FileTransferEventIntent {
    String tenantId;
    Long projectId;
    FileTransferOutboxEventType eventType;
    Long runId;
    Long itemId;
    LocalDateTime occurredAt;
    Map<String, Object> payload;
}
