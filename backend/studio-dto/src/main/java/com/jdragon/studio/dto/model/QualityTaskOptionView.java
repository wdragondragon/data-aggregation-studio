package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class QualityTaskOptionView {
    private Long id;
    private Long projectId;
    private String taskName;
    private String ruleName;
    private String ruleDimension;
    private String granularity;
}
