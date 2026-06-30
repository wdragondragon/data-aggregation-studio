package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataIngestionInvokeResult {
    private String requestId;
    private String serviceCode;
    private Long receivedCount;
    private Long successCount;
    private Long failedCount;
    private String status;
    private List<DataIngestionSourceInvokeResult> sourceResults = new ArrayList<DataIngestionSourceInvokeResult>();
}
