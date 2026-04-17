package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataServiceRequestMethod;
import com.jdragon.studio.dto.enums.DataServiceResponseType;
import com.jdragon.studio.dto.enums.DataServiceSourceType;
import com.jdragon.studio.dto.enums.DataServiceStatus;
import com.jdragon.studio.dto.enums.DataServiceType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataServiceDefinitionView extends BaseDefinition {
    private Long createdBy;
    private String serviceCode;
    private String serviceName;
    private DataServiceType serviceType;
    private DataServiceStatus status;
    private DataServiceSourceType sourceType;
    private Long datasourceId;
    private String datasourceName;
    private String datasourceTypeCode;
    private Long modelId;
    private String modelName;
    private String modelPhysicalLocator;
    private String customSql;
    private DataServiceRequestMethod requestMethod;
    private DataServiceResponseType responseType;
    private String endpointPath;
    private String serviceKey;
    private Boolean cacheEnabled;
    private List<DataServiceRequestParamView> requestParams = new ArrayList<DataServiceRequestParamView>();
    private List<DataServiceResponseParamView> responseParams = new ArrayList<DataServiceResponseParamView>();
    private List<DataServicePublishParamView> publishParams = new ArrayList<DataServicePublishParamView>();
}
