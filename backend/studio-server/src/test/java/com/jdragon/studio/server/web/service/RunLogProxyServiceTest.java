package com.jdragon.studio.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.RunLogChunkEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.RunLogChunkMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.infra.service.RunService;
import com.jdragon.studio.infra.service.RuntimeEndpointHttpClient;
import com.jdragon.studio.infra.service.RuntimeEndpointSecurityService;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import com.sun.net.httpserver.HttpServer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunLogProxyServiceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        if (TableInfoHelper.getTableInfo(WorkerLeaseEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "run-log-proxy-test"),
                    WorkerLeaseEntity.class);
        }
    }

    @Test
    void shouldUseTheExactOnlineWorkerLeaseAndRequireAuthenticatedResponse() throws Exception {
        AtomicReference<String> receivedToken = new AtomicReference<String>();
        HttpServer server = server(true,
                "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"ok\","
                        + "\"data\":{\"runRecordId\":91,\"content\":\"hello\",\"sizeBytes\":5}}",
                receivedToken);
        Fixture fixture = fixture("http://127.0.0.1:" + server.getAddress().getPort(), true, 10 * 1024 * 1024);
        try {
            RunLogView result = fixture.proxy.viewLog(91L, 1, 1024 * 1024);

            assertThat(result.getRunRecordId()).isEqualTo(91L);
            assertThat(result.getContent()).isEqualTo("hello");
            assertThat(receivedToken.get()).isEqualTo("internal-token");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<LambdaQueryWrapper<WorkerLeaseEntity>> captor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(fixture.workerLeaseMapper).selectOne(captor.capture());
            String sqlSegment = captor.getValue().getSqlSegment().toLowerCase();
            assertThat(captor.getValue().getParamNameValuePairs().values())
                    .contains("tenant-a", "worker-a", StudioConstants.WORKER_STATUS_ONLINE,
                            50L, "instance-a", "boot-a");
            assertThat(sqlSegment)
                    .contains("runtime_cluster_id", "instance_id", "boot_id", "lease_expires_at",
                            "last_heartbeat_at", "order by");
        } finally {
            fixture.httpClient.close();
            server.stop(0);
        }
    }

    @Test
    void shouldRejectAValidLookingResponseWithoutWorkerAuthenticationMarker() throws Exception {
        HttpServer server = server(false,
                "{\"success\":true,\"data\":{\"runRecordId\":91,\"content\":\"wrong upstream\"}}",
                new AtomicReference<String>());
        Fixture fixture = fixture("http://127.0.0.1:" + server.getAddress().getPort(), true, 10 * 1024 * 1024);
        try {
            assertThatThrownBy(() -> fixture.proxy.viewLog(91L, 1, 4096))
                    .isInstanceOfSatisfying(StudioException.class, exception -> {
                        assertThat(exception.getCode()).isEqualTo(StudioErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(exception.getMessage()).contains("authenticated run log response");
                    });
        } finally {
            fixture.httpClient.close();
            server.stop(0);
        }
    }

    @Test
    void shouldRejectPrivateLeaseEndpointUnlessItsHostIsExplicitlyAllowed() {
        Fixture fixture = fixture("http://127.0.0.1:6553", false, 10 * 1024 * 1024);
        try {
            assertThatThrownBy(() -> fixture.proxy.viewLog(91L, 1, 4096))
                    .isInstanceOfSatisfying(StudioException.class, exception -> {
                        assertThat(exception.getCode()).isEqualTo(StudioErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(exception.getMessage()).contains("runtime endpoint policy");
                    });
        } finally {
            fixture.httpClient.close();
        }
    }

    @Test
    void shouldApplyTheConfiguredResponseLimitToWorkerLogs() throws Exception {
        String largeBody = "{\"success\":true,\"data\":{\"runRecordId\":91,\"content\":\""
                + "x".repeat(4096) + "\"}}";
        HttpServer server = server(true, largeBody, new AtomicReference<String>());
        Fixture fixture = fixture("http://127.0.0.1:" + server.getAddress().getPort(), true, 1024);
        try {
            assertThatThrownBy(() -> fixture.proxy.viewLog(91L, 1, 4096))
                    .isInstanceOfSatisfying(StudioException.class, exception -> {
                        assertThat(exception.getCode()).isEqualTo(StudioErrorCode.INTERNAL_SERVER_ERROR);
                        assertThat(exception.getMessage()).contains("configured maximum size");
                    });
        } finally {
            fixture.httpClient.close();
            server.stop(0);
        }
    }

    @Test
    void shouldPreviewTheRequestedStreamingChunkThroughItsOwningWorker() throws Exception {
        HttpServer server = server(true,
                "{\"success\":true,\"data\":{\"runRecordId\":91,\"content\":\"chunk-content\"}}",
                new AtomicReference<String>());
        Fixture fixture = fixture("http://127.0.0.1:" + server.getAddress().getPort(), true, 10 * 1024 * 1024);
        RunLogChunkEntity chunk = new RunLogChunkEntity();
        chunk.setId(88L);
        chunk.setCollectionTaskId(42L);
        chunk.setRunRecordId(91L);
        chunk.setTenantId("tenant-a");
        chunk.setProjectId(101L);
        when(fixture.runLogChunkMapper.selectOne(any())).thenReturn(chunk);
        try {
            RunLogView result = fixture.proxy.viewChunk(42L, 88L, 2, 4096);
            assertThat(result.getContent()).isEqualTo("chunk-content");
        } finally {
            fixture.httpClient.close();
            server.stop(0);
        }
    }

    private HttpServer server(boolean authenticated,
                              String responseBody,
                              AtomicReference<String> receivedToken) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runs/91/log", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst(StudioConstants.INTERNAL_API_TOKEN_HEADER));
            if (authenticated) {
                exchange.getResponseHeaders().add(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                        RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/internal/runs/chunks/88/preview", exchange -> {
            if (authenticated) {
                exchange.getResponseHeaders().add(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                        RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private Fixture fixture(String workerApiBaseUrl, boolean allowLocalhost, int maxResponseBytes) {
        RunService runService = mock(RunService.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        RunLogChunkMapper runLogChunkMapper = mock(RunLogChunkMapper.class);
        RunRecordEntity pointer = new RunRecordEntity();
        pointer.setId(91L);
        pointer.setTenantId("tenant-a");
        pointer.setProjectId(101L);
        pointer.setCollectionTaskId(42L);
        pointer.setActualClusterId(50L);
        pointer.setActualClusterCode("C50");
        pointer.setWorkerCode("worker-a");
        pointer.setWorkerInstanceId("instance-a");
        pointer.setWorkerBootId("boot-a");
        pointer.setLogStorageType(RunLogStorageService.STORAGE_LOCAL);
        pointer.setLogFilePath("2026-07-23/run-91.log");
        when(runService.getLogPointer(91L)).thenReturn(pointer);

        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setId(7L);
        lease.setTenantId("tenant-a");
        lease.setRuntimeClusterId(50L);
        lease.setRuntimeClusterCode("C50");
        lease.setWorkerCode("worker-a");
        lease.setInstanceId("instance-a");
        lease.setBootId("boot-a");
        lease.setStatus(StudioConstants.WORKER_STATUS_ONLINE);
        lease.setLastHeartbeatAt(LocalDateTime.now());
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("apiBaseUrl", workerApiBaseUrl);
        lease.setCapabilitiesJson(capabilities);
        when(workerLeaseMapper.selectOne(any())).thenReturn(lease);

        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("internal-token");
        properties.getRuntimeEndpoint().setMaxResponseBytes(maxResponseBytes);
        if (allowLocalhost) {
            properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
        }
        RuntimeEndpointHttpClient httpClient = new RuntimeEndpointHttpClient();
        RunLogProxyService proxy = new RunLogProxyService(
                runService, workerLeaseMapper, properties, new ObjectMapper().findAndRegisterModules(),
                mock(RunLogStorageService.class), new RuntimeEndpointSecurityService(properties), httpClient,
                runLogChunkMapper);
        return new Fixture(proxy, workerLeaseMapper, httpClient, runLogChunkMapper);
    }

    private static final class Fixture {
        private final RunLogProxyService proxy;
        private final WorkerLeaseMapper workerLeaseMapper;
        private final RuntimeEndpointHttpClient httpClient;
        private final RunLogChunkMapper runLogChunkMapper;

        private Fixture(RunLogProxyService proxy,
                        WorkerLeaseMapper workerLeaseMapper,
                        RuntimeEndpointHttpClient httpClient,
                        RunLogChunkMapper runLogChunkMapper) {
            this.proxy = proxy;
            this.workerLeaseMapper = workerLeaseMapper;
            this.httpClient = httpClient;
            this.runLogChunkMapper = runLogChunkMapper;
        }
    }
}
