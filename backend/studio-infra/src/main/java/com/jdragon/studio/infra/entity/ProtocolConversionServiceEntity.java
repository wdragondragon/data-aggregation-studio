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
@TableName(value = "protocol_conversion_service", autoResultMap = true)
public class ProtocolConversionServiceEntity extends BaseProjectTenantEntity {
    private Long runtimeClusterId;
    private Long createdBy;
    private String serviceCode;
    private String serviceName;
    private String status;
    private String endpointPath;
    private String webserviceEndpointPath;
    private String serviceKey;
    private Integer tokenRequired;
    private String defaultSubscriptionName;

    private String sourceProtocol;
    private String sourceMethod;
    private String sourceDataNodePath;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> webserviceConfigJson = new LinkedHashMap<String, Object>();

    private String conversionMode;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> fieldMappingsJson = new ArrayList<Map<String, Object>>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> rawTransformersJson = new ArrayList<Map<String, Object>>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> fixedFieldsJson = new ArrayList<Map<String, Object>>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> bodyBridgeOptionsJson = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> requestPassthroughJson = new LinkedHashMap<String, Object>();

    private Long targetDatasourceId;
    private String targetDatasourceNameSnapshot;
    private String targetPath;
    private String targetProtocol;
    private String targetMethod;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> targetHeadersJson = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> targetQueryJson = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> targetWebserviceConfigJson = new LinkedHashMap<String, Object>();

    private String targetBodyTemplate;
    private String targetDataNodePath;
    private String payloadMode;
    private Integer batchSize;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> responseStatusJson = new LinkedHashMap<String, Object>();
}
