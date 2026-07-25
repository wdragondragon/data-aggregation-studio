package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.ProtocolConversionMode;
import com.jdragon.studio.dto.enums.ProtocolConversionProtocol;
import com.jdragon.studio.dto.model.ProtocolConversionFieldMapping;
import com.jdragon.studio.dto.model.ProtocolConversionFixedField;
import com.jdragon.studio.dto.model.TransformerBinding;
import com.jdragon.studio.dto.model.WebServiceConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ProtocolConversionServiceSaveRequest {
    @NotNull(message = "Runtime cluster is required")
    private Long runtimeClusterId;
    private Long id;

    @NotBlank
    private String serviceCode;

    @NotBlank
    private String serviceName;

    private Boolean tokenRequired;
    private String defaultSubscriptionName;

    private ProtocolConversionProtocol sourceProtocol;
    private String sourceMethod;
    private String sourceDataNodePath;
    private WebServiceConfig webserviceConfig;

    private ProtocolConversionMode conversionMode;
    private List<ProtocolConversionFieldMapping> fieldMappings = new ArrayList<ProtocolConversionFieldMapping>();
    private List<TransformerBinding> rawTransformers = new ArrayList<TransformerBinding>();
    private List<ProtocolConversionFixedField> fixedFields = new ArrayList<ProtocolConversionFixedField>();
    private Map<String, Object> bodyBridgeOptions = new LinkedHashMap<String, Object>();
    private Map<String, Object> requestPassthrough = new LinkedHashMap<String, Object>();

    private Long targetDatasourceId;
    private String targetPath;
    private ProtocolConversionProtocol targetProtocol;
    private String targetMethod;
    private Map<String, Object> targetHeaders = new LinkedHashMap<String, Object>();
    private Map<String, Object> targetQuery = new LinkedHashMap<String, Object>();
    private WebServiceConfig targetWebserviceConfig;
    private String targetBodyTemplate;
    private String targetDataNodePath;
    private DataIngestionPayloadMode payloadMode;
    private Integer batchSize;
    private Map<String, Object> responseStatus = new LinkedHashMap<String, Object>();
}
