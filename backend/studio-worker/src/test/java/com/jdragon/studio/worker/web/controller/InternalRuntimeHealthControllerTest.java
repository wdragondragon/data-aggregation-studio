package com.jdragon.studio.worker.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.service.DataIngestionService;
import com.jdragon.studio.infra.service.DataServiceService;
import com.jdragon.studio.infra.service.ProtocolConversionService;
import com.jdragon.studio.infra.service.RuntimeEndpointHeaderPolicy;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalRuntimeHealthControllerTest {

    @Test
    void shouldRequireTokenAndMatchingTargetClusterIdentity() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("internal-secret");
        properties.setRuntimeClusterCode("50");
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(50L);
        cluster.setCode("50");
        cluster.setEnabled(1);
        when(clusterMapper.selectById(50L)).thenReturn(cluster);
        InternalRuntimeInvocationController controller = new InternalRuntimeInvocationController(
                mock(DataServiceService.class), mock(DataIngestionService.class),
                mock(ProtocolConversionService.class), objectMapper(), properties,
                new RuntimeEndpointHeaderPolicy());
        controller.setRuntimeIdentityMappers(clusterMapper, null, null, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/runtime/health");
        request.addHeader("X-Studio-Internal-Token", "internal-secret");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.health(request, response);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"data\":\"OK\""));
    }

    @Test
    void shouldRejectMismatchedTargetClusterIdentity() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("internal-secret");
        properties.setRuntimeClusterCode("OMS");
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(50L);
        cluster.setCode("50");
        cluster.setEnabled(1);
        when(clusterMapper.selectById(50L)).thenReturn(cluster);
        InternalRuntimeInvocationController controller = new InternalRuntimeInvocationController(
                mock(DataServiceService.class), mock(DataIngestionService.class),
                mock(ProtocolConversionService.class), objectMapper(), properties,
                new RuntimeEndpointHeaderPolicy());
        controller.setRuntimeIdentityMappers(clusterMapper, null, null, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/runtime/health");
        request.addHeader("X-Studio-Internal-Token", "internal-secret");
        request.addHeader("X-Studio-Target-Cluster-Id", "50");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.health(request, response);

        assertEquals(503, response.getStatus());
    }

    @Test
    void shouldMarkInternalAuthenticationFailure() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("internal-secret");
        InternalRuntimeInvocationController controller = new InternalRuntimeInvocationController(
                mock(DataServiceService.class), mock(DataIngestionService.class),
                mock(ProtocolConversionService.class), objectMapper(), properties,
                new RuntimeEndpointHeaderPolicy());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/runtime/health");
        request.addHeader("X-Studio-Internal-Token", "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.health(request, response);

        assertEquals(401, response.getStatus());
        assertEquals(RuntimeInternalHeaders.INTERNAL_AUTHENTICATION,
                response.getHeader(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER));
        assertTrue(response.getContentAsString().contains("Internal runtime authentication failed"));
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
