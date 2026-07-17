package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AlertDeliveryView {
    private Long id;
    private Long eventId;
    private Long incidentId;
    private Long ruleId;
    private String ruleName;
    private String ruleType;
    private String severity;
    private String eventType;
    private LocalDateTime occurredAt;
    private String subjectType;
    private Long subjectId;
    private String subjectName;
    private String targetPath;
    private String summary;
    private String channelType;
    private Long channelId;
    private String channelName;
    private Long recipientUserId;
    private String recipientDisplay;
    private String messageFormat;
    private String messageTitle;
    private String messageContent;
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
