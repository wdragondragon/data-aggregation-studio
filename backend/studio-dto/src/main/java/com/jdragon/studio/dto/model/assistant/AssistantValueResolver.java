package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Assistant input value resolver")
public class AssistantValueResolver {
    @Schema(description = "Input key resolved by this resolver")
    private String inputKey;

    @Schema(description = "Interface code to call")
    private String interfaceCode;

    @Schema(description = "Resolver description")
    private String description;

    @Schema(description = "Resolver parameter template")
    private Map<String, Object> params = new LinkedHashMap<String, Object>();
}
