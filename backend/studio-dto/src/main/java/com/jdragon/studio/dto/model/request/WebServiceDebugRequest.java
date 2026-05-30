package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.WebServiceSoapVersion;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class WebServiceDebugRequest {
    private String soapEnvelope;
    private WebServiceSoapVersion soapVersion;
    private Map<String, Object> headers = new LinkedHashMap<String, Object>();
}
