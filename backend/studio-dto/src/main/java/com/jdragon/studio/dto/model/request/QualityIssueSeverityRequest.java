package com.jdragon.studio.dto.model.request;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class QualityIssueSeverityRequest {
    @NotBlank(message = "Issue severity is required")
    private String severity;
    private String comment;
}

