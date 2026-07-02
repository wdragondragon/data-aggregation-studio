package com.jdragon.studio.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jdragon.studio.dto.enums.DataServiceRequestMethod;
import com.jdragon.studio.dto.enums.DataServiceResponseType;
import com.jdragon.studio.dto.enums.DataServiceSourceType;
import com.jdragon.studio.dto.enums.DataServiceStatus;
import com.jdragon.studio.dto.enums.DataServiceType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode(callSuper = true)
public class DataServiceListView extends BaseDefinition {
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
    private DataServiceRequestMethod requestMethod;
    private DataServiceResponseType responseType;
    private String endpointPath;
    private Boolean cacheEnabled;
    private Boolean tokenRequired;
    private String defaultSubscriptionName;
    private Boolean webserviceEnabled;
}
