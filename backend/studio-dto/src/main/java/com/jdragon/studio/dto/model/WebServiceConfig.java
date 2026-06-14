package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.WebServiceSoapVersion;
import lombok.Data;

@Data
public class WebServiceConfig {
    private Boolean enabled;
    private WebServiceSoapVersion soapVersion;
    private String namespaceUri;
    private String operationName;
    private String soapAction;
    private String requestRootName;
    private String responseRootName;
    private String responseDataNodePath;
}
