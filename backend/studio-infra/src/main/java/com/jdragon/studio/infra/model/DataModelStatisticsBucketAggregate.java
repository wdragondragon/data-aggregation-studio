package com.jdragon.studio.infra.model;

import lombok.Data;

@Data
public class DataModelStatisticsBucketAggregate {
    private String bucketKey;
    private Integer bucketIndex;
    private Long count;
}
