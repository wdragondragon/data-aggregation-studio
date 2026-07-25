package com.jdragon.studio.worker.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.WebServiceSoapVersion;
import com.jdragon.studio.dto.model.WebServiceDebugResult;
import com.jdragon.studio.dto.model.DataIngestionInvokeResult;
import com.jdragon.studio.dto.model.request.DataServiceDebugRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.DataIngestionService;
import com.jdragon.studio.infra.service.DataServiceService;
import com.jdragon.studio.infra.service.ProtocolConversionService;
import com.jdragon.studio.infra.service.RuntimeEndpointHeaderPolicy;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.worker.web.filter.InternalApiTokenFilter;
import com.jdragon.studio.worker.idempotency.RuntimeInvocationIdempotencyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalRuntimeInvocationControllerDebugTest {

    @AfterEach
    void clearContext() {
        StudioRequestContextHolder.clear();
    }

    @Test
    void shouldReplayCompletedIngestionWithoutRepeatingBusinessSideEffects() throws Exception {
        Fixture fixture = fixture();
        DataIngestionServiceEntity entity = new DataIngestionServiceEntity();
        entity.setId(32L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.ingestionMapper.selectOne(any())).thenReturn(entity);
        DataIngestionInvokeResult result = new DataIngestionInvokeResult();
        result.setRequestId("request-1");
        result.setServiceCode("orders");
        result.setStatus("SUCCESS");
        result.setReceivedCount(1L);
        result.setSuccessCount(1L);
        result.setFailedCount(0L);
        when(fixture.ingestionService.invoke(eq("orders"), eq("public"), isNull(), anyMap(),
                anyMap(), anyMap(), any(), eq("POST"), any(), isNull())).thenReturn(result);
        when(fixture.idempotencyService.begin(eq("tenant-a"), eq(101L), eq(50L),
                eq("DATA_INGESTION_SERVICE"), eq(32L), eq(hex('a')), eq(hex('b'))))
                .thenReturn(RuntimeInvocationIdempotencyService.BeginResult.execute(901L, "owner-1"));

        MockHttpServletResponse first = new MockHttpServletResponse();
        fixture.controller.invoke("data-ingestion-services", "orders", "public",
                idempotentRequest("REST", "application/json", "{}", hex('a'), hex('b')), first);

        when(fixture.idempotencyService.begin(eq("tenant-a"), eq(101L), eq(50L),
                eq("DATA_INGESTION_SERVICE"), eq(32L), eq(hex('a')), eq(hex('b'))))
                .thenReturn(RuntimeInvocationIdempotencyService.BeginResult.replay(
                        new RuntimeInvocationIdempotencyService.StoredResponse(
                                200, first.getContentType(), first.getContentAsByteArray())));
        MockHttpServletResponse replay = new MockHttpServletResponse();
        fixture.controller.invoke("data-ingestion-services", "orders", "public",
                idempotentRequest("REST", "application/json", "{}", hex('a'), hex('b')), replay);

        assertEquals(first.getContentAsString(), replay.getContentAsString());
        verify(fixture.ingestionService, times(1)).invoke(eq("orders"), eq("public"), isNull(),
                anyMap(), anyMap(), anyMap(), any(), eq("POST"), any(), isNull());
        verify(fixture.idempotencyService, times(1)).complete(eq(901L), eq("owner-1"),
                eq(200), any(), any(byte[].class));
    }

    @Test
    void shouldGuardAndReplaySoapProtocolConversionWrites() throws Exception {
        Fixture fixture = fixture();
        ProtocolConversionServiceEntity entity = new ProtocolConversionServiceEntity();
        entity.setId(33L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.conversionMapper.selectOne(any())).thenReturn(entity);
        when(fixture.conversionService.invokeWebService(eq("convert"), eq("public"), isNull(),
                anyMap(), any(), any(), isNull()))
                .thenReturn("<Envelope><accepted>true</accepted></Envelope>");
        when(fixture.idempotencyService.begin(eq("tenant-a"), eq(101L), eq(50L),
                eq("PROTOCOL_CONVERSION_SERVICE"), eq(33L), eq(hex('c')), eq(hex('d'))))
                .thenReturn(RuntimeInvocationIdempotencyService.BeginResult.execute(902L, "owner-2"));

        MockHttpServletResponse first = new MockHttpServletResponse();
        fixture.controller.invoke("protocol-conversions", "convert", "public",
                idempotentRequest("SOAP", "text/xml;charset=UTF-8", "<Envelope/>",
                        hex('c'), hex('d')), first);

        when(fixture.idempotencyService.begin(eq("tenant-a"), eq(101L), eq(50L),
                eq("PROTOCOL_CONVERSION_SERVICE"), eq(33L), eq(hex('c')), eq(hex('d'))))
                .thenReturn(RuntimeInvocationIdempotencyService.BeginResult.replay(
                        new RuntimeInvocationIdempotencyService.StoredResponse(
                                200, first.getContentType(), first.getContentAsByteArray())));
        MockHttpServletResponse replay = new MockHttpServletResponse();
        fixture.controller.invoke("protocol-conversions", "convert", "public",
                idempotentRequest("SOAP", "text/xml;charset=UTF-8", "<Envelope/>",
                        hex('c'), hex('d')), replay);

        assertEquals(first.getContentAsString(), replay.getContentAsString());
        verify(fixture.conversionService, times(1)).invokeWebService(eq("convert"), eq("public"),
                isNull(), anyMap(), any(), any(), isNull());
    }

    @Test
    void shouldPreserveSoap12FaultVersionAfterProtocolConversionConsumesRequestBody() throws Exception {
        Fixture fixture = fixture();
        ProtocolConversionServiceEntity entity = new ProtocolConversionServiceEntity();
        entity.setId(33L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.conversionMapper.selectOne(any())).thenReturn(entity);
        when(fixture.conversionService.invokeWebService(eq("convert"), eq("public"), isNull(),
                anyMap(), any(), any(), isNull()))
                .thenThrow(new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid SOAP payload"));
        when(fixture.conversionService.webServiceFault(eq(WebServiceSoapVersion.SOAP_12),
                eq(StudioErrorCode.BAD_REQUEST), eq("Invalid SOAP payload")))
                .thenReturn("<soap12-fault/>");
        String envelope = "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\"/>";
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/runtime/protocol-conversions/convert/public");
        request.addHeader("X-Studio-Internal-Token", "internal-token");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        request.addHeader("X-Studio-Invocation-Variant", "SOAP");
        request.setContentType("application/soap+xml;charset=UTF-8");
        request.setContent(envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.invoke("protocol-conversions", "convert", "public", request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/soap+xml"));
        assertEquals("<soap12-fault/>", response.getContentAsString());
        verify(fixture.conversionService).webServiceFault(eq(WebServiceSoapVersion.SOAP_12),
                eq(StudioErrorCode.BAD_REQUEST), eq("Invalid SOAP payload"));
    }

    @Test
    void shouldRejectUnguardedWorkerWriteInRequiredMode() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.getRuntimeInvocationIdempotency().setMode(
                StudioPlatformProperties.RuntimeInvocationIdempotencyMode.REQUIRED_WRITE);
        DataIngestionServiceEntity entity = new DataIngestionServiceEntity();
        entity.setId(32L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.ingestionMapper.selectOne(any())).thenReturn(entity);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/runtime/data-ingestion-services/orders/public");
        request.addHeader("X-Studio-Internal-Token", "internal-token");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        request.addHeader("X-Studio-Invocation-Variant", "REST");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.invoke("data-ingestion-services", "orders", "public", request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("IDEMPOTENCY_KEY_REQUIRED"));
        verify(fixture.ingestionService, never()).invoke(any(), any(), any(), anyMap(),
                anyMap(), anyMap(), any(), any(), any(), any());
    }

    @Test
    void shouldPreserveAuthenticatedRuntimeMarkerWhenReplayingBusiness401() throws Exception {
        Fixture fixture = fixture();
        DataIngestionServiceEntity entity = new DataIngestionServiceEntity();
        entity.setId(32L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.ingestionMapper.selectOne(any())).thenReturn(entity);
        byte[] failure = fixture.objectMapper.writeValueAsBytes(
                com.jdragon.studio.dto.common.Result.error(
                        StudioErrorCode.UNAUTHORIZED, "Business token rejected"));
        when(fixture.idempotencyService.begin(eq("tenant-a"), eq(101L), eq(50L),
                eq("DATA_INGESTION_SERVICE"), eq(32L), eq(hex('8')), eq(hex('9'))))
                .thenReturn(RuntimeInvocationIdempotencyService.BeginResult.replay(
                        new RuntimeInvocationIdempotencyService.StoredResponse(
                                401, "application/json;charset=UTF-8", failure)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.invoke("data-ingestion-services", "orders", "public",
                idempotentRequest("REST", "application/json", "{}", hex('8'), hex('9')),
                response);

        assertEquals(401, response.getStatus());
        assertEquals(RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED,
                response.getHeader(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER));
        assertTrue(response.getContentAsString().contains("Business token rejected"));
        verify(fixture.ingestionService, never()).invoke(any(), any(), any(), anyMap(),
                anyMap(), anyMap(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExcludeTransportHopByHopHeadersFromBusinessHeaders() {
        Fixture fixture = fixture();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", "worker.internal:18081");
        request.addHeader("Content-Length", "128");
        request.addHeader("Connection", "keep-alive, X-Internal-Hop");
        request.addHeader("Transfer-Encoding", "chunked");
        request.addHeader("Proxy-Connection", "keep-alive");
        request.addHeader("X-Internal-Hop", "transport-only");
        request.addHeader("X-Studio-Trace", "injected-by-intermediary");
        request.addHeader(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER, "spoofed");
        request.addHeader("Authorization", "Bearer business-secret");
        request.addHeader("X-Business-Trace", "trace-1");

        Map<String, Object> headers = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                fixture.controller, "headers", request);

        assertEquals("trace-1", headers.get("X-Business-Trace"));
        assertFalse(headers.containsKey("Host"));
        assertFalse(headers.containsKey("Content-Length"));
        assertFalse(headers.containsKey("Connection"));
        assertFalse(headers.containsKey("Transfer-Encoding"));
        assertFalse(headers.containsKey("Proxy-Connection"));
        assertFalse(headers.containsKey("X-Internal-Hop"));
        assertFalse(headers.containsKey("X-Studio-Trace"));
        assertFalse(headers.containsKey(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER));
        assertEquals("Bearer business-secret", headers.get("Authorization"));
    }

    @Test
    void shouldExecuteRestDebugWithResourceContextAndIsolatedTransportHeaders() throws Exception {
        Fixture fixture = fixture();
        DataServiceDefinitionEntity entity = new DataServiceDefinitionEntity();
        entity.setId(31L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.dataServiceMapper.selectById(31L)).thenReturn(entity);
        when(fixture.dataServiceService.debug(eq(31L), any(DataServiceDebugRequest.class))).thenAnswer(invocation -> {
            StudioRequestContext context = StudioRequestContextHolder.getContext();
            assertEquals("tenant-a", context.getTenantId());
            assertEquals(101L, context.getProjectId());
            DataServiceDebugRequest debugRequest = invocation.getArgument(1);
            assertEquals("business-secret", debugRequest.getHeaders().get("Authorization"));
            return Collections.<String, Object>singletonMap("accepted", Boolean.TRUE);
        });
        MockHttpServletRequest request = request("REST");
        request.addHeader("Authorization", "Bearer transport-secret");
        JsonNode payload = fixture.objectMapper.readTree("{\"headers\":{\"Authorization\":\"business-secret\"}}");
        request.setContent(fixture.objectMapper.writeValueAsBytes(payload));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.debug("data-services", 31L, request, response);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("accepted"));
        assertNull(StudioRequestContextHolder.getContext());
    }

    @Test
    void shouldSelectSoapDebugOnTargetWorker() throws Exception {
        Fixture fixture = fixture();
        DataIngestionServiceEntity entity = new DataIngestionServiceEntity();
        entity.setId(41L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.ingestionMapper.selectById(41L)).thenReturn(entity);
        WebServiceDebugResult debugResult = new WebServiceDebugResult();
        debugResult.setSuccess(Boolean.TRUE);
        debugResult.setHttpStatus(200);
        when(fixture.ingestionService.debugWebService(eq(41L), any())).thenReturn(debugResult);
        MockHttpServletRequest request = request("SOAP");
        request.setContent(fixture.objectMapper.writeValueAsBytes(
                fixture.objectMapper.readTree("{\"soapEnvelope\":\"<Envelope/>\"}")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.debug("data-ingestion-services", 41L, request, response);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("httpStatus"));
        verify(fixture.ingestionService).debugWebService(eq(41L), any());
        verify(fixture.ingestionService, never()).debug(eq(41L), any());
    }

    @Test
    void shouldExcludeManagedTransportHeadersFromBusinessInvocationHeaders() throws Exception {
        Fixture fixture = fixture();
        DataServiceDefinitionEntity entity = new DataServiceDefinitionEntity();
        entity.setId(31L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.dataServiceMapper.selectOne(any())).thenReturn(entity);
        when(fixture.dataServiceService.invoke(eq("orders"), eq("public"), isNull(), anyMap(), anyMap(),
                anyMap(), eq("POST"), any(), isNull())).thenAnswer(invocation -> {
            StudioRequestContext context = StudioRequestContextHolder.getContext();
            assertEquals("tenant-a", context.getTenantId());
            assertEquals(101L, context.getProjectId());
            return Collections.<String, Object>emptyMap();
        });
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/runtime/data-services/orders/public");
        request.addHeader("X-Studio-Internal-Token", "internal-token");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        request.addHeader("X-Studio-Invocation-Variant", "REST");
        request.addHeader("X-Studio-Transport-Authorization", "true");
        request.addHeader("X-Studio-Transport-Header-Names", "Cookie,X-SLB-Access-Token");
        request.addHeader("Authorization", "Bearer transport-secret");
        request.addHeader("Cookie", "session=transport-secret");
        request.addHeader("X-SLB-Access-Token", "transport-secret");
        request.addHeader("X-Business-Trace", "trace-1");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        StudioRequestContext previous = new StudioRequestContext();
        previous.setTenantId("control-tenant");
        previous.setProjectId(999L);
        StudioRequestContextHolder.setContext(previous);

        fixture.controller.invoke("data-services", "orders", "public", request, response);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> headers = ArgumentCaptor.forClass(Map.class);
        verify(fixture.dataServiceService).invoke(eq("orders"), eq("public"), isNull(), headers.capture(),
                anyMap(), anyMap(), eq("POST"), any(), isNull());
        assertEquals("trace-1", headers.getValue().get("X-Business-Trace"));
        assertFalse(headers.getValue().containsKey("Cookie"));
        assertFalse(headers.getValue().containsKey("X-SLB-Access-Token"));
        assertFalse(headers.getValue().containsKey("Authorization"));
        assertFalse(headers.getValue().containsKey("X-Studio-Transport-Authorization"));
        assertFalse(headers.getValue().containsKey("X-Studio-Transport-Header-Names"));
        assertEquals("control-tenant", StudioRequestContextHolder.getContext().getTenantId());
        assertEquals(999L, StudioRequestContextHolder.getContext().getProjectId());
    }

    @Test
    void shouldRejectInvocationWhenResourceTenantDoesNotMatchTargetCluster() throws Exception {
        Fixture fixture = fixture();
        DataServiceDefinitionEntity entity = new DataServiceDefinitionEntity();
        entity.setId(31L);
        entity.setTenantId("tenant-b");
        entity.setProjectId(202L);
        entity.setRuntimeClusterId(50L);
        when(fixture.dataServiceMapper.selectOne(any())).thenReturn(entity);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/runtime/data-services/orders/public");
        request.addHeader("X-Studio-Internal-Token", "internal-token");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        request.addHeader("X-Studio-Invocation-Variant", "REST");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.invoke("data-services", "orders", "public", request, response);

        assertEquals(503, response.getStatus());
        assertNull(StudioRequestContextHolder.getContext());
        verify(fixture.dataServiceService, never()).invoke(any(), any(), any(), anyMap(), anyMap(),
                anyMap(), any(), any(), any());
    }

    @Test
    void shouldRejectInvocationWhenProjectClusterAuthorizationWasRevoked() throws Exception {
        Fixture fixture = fixture();
        DataServiceDefinitionEntity entity = new DataServiceDefinitionEntity();
        entity.setId(31L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.dataServiceMapper.selectOne(any())).thenReturn(entity);
        when(fixture.authorizationService.isRuntimeClusterAuthorizedForProject(
                "tenant-a", 101L, 50L)).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/runtime/data-services/orders/public");
        request.addHeader("X-Studio-Internal-Token", "internal-token");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.invoke("data-services", "orders", "public", request, response);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("not authorized"));
        verify(fixture.dataServiceService, never()).invoke(any(), any(), any(), anyMap(), anyMap(),
                anyMap(), any(), any(), any());
    }

    @Test
    void shouldMarkInternalAuthenticationFailureForInvokeAndDebug() throws Exception {
        Fixture fixture = fixture();
        MockHttpServletRequest invokeRequest = new MockHttpServletRequest(
                "POST", "/internal/runtime/data-services/orders/public");
        invokeRequest.addHeader("X-Studio-Internal-Token", "wrong-token");
        MockHttpServletResponse invokeResponse = new MockHttpServletResponse();

        fixture.controller.invoke("data-services", "orders", "public", invokeRequest, invokeResponse);

        assertEquals(401, invokeResponse.getStatus());
        assertEquals(RuntimeInternalHeaders.INTERNAL_AUTHENTICATION,
                invokeResponse.getHeader(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER));

        MockHttpServletRequest debugRequest = new MockHttpServletRequest(
                "POST", "/internal/runtime/debug/data-services/31");
        debugRequest.addHeader("X-Studio-Internal-Token", "wrong-token");
        MockHttpServletResponse debugResponse = new MockHttpServletResponse();

        fixture.controller.debug("data-services", 31L, debugRequest, debugResponse);

        assertEquals(401, debugResponse.getStatus());
        assertEquals(RuntimeInternalHeaders.INTERNAL_AUTHENTICATION,
                debugResponse.getHeader(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER));
        verify(fixture.dataServiceService, never()).debug(any(), any());
    }

    @Test
    void shouldNotMarkBusinessAuthenticationFailureAsInternal() throws Exception {
        Fixture fixture = fixture();
        DataServiceDefinitionEntity entity = new DataServiceDefinitionEntity();
        entity.setId(31L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.dataServiceMapper.selectOne(any())).thenReturn(entity);
        when(fixture.dataServiceService.invoke(eq("orders"), eq("public"), isNull(), anyMap(), anyMap(),
                anyMap(), eq("POST"), any(), isNull()))
                .thenThrow(new StudioException(StudioErrorCode.UNAUTHORIZED, "Business token is invalid"));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/runtime/data-services/orders/public");
        request.addHeader("X-Studio-Internal-Token", "internal-token");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        request.addHeader("X-Studio-Invocation-Variant", "REST");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.invoke("data-services", "orders", "public", request, response);

        assertEquals(401, response.getStatus());
        assertNull(response.getHeader(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER));
        assertTrue(response.getContentAsString().contains("Business token is invalid"));
    }

    @Test
    void shouldBindInternalRuntimePathVariablesWithoutCompilerParameterMetadata() throws Exception {
        Fixture fixture = fixture();
        DataServiceDefinitionEntity entity = new DataServiceDefinitionEntity();
        entity.setId(31L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.dataServiceMapper.selectById(31L)).thenReturn(entity);
        when(fixture.dataServiceService.debug(eq(31L), any(DataServiceDebugRequest.class)))
                .thenReturn(Collections.<String, Object>emptyMap());
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("internal-token");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(fixture.controller)
                .addFilters(new InternalApiTokenFilter(fixture.objectMapper, properties))
                .build();

        mockMvc.perform(post("/internal/runtime/debug/data-services/31")
                        .header("X-Studio-Internal-Token", "internal-token")
                        .header("X-Studio-Target-Cluster-Id", "50")
                        .header("X-Studio-Invocation-Variant", "REST")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                        RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED));

        mockMvc.perform(post("/internal/runtime/debug/data-services/31")
                        .header("X-Studio-Internal-Token", "wrong-token")
                        .header("X-Studio-Target-Cluster-Id", "50")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER,
                        RuntimeInternalHeaders.INTERNAL_AUTHENTICATION));

        verify(fixture.dataServiceService).debug(eq(31L), any(DataServiceDebugRequest.class));
    }

    @Test
    void shouldRejectOversizedInvocationBeforeIdempotencyOrBusinessExecution() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.setRuntimeInvocationMaxBodyBytes(1024);
        DataIngestionServiceEntity entity = new DataIngestionServiceEntity();
        entity.setId(32L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.ingestionMapper.selectOne(any())).thenReturn(entity);
        MockHttpServletRequest request = idempotentRequest("REST", "application/json",
                "x".repeat(1025), hex('a'), hex('b'));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.invoke("data-ingestion-services", "orders", "public", request, response);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("PAYLOAD_TOO_LARGE"));
        verify(fixture.idempotencyService, never()).begin(any(), any(), any(), any(), any(), any(), any());
        verify(fixture.ingestionService, never()).invoke(any(), any(), any(), anyMap(),
                anyMap(), anyMap(), any(), any(), any(), any());
    }

    @Test
    void shouldRejectOversizedDebugBodyInsideWorker() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.setRuntimeInvocationMaxBodyBytes(1024);
        DataServiceDefinitionEntity entity = new DataServiceDefinitionEntity();
        entity.setId(31L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.dataServiceMapper.selectById(31L)).thenReturn(entity);
        MockHttpServletRequest request = request("REST");
        request.setContent(("{\"value\":\"" + "x".repeat(1025) + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.debug("data-services", 31L, request, response);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("PAYLOAD_TOO_LARGE"));
        verify(fixture.dataServiceService, never()).debug(any(), any());
    }

    @Test
    void shouldRejectChunkedOversizedInvocationWhileReading() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.setRuntimeInvocationMaxBodyBytes(1024);
        DataServiceDefinitionEntity entity = new DataServiceDefinitionEntity();
        entity.setId(31L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.dataServiceMapper.selectOne(any())).thenReturn(entity);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/runtime/data-services/orders/public") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.addHeader("X-Studio-Internal-Token", "internal-token");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        request.addHeader("X-Studio-Invocation-Variant", "REST");
        request.setContentType("application/json");
        request.setContent(("{\"value\":\"" + "x".repeat(1025) + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.invoke("data-services", "orders", "public", request, response);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("PAYLOAD_TOO_LARGE"));
        verify(fixture.dataServiceService, never()).invoke(any(), any(), any(), anyMap(),
                anyMap(), anyMap(), any(), any(), any());
    }

    @Test
    void shouldRejectChunkedOversizedWriteBeforeIdempotencyOrBusinessExecution() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.setRuntimeInvocationMaxBodyBytes(1024);
        DataIngestionServiceEntity entity = new DataIngestionServiceEntity();
        entity.setId(32L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.ingestionMapper.selectOne(any())).thenReturn(entity);
        MockHttpServletRequest request = chunkedRequest("application/x-www-form-urlencoded",
                "value=" + "x".repeat(1025));
        request.addHeader(RuntimeInternalHeaders.IDEMPOTENCY_KEY_HASH_HEADER, hex('a'));
        request.addHeader(RuntimeInternalHeaders.IDEMPOTENCY_FINGERPRINT_HEADER, hex('b'));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.invoke("data-ingestion-services", "orders", "public", request, response);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("PAYLOAD_TOO_LARGE"));
        verify(fixture.idempotencyService, never()).begin(any(), any(), any(), any(), any(), any(), any());
        verify(fixture.ingestionService, never()).invoke(any(), any(), any(), anyMap(),
                anyMap(), anyMap(), any(), any(), any(), any());
    }

    @Test
    void shouldParseChunkedFormBodyAfterBoundedCaching() throws Exception {
        Fixture fixture = fixture();
        DataIngestionServiceEntity entity = new DataIngestionServiceEntity();
        entity.setId(32L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(101L);
        entity.setRuntimeClusterId(50L);
        when(fixture.ingestionMapper.selectOne(any())).thenReturn(entity);
        when(fixture.idempotencyService.begin(eq("tenant-a"), eq(101L), eq(50L),
                eq("DATA_INGESTION_SERVICE"), eq(32L), eq(hex('a')), eq(hex('b'))))
                .thenReturn(RuntimeInvocationIdempotencyService.BeginResult.execute(902L, "owner-2"));
        DataIngestionInvokeResult result = new DataIngestionInvokeResult();
        result.setStatus("SUCCESS");
        when(fixture.ingestionService.invoke(eq("orders"), eq("public"), isNull(), anyMap(),
                anyMap(), anyMap(), isNull(), eq("POST"), any(), isNull())).thenReturn(result);
        MockHttpServletRequest request = chunkedRequest("application/x-www-form-urlencoded",
                "name=hello+world&name=last&city=%E4%B8%8A%E6%B5%B7");
        request.setQueryString("source=manual&source=timer");
        request.addHeader(RuntimeInternalHeaders.IDEMPOTENCY_KEY_HASH_HEADER, hex('a'));
        request.addHeader(RuntimeInternalHeaders.IDEMPOTENCY_FINGERPRINT_HEADER, hex('b'));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.controller.invoke("data-ingestion-services", "orders", "public", request, response);

        assertEquals(200, response.getStatus());
        ArgumentCaptor<Map<String, Object>> queryCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> formCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.ingestionService).invoke(eq("orders"), eq("public"), isNull(), anyMap(),
                queryCaptor.capture(), formCaptor.capture(), isNull(), eq("POST"), any(), isNull());
        assertEquals("timer", queryCaptor.getValue().get("source"));
        assertEquals("上海", formCaptor.getValue().get("city"));
        assertEquals("hello world", ((String[]) formCaptor.getValue().get("name"))[0]);
        assertEquals("last", ((String[]) formCaptor.getValue().get("name"))[1]);
    }

    private MockHttpServletRequest chunkedRequest(String contentType, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/runtime/data-ingestion-services/orders/public") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.addHeader("X-Studio-Internal-Token", "internal-token");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        request.addHeader("X-Studio-Invocation-Variant", "REST");
        request.setContentType(contentType);
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    private MockHttpServletRequest request(String variant) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/runtime/debug");
        request.addHeader("X-Studio-Internal-Token", "internal-token");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        request.addHeader("X-Studio-Invocation-Variant", variant);
        request.setContentType("application/json");
        return request;
    }

    private MockHttpServletRequest idempotentRequest(String variant, String contentType, String body,
                                                     String keyHash, String fingerprint) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/runtime/invoke");
        request.addHeader("X-Studio-Internal-Token", "internal-token");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        request.addHeader("X-Studio-Invocation-Variant", variant);
        request.addHeader(RuntimeInternalHeaders.IDEMPOTENCY_KEY_HASH_HEADER, keyHash);
        request.addHeader(RuntimeInternalHeaders.IDEMPOTENCY_FINGERPRINT_HEADER, fingerprint);
        request.setContentType(contentType);
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    private static String hex(char value) {
        return String.valueOf(value).repeat(64);
    }

    private Fixture fixture() {
        DataServiceService dataServiceService = mock(DataServiceService.class);
        DataIngestionService ingestionService = mock(DataIngestionService.class);
        ProtocolConversionService conversionService = mock(ProtocolConversionService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("internal-token");
        properties.setRuntimeClusterCode("C50");
        InternalRuntimeInvocationController controller = new InternalRuntimeInvocationController(
                dataServiceService, ingestionService, conversionService, objectMapper, properties,
                new RuntimeEndpointHeaderPolicy());
        WorkerAuthorizationService authorizationService = mock(WorkerAuthorizationService.class);
        when(authorizationService.isRuntimeClusterAuthorizedForProject(
                "tenant-a", 101L, 50L)).thenReturn(true);
        controller.setWorkerAuthorizationService(authorizationService);
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(50L);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C50");
        cluster.setEnabled(1);
        when(clusterMapper.selectById(50L)).thenReturn(cluster);
        DataServiceDefinitionMapper dataServiceMapper = mock(DataServiceDefinitionMapper.class);
        DataIngestionServiceMapper ingestionMapper = mock(DataIngestionServiceMapper.class);
        ProtocolConversionServiceMapper conversionMapper = mock(ProtocolConversionServiceMapper.class);
        controller.setRuntimeIdentityMappers(clusterMapper, dataServiceMapper, ingestionMapper, conversionMapper);
        RuntimeInvocationIdempotencyService idempotencyService =
                mock(RuntimeInvocationIdempotencyService.class);
        controller.setIdempotencyService(idempotencyService);
        return new Fixture(controller, objectMapper, dataServiceService, ingestionService,
                conversionService, dataServiceMapper, ingestionMapper, conversionMapper,
                idempotencyService, authorizationService, properties);
    }

    private static final class Fixture {
        private final InternalRuntimeInvocationController controller;
        private final ObjectMapper objectMapper;
        private final DataServiceService dataServiceService;
        private final DataIngestionService ingestionService;
        private final ProtocolConversionService conversionService;
        private final DataServiceDefinitionMapper dataServiceMapper;
        private final DataIngestionServiceMapper ingestionMapper;
        private final ProtocolConversionServiceMapper conversionMapper;
        private final RuntimeInvocationIdempotencyService idempotencyService;
        private final WorkerAuthorizationService authorizationService;
        private final StudioPlatformProperties properties;

        private Fixture(InternalRuntimeInvocationController controller, ObjectMapper objectMapper,
                        DataServiceService dataServiceService, DataIngestionService ingestionService,
                        ProtocolConversionService conversionService,
                        DataServiceDefinitionMapper dataServiceMapper,
                        DataIngestionServiceMapper ingestionMapper,
                        ProtocolConversionServiceMapper conversionMapper,
                        RuntimeInvocationIdempotencyService idempotencyService,
                        WorkerAuthorizationService authorizationService,
                        StudioPlatformProperties properties) {
            this.controller = controller;
            this.objectMapper = objectMapper;
            this.dataServiceService = dataServiceService;
            this.ingestionService = ingestionService;
            this.conversionService = conversionService;
            this.dataServiceMapper = dataServiceMapper;
            this.ingestionMapper = ingestionMapper;
            this.conversionMapper = conversionMapper;
            this.idempotencyService = idempotencyService;
            this.authorizationService = authorizationService;
            this.properties = properties;
        }
    }
}
