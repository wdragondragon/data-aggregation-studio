package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.model.WorkflowEdgeDefinition;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.WorkflowScheduleDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Workflow draft save request")
public class WorkflowSaveRequest {
    @Schema(description = "Workflow definition id")
    private Long definitionId;

    @NotBlank(message = "Workflow code is required")
    @Schema(description = "Workflow code", required = true)
    private String code;

    @NotBlank(message = "Workflow name is required")
    @Schema(description = "Workflow name", required = true)
    private String name;

    @Schema(description = "Workflow schedule")
    private WorkflowScheduleDefinition schedule;

    @Schema(description = "Workflow nodes")
    private List<WorkflowNodeDefinition> nodes = new ArrayList<WorkflowNodeDefinition>();

    @Schema(description = "Workflow edges")
    private List<WorkflowEdgeDefinition> edges = new ArrayList<WorkflowEdgeDefinition>();
}
