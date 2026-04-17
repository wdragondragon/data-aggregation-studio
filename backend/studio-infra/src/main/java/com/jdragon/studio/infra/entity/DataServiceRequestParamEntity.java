package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_service_request_param")
public class DataServiceRequestParamEntity extends BaseStudioEntity {
    private Long serviceId;
    private Integer sortOrder;
    private String paramName;
    private String fieldName;
    private String valueType;
    private String queryOperator;
    private Integer required;
    private String description;
    private Integer fixedParam;
}
