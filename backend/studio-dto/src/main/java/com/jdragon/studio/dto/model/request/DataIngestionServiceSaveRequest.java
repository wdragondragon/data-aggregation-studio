package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.DataIngestionRequestFormat;
import com.jdragon.studio.dto.enums.DataIngestionTargetType;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionSourceBinding;
import com.jdragon.studio.dto.model.WebServiceConfig;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class DataIngestionServiceSaveRequest {
    private Long id;

    @NotBlank
    private String serviceCode;

    @NotBlank
    private String serviceName;

    private DataIngestionRequestFormat requestFormat;
    private DataIngestionPayloadMode payloadMode;
    private String dataNodePath;
    private DataIngestionTargetType targetType;
    private Long datasourceId;
    private Long modelId;
    private Integer maxBatchSize;
    private Boolean tokenRequired;
    private String defaultSubscriptionName;
    private Boolean webserviceEnabled;
    private WebServiceConfig webserviceConfig;
    private Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
    private List<DataIngestionFieldMapping> fieldMappings = new ArrayList<DataIngestionFieldMapping>();
    private List<DataIngestionSourceBinding> sourceBindings = new ArrayList<DataIngestionSourceBinding>();
}
