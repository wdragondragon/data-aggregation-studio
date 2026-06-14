package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProtocolConversionDebugResult extends ProtocolConversionInvokeResult {
    private Map<String, Object> targetRequest = new LinkedHashMap<String, Object>();
}
