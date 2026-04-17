package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quality_metric_snapshot")
public class QualityMetricSnapshotEntity extends BaseProjectTenantEntity {
    private LocalDate snapshotDate;
    private Long datasourceId;
    private String datasourceNameSnapshot;
    private String datasourceTypeCode;
    private Long modelId;
    private String modelNameSnapshot;
    private String modelPhysicalLocator;
    private String ruleDimension;
    private Long executionHealthScore;
    private Long governanceRiskScore;
    private Long activeIssueCount;
    private Long overdueIssueCount;
    private Long coverageRate;
    private Long failureRate;
    private Long affectedAssetCount;
    private Long reopenRate;
}
