package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class AlertChannelQueryRequest {
    private String keyword;
    private Boolean enabled;
    private Integer pageNo;
    private Integer pageSize;
}
