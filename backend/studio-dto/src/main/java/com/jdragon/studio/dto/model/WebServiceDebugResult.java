package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class WebServiceDebugResult {
    private Boolean success;
    private Integer httpStatus;
    private String requestEnvelope;
    private String responseEnvelope;
    private Object result;
    private String errorCode;
    private String errorMessage;
}
