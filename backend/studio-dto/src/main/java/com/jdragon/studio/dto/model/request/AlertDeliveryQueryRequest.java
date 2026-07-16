package com.jdragon.studio.dto.model.request;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AlertDeliveryQueryRequest {
    private Long incidentId;
    private Long eventId;
    private Long channelId;
    private String channelType;
    private String status;
    private Boolean failedOnly;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer pageNo;
    private Integer pageSize;
}
