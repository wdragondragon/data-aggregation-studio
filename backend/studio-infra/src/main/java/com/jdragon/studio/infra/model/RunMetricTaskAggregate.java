package com.jdragon.studio.infra.model;

import lombok.Data;

@Data
public class RunMetricTaskAggregate {
    private Long collectionTaskId;
    private Long readSucceedRecords;
    private Long successRecords;
    private Long preciseRunCount;
    private Long legacyRunCount;
}
