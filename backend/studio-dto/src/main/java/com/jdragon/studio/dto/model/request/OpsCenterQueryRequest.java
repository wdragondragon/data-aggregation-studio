package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class OpsCenterQueryRequest {
    private String startTime;
    private String endTime;
    private String executionType;
    private String status;
    private String workerGroupCode;
    private Long requestedClusterId;
    private Long actualClusterId;
    private Integer pageNo;
    private Integer pageSize;
}
