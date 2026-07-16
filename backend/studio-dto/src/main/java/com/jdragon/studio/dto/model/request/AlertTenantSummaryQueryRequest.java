package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class AlertTenantSummaryQueryRequest {
    private String keyword;
    private Integer pageNo;
    private Integer pageSize;
}
