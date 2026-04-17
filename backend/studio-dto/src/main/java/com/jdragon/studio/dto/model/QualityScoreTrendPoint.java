package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class QualityScoreTrendPoint {
    private String dateLabel;
    private Long executionHealthScore;
    private Long governanceRiskScore;
}
