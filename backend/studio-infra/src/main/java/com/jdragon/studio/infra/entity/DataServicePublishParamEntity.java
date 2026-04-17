package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_service_publish_param")
public class DataServicePublishParamEntity extends BaseStudioEntity {
    private Long serviceId;
    private Integer sortOrder;
    private String frontendParamName;
    private String backendParamName;
    private String position;
    private String valueType;
    private String exampleValue;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String defaultValue;

    private Integer required;
    private String description;
}
