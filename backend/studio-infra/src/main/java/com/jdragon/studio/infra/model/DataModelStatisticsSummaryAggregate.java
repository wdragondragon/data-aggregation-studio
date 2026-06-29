package com.jdragon.studio.infra.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DataModelStatisticsSummaryAggregate {
    private Long matchedModelCount;
    private Long matchedItemCount;
    private Long distinctCount;
    private Long numericCount;
    private BigDecimal minValue;
    private BigDecimal maxValue;
    private BigDecimal sumValue;
    private BigDecimal avgValue;
}
