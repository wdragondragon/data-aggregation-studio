package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.DataIngestionRequestFormat;
import com.jdragon.studio.dto.enums.DataIngestionStatus;
import com.jdragon.studio.dto.enums.DataIngestionTargetType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataIngestionServiceView extends BaseDefinition {
    private Long createdBy;
    private String serviceCode;
    private String serviceName;
    private DataIngestionStatus status;
    private DataIngestionRequestFormat requestFormat;
    private DataIngestionPayloadMode payloadMode;
    private String dataNodePath;
    private DataIngestionTargetType targetType;
    private Long datasourceId;
    private String datasourceName;
    private String datasourceTypeCode;
    private Long modelId;
    private String modelName;
    private String modelPhysicalLocator;
    private String endpointPath;
    private String serviceKey;
    private Integer maxBatchSize;
    private Boolean tokenRequired;
    private String defaultSubscriptionName;
    private Boolean webserviceEnabled;
    private WebServiceConfig webserviceConfig;
    private Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
    private List<DataIngestionFieldMapping> fieldMappings = new ArrayList<DataIngestionFieldMapping>();
}
