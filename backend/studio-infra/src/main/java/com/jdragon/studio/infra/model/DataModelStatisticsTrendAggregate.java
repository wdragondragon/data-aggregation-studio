package com.jdragon.studio.infra.model;

import lombok.Data;

@Data
public class DataModelStatisticsTrendAggregate {
    private String bucketKey;
    private Long count;
}
