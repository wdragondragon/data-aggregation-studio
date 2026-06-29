package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_ingestion_service", autoResultMap = true)
public class DataIngestionServiceEntity extends BaseProjectTenantEntity {
    private Long createdBy;
    private String serviceCode;
    private String serviceName;
    private String status;
    private String requestFormat;
    private String payloadMode;
    private String dataNodePath;
    private String targetType;
    private Long datasourceId;
    private String datasourceNameSnapshot;
    private String datasourceTypeCode;
    private Long modelId;
    private String modelNameSnapshot;
    private String modelPhysicalLocator;
    private String endpointPath;
    private String serviceKey;
    private Integer maxBatchSize;
    private Integer tokenRequired;
    private String defaultSubscriptionName;
    private Integer webserviceEnabled;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> webserviceConfigJson = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> writerOptionsJson = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> fieldMappingsJson = new ArrayList<Map<String, Object>>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> sourcePositionsJson = new ArrayList<String>();
}
