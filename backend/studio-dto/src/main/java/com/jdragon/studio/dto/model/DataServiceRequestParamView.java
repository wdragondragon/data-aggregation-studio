package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataServiceQueryOperator;
import com.jdragon.studio.dto.enums.DataServiceValueType;
import lombok.Data;

@Data
public class DataServiceRequestParamView {
    private Long id;
    private Long serviceId;
    private Integer sortOrder;
    private String paramName;
    private String fieldName;
    private DataServiceValueType valueType;
    private DataServiceQueryOperator queryOperator;
    private Boolean required;
    private String description;
    private Boolean fixedParam;
}
