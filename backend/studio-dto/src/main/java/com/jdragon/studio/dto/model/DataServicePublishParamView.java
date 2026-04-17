package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataServiceParamPosition;
import com.jdragon.studio.dto.enums.DataServiceValueType;
import lombok.Data;

@Data
public class DataServicePublishParamView {
    private Long id;
    private Long serviceId;
    private Integer sortOrder;
    private String frontendParamName;
    private String backendParamName;
    private DataServiceParamPosition position;
    private DataServiceValueType valueType;
    private String exampleValue;
    private String defaultValue;
    private Boolean required;
    private String description;
}
