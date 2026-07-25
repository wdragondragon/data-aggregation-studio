package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class RuntimeAssistantScriptExecuteRequest {

    @NotNull
    private Long targetClusterId;

    @NotBlank
    private String targetClusterCode;

    @NotBlank
    private String tenantId;

    @NotNull
    private Long projectId;

    private Long userId;
    private String username;

    @NotBlank
    private String entrypointId;

    @NotNull
    private Map<String, Object> input = new LinkedHashMap<String, Object>();
}
