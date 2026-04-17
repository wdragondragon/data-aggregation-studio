package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class DataServiceResponseParamView {
    private Long id;
    private Long serviceId;
    private Integer sortOrder;
    private Boolean enabled;
    private String paramName;
    private String fieldName;
    private String exampleValue;
    private String description;
}
