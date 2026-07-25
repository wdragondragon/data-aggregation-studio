package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.QualityTaskAlertConfig;
import com.jdragon.studio.dto.model.QualityTaskParamBinding;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Quality task save request")
public class QualityTaskSaveRequest {
    @NotNull(message = "Runtime cluster is required")
    @Schema(description = "Runtime cluster id", required = true)
    private Long runtimeClusterId;
    @Schema(description = "Task id")
    private Long id;

    @NotBlank(message = "Task name is required")
    @Schema(description = "Task name", required = true)
    private String taskName;

    @NotBlank(message = "Task code is required")
    @Schema(description = "Task code", required = true)
    private String taskCode;

    @NotNull(message = "Rule id is required")
    @Schema(description = "Rule id", required = true)
    private Long ruleId;

    @NotNull(message = "Granularity is required")
    @Schema(description = "Granularity", required = true)
    private QualityRuleGranularity granularity;

    @NotNull(message = "Datasource is required")
    @Schema(description = "Datasource id", required = true)
    private Long datasourceId;

    @NotNull(message = "Model is required")
    @Schema(description = "Model id", required = true)
    private Long modelId;

    @Schema(description = "Column name")
    private String columnName;

    @Schema(description = "Where clause")
    private String whereClause;

    @Schema(description = "Resolved SQL preview")
    private String resolvedSqlPreview;

    @Schema(description = "Parameter bindings")
    private List<QualityTaskParamBinding> parameterBindings = new ArrayList<QualityTaskParamBinding>();

    @Schema(description = "Alert configs")
    private List<QualityTaskAlertConfig> alertConfigs = new ArrayList<QualityTaskAlertConfig>();

    @Schema(description = "Schedule")
    private CollectionTaskScheduleDefinition schedule;
}

