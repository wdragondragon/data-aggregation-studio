package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class NotificationQueryRequest {
    private Integer pageNo;
    private Integer pageSize;
    private Boolean unreadOnly;
}
