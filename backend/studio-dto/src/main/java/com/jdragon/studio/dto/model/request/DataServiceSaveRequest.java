package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.DataServiceRequestMethod;
import com.jdragon.studio.dto.enums.DataServiceResponseType;
import com.jdragon.studio.dto.enums.DataServiceSourceType;
import com.jdragon.studio.dto.enums.DataServiceType;
import com.jdragon.studio.dto.model.DataServicePublishParamView;
import com.jdragon.studio.dto.model.DataServiceRequestParamView;
import com.jdragon.studio.dto.model.DataServiceResponseParamView;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

@Data
public class DataServiceSaveRequest {
    private Long id;

    @NotBlank
    private String serviceCode;

    @NotBlank
    private String serviceName;

    private DataServiceType serviceType;
    private DataServiceSourceType sourceType;
    private Long datasourceId;
    private Long modelId;
    private String customSql;
    private DataServiceRequestMethod requestMethod;
    private DataServiceResponseType responseType;
    private Boolean cacheEnabled;
    private Boolean tokenRequired;
    private String defaultSubscriptionName;
    private List<DataServiceRequestParamView> requestParams = new ArrayList<DataServiceRequestParamView>();
    private List<DataServiceResponseParamView> responseParams = new ArrayList<DataServiceResponseParamView>();
    private List<DataServicePublishParamView> publishParams = new ArrayList<DataServicePublishParamView>();
}

