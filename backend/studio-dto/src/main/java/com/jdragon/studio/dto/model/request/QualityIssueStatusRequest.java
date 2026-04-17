package com.jdragon.studio.dto.model.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class QualityIssueStatusRequest {
    @NotBlank(message = "Issue status is required")
    private String status;
    private String comment;
}
