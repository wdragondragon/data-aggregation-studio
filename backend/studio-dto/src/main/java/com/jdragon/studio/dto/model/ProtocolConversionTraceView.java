package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class ProtocolConversionTraceView {
    private String requestId;
    private ProtocolConversionTraceStepView sourceRequest;
    private ProtocolConversionTraceStepView convertedRequest;
    private ProtocolConversionTraceStepView targetResponse;
    private ProtocolConversionTraceStepView convertedResponse;
}
