package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Assistant skill memory learning response")
public class AssistantLearnResponse {
    @Schema(description = "Whether this interaction was accepted into skill memory")
    private Boolean accepted;

    @Schema(description = "Response message")
    private String message;
}
