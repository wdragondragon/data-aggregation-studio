package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jdragon.studio.infra.config.JacksonConfig;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.RuntimeClusterInstanceView;
import com.jdragon.studio.dto.model.RuntimeClusterView;
import com.jdragon.studio.dto.model.RuntimeEndpointView;
import com.jdragon.studio.dto.model.request.RuntimeClusterSaveRequest;
import com.jdragon.studio.dto.model.request.RuntimeEndpointSaveRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectRuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeEndpointEntity;
import com.jdragon.studio.infra.mapper.ProjectRuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RuntimeEndpointMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeClusterServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        if (TableInfoHelper.getTableInfo(RuntimeClusterEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), RuntimeClusterEntity.class);
        }
    }

    @Test
    void shouldAcceptNumericRuntimeClusterCode() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                mock(ProjectRuntimeClusterMapper.class), security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));
        RuntimeClusterSaveRequest request = new RuntimeClusterSaveRequest();
        request.setCode("46");
        request.setName("46 集群");

        RuntimeClusterView saved = service.save(request);

        assertEquals("46", saved.getCode());
        verify(clusterMapper).insert(any(RuntimeClusterEntity.class));
    }

    @Test
    void shouldReturnOnlyEnabledClustersAuthorizedForProject() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.currentProjectId()).thenReturn(101L);

        RuntimeClusterEntity authorized = cluster(10L, "OMS", "OMS", 1);
        authorized.getInstancesJson().put("worker-1", Map.of(
                "instanceId", "worker-1", "summary", "http://internal-worker",
                "heartbeatAt", LocalDateTime.now().toString()));
        RuntimeClusterEntity ungranted = cluster(20L, "C50", "50 集群", 1);
        when(clusterMapper.selectList(any())).thenReturn(List.of(authorized, ungranted));
        ProjectRuntimeClusterEntity grant = new ProjectRuntimeClusterEntity();
        grant.setRuntimeClusterId(10L);
        grant.setEnabled(1);
        grant.setPreferred(1);
        grant.setAllowManualOverride(1);
        when(authorizationMapper.selectList(any())).thenReturn(List.of(grant));

        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper, authorizationMapper,
                security, mock(EncryptionService.class), objectMapper(), mock(RuntimeClusterReferenceRepository.class),
                projectMapper(101L, "tenant-a"));

        List<RuntimeClusterView> options = service.options(101L);

        assertEquals(1, options.size());
        assertEquals(10L, options.get(0).getId());
        assertEquals("OMS", options.get(0).getCode());
        assertTrue(options.get(0).isPreferred());
        assertTrue(options.get(0).isAllowManualOverride());
        assertTrue(options.get(0).getInstances().isEmpty());
    }

    @Test
    void shouldNotRecreateDefaultClusterWhenConfiguredClustersAreDisabled() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.currentProjectId()).thenReturn(101L);
        when(clusterMapper.selectList(any())).thenReturn(List.of());
        when(clusterMapper.selectPhysicalCountByTenant("tenant-a")).thenReturn(1L);
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                mock(ProjectRuntimeClusterMapper.class), security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), projectMapper(101L, "tenant-a"));
        service.setStudioPlatformProperties(singleClusterCompatibilityProperties());

        List<RuntimeClusterView> options = service.options(null);

        assertTrue(options.isEmpty());
        verify(clusterMapper, never()).insert(any(RuntimeClusterEntity.class));
    }

    @Test
    void shouldReportReservedCodeInsteadOfLeakingDatabaseDuplicateError() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        when(clusterMapper.selectPhysicalCountByTenantAndCode("tenant-a", "C50")).thenReturn(1L);
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                mock(ProjectRuntimeClusterMapper.class), security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));
        RuntimeClusterSaveRequest request = new RuntimeClusterSaveRequest();
        request.setCode("c50");
        request.setName("50 集群");

        StudioException exception = assertThrows(StudioException.class, () -> service.save(request));

        assertEquals("Runtime cluster code already exists", exception.getMessage());
        verify(clusterMapper, never()).insert(any(RuntimeClusterEntity.class));
    }

    @Test
    void shouldRequireProjectContextForRuntimeClusterOptions() {
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.currentProjectId()).thenReturn(null);
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                mock(ProjectRuntimeClusterMapper.class), security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));

        StudioException exception = assertThrows(StudioException.class, () -> service.options(null));

        assertEquals("Project id is required", exception.getMessage());
        verify(clusterMapper, never()).selectList(any());
    }

    @Test
    void shouldRejectClusterManagementReadsForProjectMember() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(false);
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                mock(ProjectRuntimeClusterMapper.class), security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));

        StudioException listException = assertThrows(StudioException.class, service::list);
        StudioException getException = assertThrows(StudioException.class, () -> service.get(10L));

        assertEquals("Runtime cluster management requires tenant administrator permission", listException.getMessage());
        assertEquals("Runtime cluster management requires tenant administrator permission", getException.getMessage());
        verify(clusterMapper, never()).selectList(any());
        verify(clusterMapper, never()).selectOne(any());
    }

    @Test
    void shouldKeepExplicitlyDisabledProjectAuthorizationRevoked() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.currentProjectId()).thenReturn(101L);
        when(clusterMapper.selectList(any())).thenReturn(List.of(cluster(10L, "DEFAULT-LOCAL", "默认本地集群", 1)));
        ProjectRuntimeClusterEntity revoked = new ProjectRuntimeClusterEntity();
        revoked.setProjectId(101L);
        revoked.setRuntimeClusterId(10L);
        revoked.setEnabled(0);
        revoked.setPreferred(1);
        when(authorizationMapper.selectList(any())).thenReturn(List.of(revoked));
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                authorizationMapper, security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), projectMapper(101L, "tenant-a"));
        service.setStudioPlatformProperties(singleClusterCompatibilityProperties());

        List<RuntimeClusterView> options = service.options(101L);

        assertTrue(options.isEmpty());
        verify(authorizationMapper, never()).insert(any(ProjectRuntimeClusterEntity.class));
        verify(authorizationMapper, never()).updateById(any(ProjectRuntimeClusterEntity.class));
    }

    @Test
    void shouldNotRecoverLogicallyDeletedProjectAuthorizationFromOptions() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.currentProjectId()).thenReturn(101L);
        when(clusterMapper.selectList(any())).thenReturn(List.of(cluster(10L, "DEFAULT-LOCAL", "默认本地集群", 1)));
        when(authorizationMapper.selectList(any())).thenReturn(List.of());
        when(authorizationMapper.selectPhysicalCountByProject("tenant-a", 101L)).thenReturn(1L);
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                authorizationMapper, security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), projectMapper(101L, "tenant-a"));
        service.setStudioPlatformProperties(singleClusterCompatibilityProperties());

        assertTrue(service.options(101L).isEmpty());

        verify(authorizationMapper, never()).insert(any(ProjectRuntimeClusterEntity.class));
    }

    @Test
    void shouldRejectProjectAuthorizationLookupOutsideCurrentTenant() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.currentProjectId()).thenReturn(999L);
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(projectMapper.selectOne(any())).thenReturn(null);
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                mock(ProjectRuntimeClusterMapper.class), security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), projectMapper);

        StudioException exception = assertThrows(StudioException.class, () -> service.options(101L));

        assertEquals("Project not found", exception.getMessage());
        verify(clusterMapper, never()).selectList(any());
    }

    @Test
    void shouldMaskEndpointAndNeverExposeSecretValues() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        EncryptionService encryption = mock(EncryptionService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        when(clusterMapper.selectOne(any())).thenReturn(cluster(10L, "C50", "50 集群", 1));

        RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
        endpoint.setId(101L);
        endpoint.setRuntimeClusterId(10L);
        endpoint.setMode("HTTP");
        endpoint.setEndpointCiphertext("encrypted-url");
        endpoint.setHeadersCiphertext("encrypted-headers");
        endpoint.setTokenCiphertext("encrypted-token");
        endpoint.setEnabled(1);
        endpoint.setConnectTimeoutMillis(3000);
        endpoint.setReadTimeoutMillis(5000);
        when(endpointMapper.selectList(any())).thenReturn(List.of(endpoint));
        when(encryption.decrypt("encrypted-url")).thenReturn("https://runtime.internal.example/api?access-token=secret");
        when(encryption.decrypt("encrypted-headers")).thenReturn("{\"Authorization\":\"Bearer secret\",\"X-Trace\":\"trace-secret\"}");

        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper, authorizationMapper,
                security, encryption, objectMapper(), mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));

        RuntimeEndpointView view = service.endpoints(10L).get(0);

        assertEquals("https://runtime.internal.example/***", view.getEndpointMasked());
        assertEquals(List.of("Authorization", "X-Trace"), view.getHeaderNames());
        assertTrue(view.isHasToken());
        assertFalse(view.getEndpointMasked().contains("secret"));
    }

    @Test
    void shouldExposeExpiredInstancesAsOffline() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);

        RuntimeClusterEntity entity = cluster(10L, "C50", "50 集群", 1);
        entity.setLastHeartbeatAt(LocalDateTime.now().minusMinutes(2));
        Map<String, Object> instance = new LinkedHashMap<String, Object>();
        instance.put("instanceId", "worker-1");
        instance.put("bootId", "boot-1");
        instance.put("workerGroupCode", "worker-group");
        instance.put("heartbeatAt", LocalDateTime.now().minusMinutes(2).toString());
        entity.getInstancesJson().put("worker-1", instance);
        when(clusterMapper.selectOne(any())).thenReturn(entity);

        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper, authorizationMapper,
                security, mock(EncryptionService.class), objectMapper(), mock(RuntimeClusterReferenceRepository.class),
                mock(ProjectMapper.class));

        RuntimeClusterView clusterView = service.get(10L);
        List<RuntimeClusterInstanceView> instances = service.instances(10L);

        assertEquals("OFFLINE", clusterView.getStatus());
        assertEquals(0, clusterView.getOnlineInstanceCount());
        assertEquals(1, instances.size());
        assertEquals("OFFLINE", instances.get(0).getStatus());
        assertFalse(instances.get(0).isOnline());
        assertFalse(service.hasOnlineInstance(entity));
    }

    @Test
    void shouldExposeRecentInstanceAsOnline() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);

        RuntimeClusterEntity entity = cluster(10L, "C50", "50 集群", 1);
        entity.setLastHeartbeatAt(LocalDateTime.now());
        Map<String, Object> instance = new LinkedHashMap<String, Object>();
        instance.put("instanceId", "worker-1");
        instance.put("heartbeatAt", LocalDateTime.now().toString());
        entity.getInstancesJson().put("worker-1", instance);
        when(clusterMapper.selectOne(any())).thenReturn(entity);

        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper, authorizationMapper,
                security, mock(EncryptionService.class), objectMapper(), mock(RuntimeClusterReferenceRepository.class),
                mock(ProjectMapper.class));

        RuntimeClusterView clusterView = service.get(10L);

        assertEquals("ONLINE", clusterView.getStatus());
        assertEquals(1, clusterView.getOnlineInstanceCount());
        assertTrue(clusterView.getInstances().get(0).isOnline());
        assertTrue(service.hasOnlineInstance(entity));
    }

    @Test
    void shouldReadLegacyIsoHeartbeatWithProductionDateTimeConfiguration() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        RuntimeClusterEntity entity = cluster(10L, "C50", "50 集群", 1);
        Map<String, Object> instance = new LinkedHashMap<String, Object>();
        instance.put("instanceId", "worker-legacy");
        instance.put("heartbeatAt", LocalDateTime.now().toString());
        entity.getInstancesJson().put("worker-legacy", instance);
        when(clusterMapper.selectOne(any())).thenReturn(entity);
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                mock(ProjectRuntimeClusterMapper.class), security, mock(EncryptionService.class), builder.build(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));

        List<RuntimeClusterInstanceView> instances = service.instances(10L);

        assertEquals(1, instances.size());
        assertEquals("worker-legacy", instances.get(0).getInstanceId());
        assertTrue(instances.get(0).isOnline());
    }

    @Test
    void shouldDisableClusterWithoutWritingBackStaleInstanceSummary() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        RuntimeClusterEntity entity = cluster(10L, "C50", "50 集群", 1);
        entity.getInstancesJson().put("stale-instance", Map.of("instanceId", "stale-instance"));
        when(clusterMapper.selectOne(any())).thenReturn(entity);
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                mock(ProjectRuntimeClusterMapper.class), security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));

        RuntimeClusterView disabled = service.disable(10L);

        assertFalse(disabled.isEnabled());
        verify(clusterMapper).update(org.mockito.ArgumentMatchers.isNull(), any(LambdaUpdateWrapper.class));
        verify(clusterMapper, never()).updateById(any(RuntimeClusterEntity.class));
    }

    @Test
    void shouldPersistExpiredOnlineClustersAsOffline() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        when(clusterMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(2);
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                mock(ProjectRuntimeClusterMapper.class), mock(StudioSecurityService.class),
                mock(EncryptionService.class), objectMapper(), mock(RuntimeClusterReferenceRepository.class),
                mock(ProjectMapper.class));

        assertEquals(2, service.refreshOfflineStatuses());
        verify(clusterMapper).update(org.mockito.ArgumentMatchers.isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void shouldRejectDeletingReferencedCluster() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        RuntimeClusterReferenceRepository referenceRepository = mock(RuntimeClusterReferenceRepository.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        RuntimeClusterEntity entity = cluster(10L, "C50", "50 集群", 0);
        entity.setStatus("OFFLINE");
        when(clusterMapper.selectOne(any())).thenReturn(entity);
        when(referenceRepository.countBlockingReferences("tenant-a", 10L)).thenReturn(1L);

        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper, authorizationMapper,
                security, mock(EncryptionService.class), objectMapper(), referenceRepository, mock(ProjectMapper.class));

        assertThrows(StudioException.class, () -> service.delete(10L));
        verify(clusterMapper, never()).deleteById(10L);
    }

    @Test
    void shouldDeleteDisabledUnreferencedCluster() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        RuntimeClusterReferenceRepository referenceRepository = mock(RuntimeClusterReferenceRepository.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        RuntimeClusterEntity entity = cluster(10L, "C50", "50 集群", 0);
        entity.setStatus("OFFLINE");
        when(clusterMapper.selectOne(any())).thenReturn(entity);

        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper, authorizationMapper,
                security, mock(EncryptionService.class), objectMapper(), referenceRepository, mock(ProjectMapper.class));

        service.delete(10L);

        verify(referenceRepository).cleanupNonBlockingReferences("tenant-a", 10L);
        verify(clusterMapper).deleteById(10L);
    }

    @Test
    void shouldReturnNullClusterNameForHistoricalReferenceAfterDeletion() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(clusterMapper.selectOne(any())).thenReturn(null);
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, mock(RuntimeEndpointMapper.class),
                mock(ProjectRuntimeClusterMapper.class), security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));

        assertNull(service.clusterName(46L));
    }

    @Test
    void shouldDeleteClusterMatchingFormerServerRuntimeIdentity() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        RuntimeClusterReferenceRepository referenceRepository = mock(RuntimeClusterReferenceRepository.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        RuntimeClusterEntity entity = cluster(10L, "OMS", "OMS", 0);
        entity.setStatus("OFFLINE");
        when(clusterMapper.selectOne(any())).thenReturn(entity);

        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper, authorizationMapper,
                security, mock(EncryptionService.class), objectMapper(), referenceRepository,
                mock(ProjectMapper.class));
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeClusterCode("OMS");
        service.setStudioPlatformProperties(properties);

        service.delete(10L);

        verify(referenceRepository).cleanupNonBlockingReferences("tenant-a", 10L);
        verify(clusterMapper).deleteById(10L);
    }

    @Test
    void shouldRequireEndpointToBeDisabledBeforeDeletion() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
        endpoint.setId(101L);
        endpoint.setTenantId("tenant-a");
        endpoint.setRuntimeClusterId(10L);
        endpoint.setEnabled(1);
        when(endpointMapper.selectOne(any())).thenReturn(endpoint);

        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper, authorizationMapper,
                security, mock(EncryptionService.class), objectMapper(), mock(RuntimeClusterReferenceRepository.class),
                mock(ProjectMapper.class));

        assertThrows(StudioException.class, () -> service.deleteEndpoint(101L));
        verify(endpointMapper, never()).deleteById(101L);
    }

    @Test
    void shouldDisableLegacyLocalEndpointWithoutEditingIt() {
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
        endpoint.setId(101L);
        endpoint.setTenantId("tenant-a");
        endpoint.setRuntimeClusterId(10L);
        endpoint.setMode("LOCAL");
        endpoint.setEnabled(1);
        when(endpointMapper.selectOne(any())).thenReturn(endpoint);
        RuntimeClusterService service = new RuntimeClusterService(mock(RuntimeClusterMapper.class), endpointMapper,
                mock(ProjectRuntimeClusterMapper.class), security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));

        RuntimeEndpointView disabled = service.disableEndpoint(101L);

        assertFalse(disabled.isEnabled());
        verify(endpointMapper).updateById(endpoint);
    }

    @Test
    void shouldRejectCreatingLegacyLocalEndpoint() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        EncryptionService encryption = mock(EncryptionService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        when(clusterMapper.selectOne(any())).thenReturn(cluster(46L, "C46", "46 集群", 1));
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper,
                mock(ProjectRuntimeClusterMapper.class), security, encryption, objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));
        RuntimeEndpointSaveRequest request = new RuntimeEndpointSaveRequest();
        request.setRuntimeClusterId(46L);
        request.setMode("LOCAL");

        StudioException exception = assertThrows(StudioException.class, () -> service.saveEndpoint(request));

        assertTrue(exception.getMessage().contains("Only HTTP runtime endpoints"));
        verify(encryption, never()).encrypt(anyString());
        verify(endpointMapper, never()).insert(any(RuntimeEndpointEntity.class));
    }

    @Test
    void shouldFailLegacyLocalEndpointTestAndPromptMigrationEvenWhenDisabled() {
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
        endpoint.setId(101L);
        endpoint.setTenantId("tenant-a");
        endpoint.setRuntimeClusterId(10L);
        endpoint.setMode("LOCAL");
        endpoint.setEnabled(0);
        when(endpointMapper.selectOne(any())).thenReturn(endpoint);
        RuntimeClusterService service = new RuntimeClusterService(mock(RuntimeClusterMapper.class), endpointMapper,
                mock(ProjectRuntimeClusterMapper.class), security, mock(EncryptionService.class), objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));

        RuntimeEndpointView tested = service.testEndpoint(101L);

        assertEquals("FAILED", tested.getLastTestStatus());
        assertTrue(tested.getLastTestMessage().contains("Worker HTTP/SLB endpoint"));
        verify(endpointMapper).updateById(endpoint);
    }

    @Test
    void shouldRejectUnsafeHttpEndpointBeforeEncryptingOrSavingIt() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        EncryptionService encryption = mock(EncryptionService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        when(clusterMapper.selectOne(any())).thenReturn(cluster(46L, "C46", "46 集群", 1));
        RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper,
                mock(ProjectRuntimeClusterMapper.class), security, encryption, objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));
        service.setStudioPlatformProperties(new StudioPlatformProperties());
        RuntimeEndpointSaveRequest request = new RuntimeEndpointSaveRequest();
        request.setRuntimeClusterId(46L);
        request.setMode("HTTP");
        request.setEndpointUrl("http://127.0.0.1:18081");

        assertThrows(StudioException.class, () -> service.saveEndpoint(request));

        verify(encryption, never()).encrypt(anyString());
        verify(endpointMapper, never()).insert(any(RuntimeEndpointEntity.class));
    }

    @Test
    void shouldRejectUnsafeEndpointTestBeforeDecryptingTransportSecrets() {
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        EncryptionService encryption = mock(EncryptionService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
        endpoint.setId(101L);
        endpoint.setTenantId("tenant-a");
        endpoint.setRuntimeClusterId(50L);
        endpoint.setMode("HTTP");
        endpoint.setEnabled(1);
        endpoint.setEndpointCiphertext("encrypted-url");
        endpoint.setHeadersCiphertext("encrypted-headers");
        endpoint.setTokenCiphertext("encrypted-token");
        when(endpointMapper.selectOne(any())).thenReturn(endpoint);
        when(encryption.decrypt("encrypted-url")).thenReturn("http://127.0.0.1:18081");
        RuntimeClusterService service = new RuntimeClusterService(mock(RuntimeClusterMapper.class), endpointMapper,
                mock(ProjectRuntimeClusterMapper.class), security, encryption, objectMapper(),
                mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("internal-secret");
        service.setStudioPlatformProperties(properties);

        RuntimeEndpointView tested = service.testEndpoint(101L);

        assertEquals("FAILED", tested.getLastTestStatus());
        assertTrue(tested.getLastTestMessage().contains("HTTPS"));
        verify(encryption, never()).decrypt("encrypted-headers");
        verify(encryption, never()).decrypt("encrypted-token");
    }

    @Test
    void shouldTestFixedAuthenticatedRuntimeHealthEndpointAndRequire2xx() throws Exception {
        AtomicReference<String> path = new AtomicReference<String>();
        AtomicReference<String> token = new AtomicReference<String>();
        AtomicReference<String> targetCluster = new AtomicReference<String>();
        AtomicReference<String> allowedTransportHeader = new AtomicReference<String>();
        AtomicReference<String> connectionDeclaredHeader = new AtomicReference<String>();
        AtomicReference<String> reservedStudioHeader = new AtomicReference<String>();
        AtomicReference<String> configuredHost = new AtomicReference<String>();
        AtomicReference<String> configuredContentLength = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/health", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            token.set(exchange.getRequestHeaders().getFirst("X-Studio-Internal-Token"));
            targetCluster.set(exchange.getRequestHeaders().getFirst("X-Studio-Target-Cluster-Id"));
            allowedTransportHeader.set(exchange.getRequestHeaders().getFirst("X-Transport-Auth"));
            connectionDeclaredHeader.set(exchange.getRequestHeaders().getFirst("X-Remove-Me"));
            reservedStudioHeader.set(exchange.getRequestHeaders().getFirst("X-Studio-Custom"));
            configuredHost.set(exchange.getRequestHeaders().getFirst("Host"));
            configuredContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
            RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
            StudioSecurityService security = mock(StudioSecurityService.class);
            EncryptionService encryption = mock(EncryptionService.class);
            when(security.currentTenantId()).thenReturn("tenant-a");
            when(security.hasAnyRole(any(String[].class))).thenReturn(true);
            RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
            endpoint.setId(101L);
            endpoint.setTenantId("tenant-a");
            endpoint.setRuntimeClusterId(50L);
            endpoint.setMode("HTTP");
            endpoint.setEnabled(1);
            endpoint.setEndpointCiphertext("encrypted-url");
            endpoint.setHeadersCiphertext("encrypted-headers");
            endpoint.setConnectTimeoutMillis(3000);
            endpoint.setReadTimeoutMillis(3000);
            when(endpointMapper.selectOne(any())).thenReturn(endpoint);
            when(encryption.decrypt("encrypted-url"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            Map<String, String> configuredHeaders = new LinkedHashMap<String, String>();
            configuredHeaders.put("X-Transport-Auth", "transport-secret");
            configuredHeaders.put("Connection", "X-Remove-Me");
            configuredHeaders.put("X-Remove-Me", "blocked");
            configuredHeaders.put("Host", "attacker.invalid");
            configuredHeaders.put("Content-Length", "999");
            configuredHeaders.put("Keep-Alive", "timeout=5");
            configuredHeaders.put("X-Studio-Internal-Token", "attacker");
            configuredHeaders.put("X-Studio-Target-Cluster-Id", "999");
            configuredHeaders.put("X-Studio-Custom", "blocked");
            when(encryption.decrypt("encrypted-headers"))
                    .thenReturn(objectMapper().writeValueAsString(configuredHeaders));
            RuntimeClusterService service = new RuntimeClusterService(clusterMapper, endpointMapper,
                    mock(ProjectRuntimeClusterMapper.class), security, encryption, objectMapper(),
                    mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.setInternalApiToken("internal-secret");
            properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
            service.setStudioPlatformProperties(properties);

            RuntimeEndpointView tested = service.testEndpoint(101L);

            assertEquals("/internal/runtime/health", path.get());
            assertEquals("internal-secret", token.get());
            assertEquals("50", targetCluster.get());
            assertEquals("transport-secret", allowedTransportHeader.get());
            assertNull(connectionDeclaredHeader.get());
            assertNull(reservedStudioHeader.get());
            assertFalse("attacker.invalid".equals(configuredHost.get()));
            assertFalse("999".equals(configuredContentLength.get()));
            assertEquals("FAILED", tested.getLastTestStatus());
            assertEquals("HTTP 401", tested.getLastTestMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRequireAuthenticatedRuntimeResponseMarkerForSuccessfulEndpointTest() throws Exception {
        AtomicBoolean includeRuntimeMarker = new AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/health", exchange -> {
            if (includeRuntimeMarker.get()) {
                exchange.getResponseHeaders().add(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                        RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
            StudioSecurityService security = mock(StudioSecurityService.class);
            EncryptionService encryption = mock(EncryptionService.class);
            when(security.currentTenantId()).thenReturn("tenant-a");
            when(security.hasAnyRole(any(String[].class))).thenReturn(true);
            RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
            endpoint.setId(101L);
            endpoint.setTenantId("tenant-a");
            endpoint.setRuntimeClusterId(50L);
            endpoint.setMode("HTTP");
            endpoint.setEnabled(1);
            endpoint.setEndpointCiphertext("encrypted-url");
            when(endpointMapper.selectOne(any())).thenReturn(endpoint);
            when(encryption.decrypt("encrypted-url"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            RuntimeClusterService service = new RuntimeClusterService(mock(RuntimeClusterMapper.class), endpointMapper,
                    mock(ProjectRuntimeClusterMapper.class), security, encryption, objectMapper(),
                    mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.setInternalApiToken("internal-secret");
            properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
            service.setStudioPlatformProperties(properties);

            RuntimeEndpointView missingMarker = service.testEndpoint(101L);
            assertEquals("FAILED", missingMarker.getLastTestStatus());
            assertEquals("Runtime response authentication marker is missing",
                    missingMarker.getLastTestMessage());

            includeRuntimeMarker.set(true);
            RuntimeEndpointView authenticated = service.testEndpoint(101L);
            assertEquals("SUCCESS", authenticated.getLastTestStatus());
            assertEquals("HTTP 200", authenticated.getLastTestMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFailEndpointTestWhenHealthResponseExceedsLimit() throws Exception {
        byte[] responseBody = new byte[2048];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/health", exchange -> {
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
        try {
            RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
            StudioSecurityService security = mock(StudioSecurityService.class);
            EncryptionService encryption = mock(EncryptionService.class);
            when(security.currentTenantId()).thenReturn("tenant-a");
            when(security.hasAnyRole(any(String[].class))).thenReturn(true);
            RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
            endpoint.setId(101L);
            endpoint.setTenantId("tenant-a");
            endpoint.setRuntimeClusterId(50L);
            endpoint.setMode("HTTP");
            endpoint.setEnabled(1);
            endpoint.setEndpointCiphertext("encrypted-url");
            endpoint.setConnectTimeoutMillis(3000);
            endpoint.setReadTimeoutMillis(3000);
            when(endpointMapper.selectOne(any())).thenReturn(endpoint);
            when(encryption.decrypt("encrypted-url"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            RuntimeClusterService service = new RuntimeClusterService(mock(RuntimeClusterMapper.class), endpointMapper,
                    mock(ProjectRuntimeClusterMapper.class), security, encryption, objectMapper(),
                    mock(RuntimeClusterReferenceRepository.class), mock(ProjectMapper.class));
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.setInternalApiToken("internal-secret");
            properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
            properties.getRuntimeEndpoint().setMaxResponseBytes(1024);
            service.setStudioPlatformProperties(properties);

            RuntimeEndpointView tested = service.testEndpoint(101L);

            assertEquals("FAILED", tested.getLastTestStatus());
            assertTrue(tested.getLastTestMessage().contains("exceeds the configured limit"));
        } finally {
            server.stop(0);
        }
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private ProjectMapper projectMapper(Long projectId, String tenantId) {
        ProjectMapper mapper = mock(ProjectMapper.class);
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTenantId(tenantId);
        when(mapper.selectOne(any())).thenReturn(project);
        return mapper;
    }

    private StudioPlatformProperties singleClusterCompatibilityProperties() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeClusterCode("default-local");
        return properties;
    }

    private RuntimeClusterEntity cluster(Long id, String code, String name, int enabled) {
        RuntimeClusterEntity entity = new RuntimeClusterEntity();
        entity.setId(id);
        entity.setTenantId("tenant-a");
        entity.setCode(code);
        entity.setName(name);
        entity.setEnabled(enabled);
        entity.setStatus("ONLINE");
        return entity;
    }
}
