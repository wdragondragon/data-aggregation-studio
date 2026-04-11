package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class RunMetricSummaryView {
    private Long collectedRecords;
    private Long successRecords;
    private Long readSucceedRecords;
    private Long readFailedRecords;
    private Long writeSucceedRecords;
    private Long writeFailedRecords;
    private Long failedRecords;
    private Long transformerTotalRecords;
    private Long transformerSuccessRecords;
    private Long transformerFailedRecords;
    private Long transformerFilterRecords;
}
