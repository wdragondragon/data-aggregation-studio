package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class AlertChannelSaveRequest {
    private Long id;
    @NotBlank
    private String name;
    private String endpointUrl;
    private Map<String, String> headers;
    private String signingSecret;
    private Boolean clearSigningSecret;
    private Boolean enabled;
}
