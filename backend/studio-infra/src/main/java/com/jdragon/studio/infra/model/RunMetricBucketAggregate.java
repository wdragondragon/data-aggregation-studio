package com.jdragon.studio.infra.model;

import lombok.Data;

@Data
public class RunMetricBucketAggregate {
    private String bucketKey;
    private Long collectedRecords;
    private Long successRecords;
    private Long failedRecords;
    private Long transformerTotalRecords;
    private Long transformerSuccessRecords;
    private Long transformerFailedRecords;
    private Long transformerFilterRecords;
}
