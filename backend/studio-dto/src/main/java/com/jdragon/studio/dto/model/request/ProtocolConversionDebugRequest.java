package com.jdragon.studio.dto.model.request;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ProtocolConversionDebugRequest {
    private Map<String, Object> headers = new LinkedHashMap<String, Object>();
    private Map<String, Object> query = new LinkedHashMap<String, Object>();
    private Map<String, Object> form = new LinkedHashMap<String, Object>();
    private Object body;
    private String rawBody;
}
