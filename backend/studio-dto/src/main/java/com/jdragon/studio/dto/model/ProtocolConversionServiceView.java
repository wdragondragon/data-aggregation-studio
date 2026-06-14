package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.ProtocolConversionMode;
import com.jdragon.studio.dto.enums.ProtocolConversionProtocol;
import com.jdragon.studio.dto.enums.ProtocolConversionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProtocolConversionServiceView extends BaseDefinition {
    private Long createdBy;
    private String serviceCode;
    private String serviceName;
    private ProtocolConversionStatus status;
    private String endpointPath;
    private String webserviceEndpointPath;
    private String serviceKey;
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
    private String targetDatasourceName;
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
