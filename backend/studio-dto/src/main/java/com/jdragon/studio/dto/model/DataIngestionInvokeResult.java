package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class DataIngestionInvokeResult {
    private String requestId;
    private String serviceCode;
    private Long receivedCount;
    private Long successCount;
    private Long failedCount;
    private String status;
    /**
     * Plugin identities used by the target jobs that served this invocation.
     * This is runtime metadata only; it is not part of the service definition.
     */
    private Map<String, String> pluginRevisions = new LinkedHashMap<String, String>();
    private List<DataIngestionSourceInvokeResult> sourceResults = new ArrayList<DataIngestionSourceInvokeResult>();
}
