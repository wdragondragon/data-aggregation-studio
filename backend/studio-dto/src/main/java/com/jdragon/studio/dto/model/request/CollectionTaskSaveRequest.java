package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Collection task save request")
public class CollectionTaskSaveRequest {
    @Schema(description = "Task id")
    private Long id;

    @NotBlank(message = "Task name is required")
    @Schema(description = "Task name", required = true)
    private String name;

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
