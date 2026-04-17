package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class DataServiceMetricOptionView {
    private Long id;
    private String name;
    private String label;
    private String status;
    private Long serviceId;
    private String serviceName;
}
