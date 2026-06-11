package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.WebServiceSoapVersion;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import com.jdragon.studio.dto.model.WebServiceConfig;
import com.jdragon.studio.dto.model.WebServicePreviewView;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServiceSupportTest {

    private final WebServiceSupport support = new WebServiceSupport();

    @Test
    void shouldParseSoap11HeaderAndBodyByLocalName() {
        WebServiceSupport.ParsedSoapRequest parsed = support.parse("""
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:svc="urn:test" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <soapenv:Header>
                    <svc:dataIngestionToken>token-123</svc:dataIngestionToken>
                  </soapenv:Header>
                  <soapenv:Body>
                    <svc:SubmitOrder>
                      <svc:customer>
                        <svc:name>Alice</svc:name>
                      </svc:customer>
                      <svc:records>
                        <svc:record><svc:id>1</svc:id></svc:record>
                        <svc:record><svc:id>2</svc:id><svc:name xsi:nil="true"/></svc:record>
                      </svc:records>
                    </svc:SubmitOrder>
                  </soapenv:Body>
                </soapenv:Envelope>
                """);

        assertEquals(WebServiceSoapVersion.SOAP_11, parsed.getSoapVersion());
        assertEquals("SubmitOrder", parsed.getOperationName());
        assertEquals("token-123", support.tokenFromSoapHeader(parsed, "dataIngestionToken"));
        assertEquals("Alice", nested(parsed.getBody(), "customer", "name"));
        Object records = nested(parsed.getBody(), "records", "record");
        assertInstanceOf(List.class, records);
        assertEquals(2, ((List<?>) records).size());
        Object second = ((List<?>) records).get(1);
        assertInstanceOf(Map.class, second);
        assertNull(((Map<?, ?>) second).get("name"));
    }

    @Test
    void shouldParseSoap12Envelope() {
        WebServiceSupport.ParsedSoapRequest parsed = support.parse("""
                <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
                  <env:Body><Ping><id>100</id></Ping></env:Body>
                </env:Envelope>
                """);

        assertEquals(WebServiceSoapVersion.SOAP_12, parsed.getSoapVersion());
        assertEquals("Ping", parsed.getOperationName());
        assertEquals("100", parsed.getBody().get("id"));
    }

    @Test
    void shouldRejectDoctypePayload() {
        StudioException exception = assertThrows(StudioException.class, () -> support.parse("""
                <?xml version="1.0"?>
                <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body><Ping><value>&xxe;</value></Ping></soap:Body>
                </soap:Envelope>
                """));

        assertEquals(StudioErrorCode.BAD_REQUEST, exception.getCode());
        assertTrue(exception.getMessage().contains("Invalid SOAP envelope"));
    }

    @Test
    void shouldGenerateSoapSuccessAndFaultEnvelope() {
        WebServiceConfig config = new WebServiceConfig();
        config.setSoapVersion(WebServiceSoapVersion.SOAP_12);
        config.setNamespaceUri("urn:test");
        config.setOperationName("Submit");
        config.setRequestRootName("Submit");
        config.setResponseRootName("SubmitResponse");

        String success = support.successEnvelope(config, Collections.singletonMap("message", "A&B"), WebServiceSoapVersion.SOAP_12);
        assertTrue(success.contains("http://www.w3.org/2003/05/soap-envelope"));
        assertTrue(success.contains("<tns:SubmitResponse>"));
        assertTrue(success.contains("<message>A&amp;B</message>"));

        String fault = support.faultEnvelope(WebServiceSoapVersion.SOAP_12, StudioErrorCode.UNAUTHORIZED, "token missing");
        assertTrue(fault.contains("<soap:Fault>"));
        assertTrue(fault.contains("<code>UNAUTHORIZED</code>"));
        assertTrue(fault.contains("token missing"));
    }

    @Test
    void shouldPreviewIngestionWsdlAndSampleRequest() {
        DataIngestionServiceView service = new DataIngestionServiceView();
        service.setServiceCode("order_ingest");
        service.setServiceName("Order Ingest");
        service.setPayloadMode(DataIngestionPayloadMode.OBJECT);
        service.setFieldMappings(List.of(mapping("order.id", FieldValueType.LONG), mapping("customerName", FieldValueType.STRING)));

        WebServiceConfig config = new WebServiceConfig();
        config.setEnabled(Boolean.TRUE);
        config.setNamespaceUri("urn:order");
        config.setOperationName("SubmitOrder");
        service.setWebserviceConfig(config);

        WebServicePreviewView preview = support.previewForDataIngestion(service, "/openapi/ws/data-ingestion-services/order_ingest/key");

        assertEquals("/openapi/ws/data-ingestion-services/order_ingest/key?wsdl", preview.getWsdlPath());
        assertTrue(preview.getWsdl().contains("SubmitOrder"));
        assertTrue(preview.getSampleRequest().contains("<soap:Header><tns:token>your-token</tns:token></soap:Header>"));
        assertTrue(preview.getSampleRequest().contains("<order>"));
        assertTrue(preview.getSampleResponse().contains("<serviceCode>order_ingest</serviceCode>"));
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> source, String first, String second) {
        Object child = source.get(first);
        assertInstanceOf(Map.class, child);
        return ((Map<String, Object>) child).get(second);
    }

    private static DataIngestionFieldMapping mapping(String sourceField, FieldValueType valueType) {
        DataIngestionFieldMapping mapping = new DataIngestionFieldMapping();
        mapping.setSourcePosition(DataIngestionSourcePosition.BODY);
        mapping.setSourceField(sourceField);
        mapping.setTargetField(sourceField.replace('.', '_'));
        mapping.setValueType(valueType);
        return mapping;
    }
}
