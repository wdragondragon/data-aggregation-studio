package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.request.RuntimeEndpointSaveRequest;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeEndpointEntity;
import com.jdragon.studio.infra.mapper.ProjectRuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RuntimeEndpointMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeEndpointSecretUpdateTest {

    @Test
    void shouldPreserveStoredUrlHeadersAndTokenWhenUpdateLeavesSecretsBlank() {
        Fixture fixture = fixture();
        RuntimeEndpointSaveRequest request = updateRequest();
        request.setEndpointUrl("   ");
        request.setHeaders(null);
        request.setToken(" ");
        request.setClearToken(false);

        fixture.service.saveEndpoint(request);

        ArgumentCaptor<RuntimeEndpointEntity> captor = ArgumentCaptor.forClass(RuntimeEndpointEntity.class);
        verify(fixture.endpointMapper).updateById(captor.capture());
        assertEquals("stored-url", captor.getValue().getEndpointCiphertext());
        assertEquals("stored-headers", captor.getValue().getHeadersCiphertext());
        assertEquals("stored-token", captor.getValue().getTokenCiphertext());
        verify(fixture.encryption, never()).encrypt(anyString());
    }

    @Test
    void shouldClearHeadersAndTokenOnlyWhenExplicitlyRequested() {
        Fixture fixture = fixture();
        when(fixture.encryption.encrypt("{}")).thenReturn("empty-headers");
        RuntimeEndpointSaveRequest request = updateRequest();
        request.setHeaders(new LinkedHashMap<String, String>());
        request.setClearToken(true);

        fixture.service.saveEndpoint(request);

        ArgumentCaptor<RuntimeEndpointEntity> captor = ArgumentCaptor.forClass(RuntimeEndpointEntity.class);
        verify(fixture.endpointMapper).updateById(captor.capture());
        assertEquals("stored-url", captor.getValue().getEndpointCiphertext());
        assertEquals("empty-headers", captor.getValue().getHeadersCiphertext());
        assertNull(captor.getValue().getTokenCiphertext());
        verify(fixture.encryption).encrypt("{}");
    }

    @Test
    void shouldRejectChangingStoredEndpointToLegacyLocalMode() {
        Fixture fixture = fixture();
        RuntimeEndpointSaveRequest request = updateRequest();
        request.setMode("LOCAL");

        StudioException exception = assertThrows(StudioException.class,
                () -> fixture.service.saveEndpoint(request));

        assertEquals("Only HTTP runtime endpoints are supported; migrate legacy LOCAL endpoints to a Worker HTTP/SLB endpoint",
                exception.getMessage());
        verify(fixture.endpointMapper, never()).updateById(any(RuntimeEndpointEntity.class));
    }

    private RuntimeEndpointSaveRequest updateRequest() {
        RuntimeEndpointSaveRequest request = new RuntimeEndpointSaveRequest();
        request.setId(101L);
        request.setRuntimeClusterId(46L);
        request.setMode("HTTP");
        request.setEnabled(true);
        return request;
    }

    private Fixture fixture() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        EncryptionService encryption = mock(EncryptionService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);

        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(46L);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C46");
        cluster.setEnabled(1);
        when(clusterMapper.selectOne(any())).thenReturn(cluster);

        RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
        endpoint.setId(101L);
        endpoint.setTenantId("tenant-a");
        endpoint.setRuntimeClusterId(46L);
        endpoint.setMode("HTTP");
        endpoint.setEndpointCiphertext("stored-url");
        endpoint.setHeadersCiphertext("stored-headers");
        endpoint.setTokenCiphertext("stored-token");
        endpoint.setEnabled(1);
        when(endpointMapper.selectOne(any())).thenReturn(endpoint);

        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper, authorizationMapper,
                security, encryption, new ObjectMapper(), mock(RuntimeClusterReferenceRepository.class),
                mock(ProjectMapper.class));
        return new Fixture(service, endpointMapper, encryption);
    }

    private static class Fixture {
        private final RuntimeClusterService service;
        private final RuntimeEndpointMapper endpointMapper;
        private final EncryptionService encryption;

        private Fixture(RuntimeClusterService service, RuntimeEndpointMapper endpointMapper,
                        EncryptionService encryption) {
            this.service = service;
            this.endpointMapper = endpointMapper;
            this.encryption = encryption;
        }
    }
}
