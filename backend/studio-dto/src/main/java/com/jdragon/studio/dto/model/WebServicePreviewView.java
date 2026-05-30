package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class WebServicePreviewView {
    private String endpointPath;
    private String wsdlPath;
    private String wsdl;
    private String sampleRequest;
    private String sampleResponse;
    private String soapAction;
    private String namespaceUri;
    private String operationName;
}
