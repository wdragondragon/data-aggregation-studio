package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_service_response_param", autoResultMap = true)
public class DataServiceResponseParamEntity extends BaseStudioEntity {
    private Long serviceId;
    private Integer sortOrder;
    private Integer enabled;
    private String paramName;
    private String fieldName;
    private String exampleValue;
    private String description;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> transformersJson = new ArrayList<Map<String, Object>>();
}
