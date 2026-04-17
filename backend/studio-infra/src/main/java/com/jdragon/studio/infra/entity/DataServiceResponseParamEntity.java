package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_service_response_param")
public class DataServiceResponseParamEntity extends BaseStudioEntity {
    private Long serviceId;
    private Integer sortOrder;
    private Integer enabled;
    private String paramName;
    private String fieldName;
    private String exampleValue;
    private String description;
}
