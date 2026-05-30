package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_service_definition", autoResultMap = true)
public class DataServiceDefinitionEntity extends BaseProjectTenantEntity {
    private Long createdBy;
    private String serviceCode;
    private String serviceName;
    private String serviceType;
    private String status;
    private String sourceType;
    private Long datasourceId;
    private String datasourceNameSnapshot;
    private String datasourceTypeCode;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long modelId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String modelNameSnapshot;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String modelPhysicalLocator;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String customSql;

    private String requestMethod;
    private String responseType;
    private String endpointPath;
    private String serviceKey;
    private Integer cacheEnabled;
    private Integer tokenRequired;
    private String defaultSubscriptionName;
    private Integer webserviceEnabled;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> webserviceConfigJson = new LinkedHashMap<String, Object>();
}
