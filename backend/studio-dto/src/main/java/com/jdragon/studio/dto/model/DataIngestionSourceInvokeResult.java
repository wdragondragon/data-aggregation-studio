package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class DataIngestionSourceInvokeResult {
    private String sourceCode;
    private String sourceName;
    private String targetDatasourceName;
    private String targetModelName;
    private Long receivedCount;
    private Long successCount;
    private Long failedCount;
    private String status;
    private String message;
    private Long jobId;
    private String logSectionKey;
    /** Runtime plugin identities for this target job instance. */
    private Map<String, String> pluginRevisions = new LinkedHashMap<String, String>();
}
