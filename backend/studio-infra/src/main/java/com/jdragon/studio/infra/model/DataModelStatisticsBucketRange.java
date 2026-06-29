package com.jdragon.studio.infra.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DataModelStatisticsBucketRange {
    private Integer bucketIndex;
    private String bucketKey;
    private BigDecimal lowerBound;
    private BigDecimal upperBound;
    private Boolean lastBucket;
}
