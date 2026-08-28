package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskStreamingOptions;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.dto.enums.CollectionTaskExecutionMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Collection task save request")
public class CollectionTaskSaveRequest {
    @NotNull(message = "Runtime cluster is required")
    @Schema(description = "Runtime cluster id", required = true)
    private Long runtimeClusterId;
    @Schema(description = "Task id")
    private Long id;

    @NotBlank(message = "Task name is required")
    @Schema(description = "Task name", required = true)
    private String name;

    @Schema(description = "Execution mode; historical and omitted values default to BATCH")
    private CollectionTaskExecutionMode executionMode;

    @Schema(description = "Native streaming options, valid only when executionMode is STREAMING")
    private CollectionTaskStreamingOptions streamingOptions;

    @Schema(description = "Source bindings")
    private List<CollectionTaskSourceBinding> sourceBindings = new ArrayList<CollectionTaskSourceBinding>();

    @NotNull(message = "Target binding is required")
    @Schema(description = "Target binding", required = true)
    private CollectionTaskTargetBinding targetBinding;

    @Schema(description = "Field mappings")
    private List<FieldMappingDefinition> fieldMappings = new ArrayList<FieldMappingDefinition>();

    @Schema(description = "Execution options")
    private Map<String, Object> executionOptions = new LinkedHashMap<String, Object>();

    @Schema(description = "Schedule")
    private CollectionTaskScheduleDefinition schedule;
}

