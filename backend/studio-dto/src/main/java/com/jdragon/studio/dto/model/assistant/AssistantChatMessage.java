package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Assistant chat message")
public class AssistantChatMessage {
    @Schema(description = "Message role")
    private String role;

    @Schema(description = "Message content")
    private String content;
}
