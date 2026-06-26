package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.ProtocolConversionMode;
import com.jdragon.studio.dto.enums.ProtocolConversionProtocol;
import com.jdragon.studio.dto.enums.ProtocolConversionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProtocolConversionServiceListView extends BaseDefinition {
    private Long createdBy;
    private String serviceCode;
    private String serviceName;
    private ProtocolConversionStatus status;
    private String endpointPath;
    private String webserviceEndpointPath;
    private Boolean tokenRequired;
    private String defaultSubscriptionName;
    private ProtocolConversionProtocol sourceProtocol;
    private String sourceMethod;
    private String sourceDataNodePath;
    private ProtocolConversionMode conversionMode;
    private Long targetDatasourceId;
    private String targetDatasourceName;
    private String targetPath;
    private ProtocolConversionProtocol targetProtocol;
    private String targetMethod;
    private DataIngestionPayloadMode payloadMode;
    private Integer batchSize;
}
