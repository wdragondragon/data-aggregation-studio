package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class AlertChannelSaveRequest {
    private Long id;
    @NotBlank
    private String name;
    private String channelType;
    private String endpointUrl;
    private Map<String, String> headers;
    private String signingSecret;
    private Boolean clearSigningSecret;
    private String elinkRecipientMode;
    private String elinkTargetType;
    private List<String> elinkUserIds = new ArrayList<String>();
    private Long elinkGroupId;
    private Boolean enabled;
}
