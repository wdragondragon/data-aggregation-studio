package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.ProtocolConversionMode;
import com.jdragon.studio.dto.enums.ProtocolConversionProtocol;
import com.jdragon.studio.dto.enums.WebServiceSoapVersion;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import com.jdragon.studio.dto.model.DataServiceDefinitionView;
import com.jdragon.studio.dto.model.DataServiceResponseParamView;
import com.jdragon.studio.dto.model.ProtocolConversionServiceView;
import com.jdragon.studio.dto.model.ProtocolConversionTraceStepView;
import com.jdragon.studio.dto.model.WebServiceConfig;
import com.jdragon.studio.dto.model.WebServicePreviewView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void shouldPreviewDataServiceWithDefaultResponseDataNode() {
        DataServiceDefinitionView service = dataService("orders_query");
        service.setResponseParams(List.of(responseParam("id", "1")));

        WebServiceConfig config = new WebServiceConfig();
        config.setEnabled(Boolean.TRUE);
        config.setNamespaceUri("urn:orders");
        config.setOperationName("QueryOrders");
        service.setWebserviceConfig(config);

        WebServicePreviewView preview = support.previewForDataService(service, "/openapi/ws/data-services/orders_query/key");

        assertTrue(preview.getSampleRequest().contains("<soap:Header><tns:token>your-token</tns:token></soap:Header>"));
        assertTrue(preview.getWsdl().contains("<xsd:element name=\"table\""));
        assertTrue(preview.getSampleResponse().contains("<table><row><id>1</id></row></table>"));
    }

    @Test
    void shouldOmitDataServiceTokenHeaderWhenTokenIsNotRequired() {
        DataServiceDefinitionView service = dataService("orders_query");
        service.setTokenRequired(Boolean.FALSE);
        service.setResponseParams(List.of(responseParam("id", "1")));

        WebServiceConfig config = new WebServiceConfig();
        config.setEnabled(Boolean.TRUE);
        config.setNamespaceUri("urn:orders");
        config.setOperationName("QueryOrders");
        service.setWebserviceConfig(config);

        WebServicePreviewView preview = support.previewForDataService(service, "/openapi/ws/data-services/orders_query/key");

        assertFalse(preview.getSampleRequest().contains("<soap:Header>"));
        assertFalse(preview.getSampleRequest().contains("your-token"));
        assertTrue(preview.getSampleRequest().contains("<soap:Body>"));
        assertTrue(preview.getSampleRequest().contains("<tns:QueryOrders>"));
    }

    @Test
    void shouldGenerateDataServiceResponseWithCustomEmptyDataNode() {
        WebServiceConfig config = new WebServiceConfig();
        config.setSoapVersion(WebServiceSoapVersion.SOAP_11);
        config.setNamespaceUri("urn:orders");
        config.setResponseRootName("QueryOrdersResponse");
        config.setResponseDataNodePath("result.items.item");

        Map<String, Object> table = Map.of("bodies", new ArrayList<>());
        Map<String, Object> payload = Map.of(
                "pageNum", Integer.valueOf(1),
                "pageSize", Integer.valueOf(10),
                "pages", Integer.valueOf(0),
                "table", table);

        String response = support.successEnvelope(config, payload, WebServiceSoapVersion.SOAP_11);

        assertTrue(response.contains("<result><items><item xsi:nil=\"true\"/></items></result>"));
        assertTrue(response.contains("<pages>0</pages>"));
    }

    @Test
    void shouldTreatNilProtocolConversionSourceDataNodeAsEmptyRows() throws Exception {
        ProtocolConversionService service = protocolConversionService();
        ProtocolConversionServiceView view = new ProtocolConversionServiceView();
        view.setSourceDataNodePath("table.row");
        Method sourceRows = ProtocolConversionService.class.getDeclaredMethod(
                "sourceRows", ProtocolConversionServiceView.class, Object.class);
        sourceRows.setAccessible(true);

        Map<String, Object> table = new LinkedHashMap<>();
        table.put("row", null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("table", table);

        Object rows = sourceRows.invoke(service, view, payload);

        assertInstanceOf(List.class, rows);
        assertTrue(((List<?>) rows).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldMaskSensitiveProtocolConversionTraceDiagnostics() throws Exception {
        ProtocolConversionService service = protocolConversionServiceWithInvocationLog();
        ProtocolConversionServiceView view = new ProtocolConversionServiceView();
        view.setSourceProtocol(ProtocolConversionProtocol.HTTP_JSON);
        Method sourceRequestTraceStep = ProtocolConversionService.class.getDeclaredMethod(
                "sourceRequestTraceStep",
                ProtocolConversionServiceView.class,
                String.class,
                Map.class,
                Map.class,
                Map.class,
                Object.class);
        sourceRequestTraceStep.setAccessible(true);

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("X-S14-Secret-Token", "raw-header-secret");
        headers.put("X-S14-Trace", "长期回归S14请求追踪");
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("api_key", "raw-query-api-key");
        query.put("businessTrace", "长期回归S14查询追踪");
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("clientSecret", "raw-form-secret");
        form.put("formTrace", "长期回归S14表单追踪");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", "raw-body-password");
        body.put("customerName", "长期回归S14协议转换客户");

        ProtocolConversionTraceStepView step = (ProtocolConversionTraceStepView) sourceRequestTraceStep.invoke(
                service, view, "POST", headers, query, form, body);

        assertEquals("******", step.getHeaders().get("X-S14-Secret-Token"));
        assertEquals("长期回归S14请求追踪", step.getHeaders().get("X-S14-Trace"));
        assertEquals("******", step.getQuery().get("api_key"));
        assertEquals("长期回归S14查询追踪", step.getQuery().get("businessTrace"));
        assertEquals("******", step.getForm().get("clientSecret"));
        assertEquals("长期回归S14表单追踪", step.getForm().get("formTrace"));
        assertFalse(step.getBodyPreview().contains("raw-body-password"));
        assertTrue(step.getBodyPreview().contains("长期回归S14协议转换客户"));

        Method sanitizeTargetRequest = ProtocolConversionService.class.getDeclaredMethod("sanitizeTargetRequest", Map.class);
        sanitizeTargetRequest.setAccessible(true);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("url", "http://127.0.0.1:18080/openapi/mock?api_key=raw-target-key&businessTrace=长期回归S14目标追踪");
        target.put("headers", Map.of("Authorization", "Bearer raw-target-token", "X-Business-Trace", "长期回归S14目标Header"));
        target.put("body", "{\"clientSecret\":\"raw-target-secret\",\"customerName\":\"长期回归S14目标客户\"}");

        Map<String, Object> sanitizedTarget = (Map<String, Object>) sanitizeTargetRequest.invoke(service, target);

        assertFalse(String.valueOf(sanitizedTarget.get("url")).contains("raw-target-key"));
        assertTrue(String.valueOf(sanitizedTarget.get("url")).contains("businessTrace=长期回归S14目标追踪"));
        assertEquals("******", ((Map<String, Object>) sanitizedTarget.get("headers")).get("Authorization"));
        assertEquals("长期回归S14目标Header", ((Map<String, Object>) sanitizedTarget.get("headers")).get("X-Business-Trace"));
        assertFalse(String.valueOf(sanitizedTarget.get("body")).contains("raw-target-secret"));
        assertTrue(String.valueOf(sanitizedTarget.get("body")).contains("长期回归S14目标客户"));
    }

    @Test
    void shouldParseNilProtocolConversionSoapTableAsStructuredEmptyTable() throws Exception {
        ProtocolConversionService service = protocolConversionService();
        Method parseXmlToMap = ProtocolConversionService.class.getDeclaredMethod("parseXmlToMap", String.class);
        parseXmlToMap.setAccessible(true);
        Method extractResponseBody = ProtocolConversionService.class.getDeclaredMethod(
                "extractResponseBody", ProtocolConversionProtocol.class, Object.class);
        extractResponseBody.setAccessible(true);

        Object parsed = parseXmlToMap.invoke(service, """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <soap:Body>
                    <queryRowsResponse>
                      <pageNum>1</pageNum><pageSize>10</pageSize><pages>0</pages>
                      <table><row xsi:nil="true"/></table>
                    </queryRowsResponse>
                  </soap:Body>
                </soap:Envelope>
                """);

        Object body = extractResponseBody.invoke(service, ProtocolConversionProtocol.SOAP_11, parsed);

        assertInstanceOf(Map.class, body);
        assertNull(nested((Map<String, Object>) body, "table", "row"));
    }

    @Test
    void shouldNormalizeLegacyEmptyProtocolConversionSoapTable() throws Exception {
        ProtocolConversionService service = protocolConversionService();
        Method parseXmlToMap = ProtocolConversionService.class.getDeclaredMethod("parseXmlToMap", String.class);
        parseXmlToMap.setAccessible(true);
        Method extractResponseBody = ProtocolConversionService.class.getDeclaredMethod(
                "extractResponseBody", ProtocolConversionProtocol.class, Object.class);
        extractResponseBody.setAccessible(true);

        Object parsed = parseXmlToMap.invoke(service, """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <queryRowsResponse>
                      <pageNum>1</pageNum><pageSize>10</pageSize><pages>0</pages>
                      <table></table>
                    </queryRowsResponse>
                  </soap:Body>
                </soap:Envelope>
                """);

        Object body = extractResponseBody.invoke(service, ProtocolConversionProtocol.SOAP_11, parsed);

        assertInstanceOf(Map.class, body);
        assertNull(nested((Map<String, Object>) body, "table", "row"));
    }

    @Test
    void shouldValidateProtocolConversionSoapTargetStatusFromBusinessBody() throws Exception {
        ProtocolConversionService service = protocolConversionService();
        ProtocolConversionServiceView view = new ProtocolConversionServiceView();
        view.setTargetProtocol(ProtocolConversionProtocol.SOAP_12);
        view.setResponseStatus(Map.of("path", "status", "code", "SUCCESS"));
        Class<?> targetResponseType = Class.forName(ProtocolConversionService.class.getName() + "$TargetResponse");
        Constructor<?> constructor = targetResponseType.getDeclaredConstructor(int.class, String.class, String.class);
        constructor.setAccessible(true);
        Object targetResponse = constructor.newInstance(200, "application/soap+xml;charset=UTF-8", """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
                  <soap:Body>
                    <submitProtocolConversionRowsResponse>
                      <status>SUCCESS</status>
                      <receivedCount>3</receivedCount>
                    </submitProtocolConversionRowsResponse>
                  </soap:Body>
                </soap:Envelope>
                """);
        Method validateTargetResponse = ProtocolConversionService.class.getDeclaredMethod(
                "validateTargetResponse", ProtocolConversionServiceView.class, targetResponseType);
        validateTargetResponse.setAccessible(true);

        validateTargetResponse.invoke(service, view, targetResponse);
    }

    @Test
    void shouldBridgeJsonBodyDirectlyIntoSoapOperationBody() throws Exception {
        ProtocolConversionService service = protocolConversionService();
        ProtocolConversionServiceView view = new ProtocolConversionServiceView();
        view.setConversionMode(ProtocolConversionMode.BODY_BRIDGE);
        view.setTargetProtocol(ProtocolConversionProtocol.SOAP_11);
        WebServiceConfig config = new WebServiceConfig();
        config.setNamespaceUri("urn:target");
        config.setOperationName("submitPayload");
        config.setRequestRootName("submitPayload");
        view.setTargetWebserviceConfig(config);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Alice");
        body.put("amount", "12.30");

        String targetBody = invokeBuildBodyBridgeTargetBody(service, view, body, "{\"customerName\":\"Alice\"}");

        assertTrue(targetBody.contains("<tns:submitPayload>"));
        assertTrue(targetBody.contains("<customerName>Alice</customerName>"));
        assertTrue(targetBody.contains("<amount>12.30</amount>"));
        assertTrue(!targetBody.contains("<payload>"));
        assertTrue(!targetBody.contains("<body>"));
    }

    @Test
    void shouldBridgeSoapEntityBodyDirectlyToJsonBody() throws Exception {
        ProtocolConversionService service = protocolConversionService();
        ProtocolConversionServiceView view = new ProtocolConversionServiceView();
        view.setConversionMode(ProtocolConversionMode.BODY_BRIDGE);
        view.setTargetProtocol(ProtocolConversionProtocol.HTTP_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runBatch", "20260616");
        body.put("pageSize", "50");

        String targetBody = invokeBuildBodyBridgeTargetBody(service, view, body, "<soap:Envelope/>");

        assertTrue(targetBody.contains("\"runBatch\":\"20260616\""));
        assertTrue(targetBody.contains("\"pageSize\":\"50\""));
        assertTrue(!targetBody.contains("Envelope"));
        assertTrue(!targetBody.contains("payload"));
    }

    @Test
    void shouldBridgeEmptyBodyToEmptySoapOperationBody() throws Exception {
        ProtocolConversionService service = protocolConversionService();
        ProtocolConversionServiceView view = new ProtocolConversionServiceView();
        view.setConversionMode(ProtocolConversionMode.BODY_BRIDGE);
        view.setTargetProtocol(ProtocolConversionProtocol.SOAP_11);
        WebServiceConfig config = new WebServiceConfig();
        config.setNamespaceUri("urn:target");
        config.setOperationName("submitPayload");
        config.setRequestRootName("submitPayload");
        view.setTargetWebserviceConfig(config);

        String targetBody = invokeBuildBodyBridgeTargetBody(service, view, new LinkedHashMap<>(), "");

        assertTrue(targetBody.contains("<tns:submitPayload>"));
        assertTrue(targetBody.contains("</tns:submitPayload>"));
        assertTrue(!targetBody.contains("Body Bridge request body is required"));
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

    private static DataServiceDefinitionView dataService(String serviceCode) {
        DataServiceDefinitionView service = new DataServiceDefinitionView();
        service.setServiceCode(serviceCode);
        service.setServiceName(serviceCode);
        return service;
    }

    private static DataServiceResponseParamView responseParam(String name, String exampleValue) {
        DataServiceResponseParamView param = new DataServiceResponseParamView();
        param.setEnabled(Boolean.TRUE);
        param.setParamName(name);
        param.setFieldName(name);
        param.setExampleValue(exampleValue);
        return param;
    }

    private static ProtocolConversionService protocolConversionService() {
        return new ProtocolConversionService(
                null, null, null, null, null, null, null, new ObjectMapper(), null);
    }

    private static ProtocolConversionService protocolConversionServiceWithInvocationLog() {
        return new ProtocolConversionService(
                null, null, null, null, null, null, null, new ObjectMapper(), invocationLogService());
    }

    private static OpenServiceInvocationLogService invocationLogService() {
        return new OpenServiceInvocationLogService(
                new StudioPlatformProperties(), null, null, null, null, null, null, new ObjectMapper());
    }

    private static String invokeBuildBodyBridgeTargetBody(ProtocolConversionService service,
                                                          ProtocolConversionServiceView view,
                                                          Map<String, Object> body,
                                                          String rawBody) throws Exception {
        Class<?> sourcePayloadType = Class.forName(ProtocolConversionService.class.getName() + "$SourcePayload");
        Constructor<?> constructor = sourcePayloadType.getDeclaredConstructor(Object.class, String.class);
        constructor.setAccessible(true);
        Object sourcePayload = constructor.newInstance(body, rawBody);
        Method method = ProtocolConversionService.class.getDeclaredMethod(
                "buildBodyBridgeTargetBody", ProtocolConversionServiceView.class, sourcePayloadType, String.class);
        method.setAccessible(true);
        return String.valueOf(method.invoke(service, view, sourcePayload, rawBody));
    }
}
