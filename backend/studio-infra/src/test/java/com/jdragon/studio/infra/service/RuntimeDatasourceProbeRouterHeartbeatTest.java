package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeEndpointEntity;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RuntimeEndpointMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeDatasourceProbeRouterHeartbeatTest {

    @Test
    void shouldTreatClusterWithOnlyExpiredInstancesAsUnavailableForProbe() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);

        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(46L);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C46");
        cluster.setEnabled(1);
        when(clusterMapper.selectOne(any())).thenReturn(cluster);

        RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
        endpoint.setRuntimeClusterId(46L);
        endpoint.setMode("HTTP");
        endpoint.setEnabled(1);
        when(endpointMapper.selectOne(any())).thenReturn(endpoint);
        when(runtimeClusterService.hasOnlineInstance(cluster)).thenReturn(false);

        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeClusterCode("OMS");
        RuntimeDatasourceProbeRouter router = router(clusterMapper, endpointMapper, properties, runtimeClusterService);
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setTenantId("tenant-a");

        ConnectionTestResult result = router.test(datasource, 46L);

        assertFalse(result.isSuccess());
        assertEquals(DataSourceConnectionStatus.UNKNOWN, result.getStatus());
        assertEquals("Target runtime cluster has no online endpoint", result.getMessage());
        verify(runtimeClusterService).hasOnlineInstance(cluster);
    }

    @Test
    void shouldNotUseLocalExecutionWhenSelectedClusterCodeMatchesServerCode() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);

        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(46L);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C46");
        cluster.setEnabled(1);
        when(clusterMapper.selectOne(any())).thenReturn(cluster);
        when(endpointMapper.selectOne(any())).thenReturn(null);

        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeClusterCode("C46");
        RuntimeDatasourceProbeRouter router = router(clusterMapper, endpointMapper, properties, runtimeClusterService);
        DataSourceDefinition datasource = datasource();

        ConnectionTestResult result = router.test(datasource, 46L);
        StudioException discoverError = assertThrows(StudioException.class,
                () -> router.discover(datasource, 46L, null, 1, 20));

        assertFalse(result.isSuccess());
        assertEquals(DataSourceConnectionStatus.UNKNOWN, result.getStatus());
        assertEquals("Target runtime cluster has no online endpoint", result.getMessage());
        assertEquals(StudioErrorCode.SERVICE_UNAVAILABLE, discoverError.getCode());
        assertEquals("Target runtime cluster is unavailable", discoverError.getMessage());
    }

    @Test
    void shouldRejectNullRuntimeCluster() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);

        StudioPlatformProperties properties = new StudioPlatformProperties();
        RuntimeDatasourceProbeRouter router = router(clusterMapper, endpointMapper, properties, runtimeClusterService);
        DataSourceDefinition datasource = datasource();

        StudioException error = assertThrows(StudioException.class, () -> router.test(datasource, null));

        assertEquals("Runtime cluster is required", error.getMessage());
        verify(clusterMapper, never()).selectOne(any());
        verify(endpointMapper, never()).selectOne(any());
    }

    private RuntimeDatasourceProbeRouter router(RuntimeClusterMapper clusterMapper,
                                                RuntimeEndpointMapper endpointMapper,
                                                StudioPlatformProperties properties,
                                                RuntimeClusterService runtimeClusterService) {
        return new RuntimeDatasourceProbeRouter(clusterMapper, endpointMapper,
                mock(EncryptionService.class), new ObjectMapper(), properties,
                runtimeClusterService,
                new RuntimeEndpointSecurityService(properties), new RuntimeEndpointHeaderPolicy(),
                new RuntimeEndpointHttpClient());
    }

    private DataSourceDefinition datasource() {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(301L);
        datasource.setTenantId("tenant-a");
        datasource.setProjectId(10L);
        return datasource;
    }
}
