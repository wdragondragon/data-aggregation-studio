package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_model_lineage_relation")
public class DataModelLineageRelationEntity extends BaseProjectTenantEntity {
    private String level;
    private String sourceType;
    private Long collectionTaskId;
    private String collectionTaskNameSnapshot;
    private Long sourceDatasourceId;
    private String sourceDatasourceNameSnapshot;
    private String sourceDatasourceTypeSnapshot;
    private String sourceDatabaseNameSnapshot;
    private String sourceHostSnapshot;
    private String sourcePortSnapshot;
    private Long sourceModelId;
    private String sourceModelNameSnapshot;
    private String sourceModelLocatorSnapshot;
    private String sourceFieldKey;
    private Long targetDatasourceId;
    private String targetDatasourceNameSnapshot;
    private String targetDatasourceTypeSnapshot;
    private String targetDatabaseNameSnapshot;
    private String targetHostSnapshot;
    private String targetPortSnapshot;
    private Long targetModelId;
    private String targetModelNameSnapshot;
    private String targetModelLocatorSnapshot;
    private String targetFieldKey;
    private String mappingMode;
    private String expressionSnapshot;
    private Long manualMaintainerUserId;
    private String manualMaintainerNameSnapshot;
    private Long latestRunId;
    private String latestRunStatus;
    private LocalDateTime latestRunAt;
}
