package com.jdragon.studio.dto.model.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class QualityIssueCommentRequest {
    @NotBlank(message = "Comment content is required")
    private String content;
}
