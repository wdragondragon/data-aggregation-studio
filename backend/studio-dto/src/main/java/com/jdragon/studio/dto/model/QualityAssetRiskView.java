package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class QualityAssetRiskView {
    private String assetId;
    private Long datasourceId;
    private String datasourceName;
    private String datasourceTypeCode;
    private Long modelId;
    private String modelName;
    private String modelPhysicalLocator;
    private Long executionHealthScore;
    private Long governanceRiskScore;
    private Long activeIssueCount;
    private Long overdueIssueCount;
    private Long coverageRate;
    private List<String> coverageDimensions = new ArrayList<String>();
    private List<String> riskDimensions = new ArrayList<String>();
    private String latestRunStatus;
    private LocalDateTime latestRunAt;
    private String latestEvidence;
}
