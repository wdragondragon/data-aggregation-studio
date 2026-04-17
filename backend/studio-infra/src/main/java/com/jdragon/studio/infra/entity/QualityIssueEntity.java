package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "quality_issue", autoResultMap = true)
public class QualityIssueEntity extends BaseProjectTenantEntity {
    private String issueCode;
    private String signature;
    private String issueType;
    private Long qualityTaskId;
    private String qualityTaskNameSnapshot;
    private Long ruleId;
    private String ruleNameSnapshot;
    private String ruleDimension;
    private Long datasourceId;
    private String datasourceNameSnapshot;
    private String datasourceTypeCode;
    private Long modelId;
    private String modelNameSnapshot;
    private String modelPhysicalLocator;
    private String columnName;
    private String outputField;
    private String granularity;
    private String title;
    private String latestMessage;
    private String severity;
    private String systemSeverity;
    private String manualSeverity;
    private String status;
    private Long assigneeUserId;
    private String assigneeNameSnapshot;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime lastRecoveryAt;
    private LocalDateTime slaDueAt;
    private Integer occurrenceCount;
    private Integer consecutiveFailureCount;
    private Integer reopenCount;
    private Long lastRunRecordId;
    private String lastRunStatus;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> currentEvidenceJson = new LinkedHashMap<String, Object>();
}
