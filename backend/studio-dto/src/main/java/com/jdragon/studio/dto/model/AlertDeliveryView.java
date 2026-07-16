package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AlertDeliveryView {
    private Long id;
    private Long eventId;
    private Long incidentId;
    private String channelType;
    private Long channelId;
    private String channelName;
    private Long recipientUserId;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime lastAttemptAt;
    private Integer httpStatus;
    private String responseExcerpt;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
