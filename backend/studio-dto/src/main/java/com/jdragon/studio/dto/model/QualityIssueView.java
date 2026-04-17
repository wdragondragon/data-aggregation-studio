package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.QualityIssueSeverity;
import com.jdragon.studio.dto.enums.QualityIssueStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QualityIssueView {
    private Long id;
    private String issueCode;
    private String issueType;
    private String title;
    private QualityIssueSeverity severity;
    private QualityIssueSeverity systemSeverity;
    private QualityIssueSeverity manualSeverity;
    private QualityIssueStatus status;
    private String assetId;
    private Long datasourceId;
    private String datasourceName;
    private String datasourceTypeCode;
    private Long modelId;
    private String modelName;
    private String modelPhysicalLocator;
    private Long qualityTaskId;
    private String qualityTaskName;
    private Long ruleId;
    private String ruleName;
    private String ruleDimension;
    private String granularity;
    private String columnName;
    private String outputField;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime lastRecoveryAt;
    private LocalDateTime slaDueAt;
    private Long assigneeUserId;
    private String assigneeName;
    private String latestMessage;
    private Long lastRunRecordId;
    private String lastRunStatus;
    private Integer occurrenceCount;
    private Integer consecutiveFailureCount;
    private Integer reopenCount;
    private Boolean overdue;
    private Boolean active;
}
