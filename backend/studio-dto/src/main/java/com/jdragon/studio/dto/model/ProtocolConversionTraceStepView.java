package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ProtocolConversionTraceStepView {
    private String key;
    private String title;
    private String status;
    private String protocol;
    private String method;
    private String url;
    private Integer httpStatus;
    private String contentType;
    private String bodyFormat;
    private String summary;
    private String errorMessage;
    private Map<String, Object> headers = new LinkedHashMap<String, Object>();
    private Map<String, Object> query = new LinkedHashMap<String, Object>();
    private Map<String, Object> form = new LinkedHashMap<String, Object>();
    private String bodyPreview;
}
