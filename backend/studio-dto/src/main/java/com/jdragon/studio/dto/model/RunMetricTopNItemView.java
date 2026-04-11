package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class RunMetricTopNItemView {
    private Long id;
    private String name;
    private String label;
    private String typeCode;
    private Long count;
}
