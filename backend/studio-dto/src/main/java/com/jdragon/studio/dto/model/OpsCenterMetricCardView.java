package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.OpsCenterHealthStatus;
import lombok.Data;

@Data
public class OpsCenterMetricCardView {
    private String key;
    private String label;
    private Long value;
    private OpsCenterHealthStatus status;
    private String hint;
}
