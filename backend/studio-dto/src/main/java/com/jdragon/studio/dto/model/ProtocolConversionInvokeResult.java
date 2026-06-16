package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class ProtocolConversionInvokeResult {
    private String requestId;
    private String serviceCode;
    private String sourceProtocol;
    private String status;
    private Integer targetHttpStatus;
    private String targetContentType;
    private Object targetBody;
    private Object responseBody;
    private Long receivedCount;
    private Long successCount;
    private Long failedCount;
}
