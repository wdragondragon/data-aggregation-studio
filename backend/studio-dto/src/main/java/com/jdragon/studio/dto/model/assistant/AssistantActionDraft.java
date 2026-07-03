package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Assistant action draft")
public class AssistantActionDraft {
    @Schema(description = "Action code")
    private String actionCode;

    @Schema(description = "Action summary")
    private String summary;

    @Schema(description = "Draft payload")
    private Map<String, Object> payload = new LinkedHashMap<String, Object>();

    @Schema(description = "Preview data")
    private Map<String, Object> preview = new LinkedHashMap<String, Object>();

    @Schema(description = "Confirmation level")
    private String confirmationLevel;
}
