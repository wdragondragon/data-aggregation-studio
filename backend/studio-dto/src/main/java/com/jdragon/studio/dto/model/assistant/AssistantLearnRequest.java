package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Assistant skill memory learning request")
public class AssistantLearnRequest extends AssistantPlanRequest {
    @Schema(description = "Assistant answer or useful process summary to persist")
    private String assistantContent;
}
