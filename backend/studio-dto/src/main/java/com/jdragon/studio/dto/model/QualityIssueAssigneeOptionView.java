package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class QualityIssueAssigneeOptionView {
    private Long userId;
    private String username;
    private String displayName;
    private String label;
}
