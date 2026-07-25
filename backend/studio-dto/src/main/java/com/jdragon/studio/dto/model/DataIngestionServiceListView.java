package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.DataIngestionRequestFormat;
import com.jdragon.studio.dto.enums.DataIngestionStatus;
import com.jdragon.studio.dto.enums.DataIngestionTargetType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataIngestionServiceListView extends BaseDefinition {
    private Long runtimeClusterId;
    private String runtimeClusterName;
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
    private Integer maxBatchSize;
    private Boolean tokenRequired;
    private String defaultSubscriptionName;
    private Boolean webserviceEnabled;
    private List<String> sourcePositions = new ArrayList<String>();
    private Integer sourceCount;
    private Integer targetCount;
}
