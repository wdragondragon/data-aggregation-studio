package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.test.support.StudioHttpIntegrationTestSupport;
import com.jdragon.studio.worker.bootstrap.StudioWorkerApplication;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the same HTTP endpoint contract used by a managed SLB: OMS only knows one endpoint,
 * while the proxy selects one of several Workers in the target runtime cluster.
 */
class WorkerSlbRoutingIT extends StudioHttpIntegrationTestSupport {

    private static final String CLUSTER_CODE = "SLB-TEST";
    private static final String CONFIGURED_SLB_HEADER = "X-Studio-Test-Slb";
    private static final String CONFIGURED_SLB_HEADER_VALUE = "managed-endpoint";
    private static final Object RUNTIME_MONITOR = new Object();
    private static final List<WorkerRuntime> WORKERS = new CopyOnWriteArrayList<WorkerRuntime>();
    private static volatile SlbProxy proxy;

    @DynamicPropertySource
    static void registerSlbEndpointProperties(DynamicPropertyRegistry registry) {
        registry.add("studio.runtime-endpoint.allowed-hosts[0]", () -> "127.0.0.1");
    }

    @AfterAll
    static void stopWorkersAndProxy() {
        SlbProxy currentProxy = proxy;
        proxy = null;
        if (currentProxy != null) {
            currentProxy.close();
        }
        for (WorkerRuntime worker : WORKERS) {
            worker.close();
        }
        WORKERS.clear();
    }

    @Test
    void omsShouldRouteAWorkerHttpEndpointThroughSlbAndRecoverAfterBackendRestart() throws Exception {
        SlbProxy slb = ensureSlbStarted();
        JsonNode login = loginAsAdminHttp();
        String authorization = bearer(login);
        Long projectId = currentProjectId(login);
        String tenantId = login.path("data").path("currentTenantId").asText();

        Long runtimeClusterId = createClusterAndEndpoint(authorization, projectId, slb.baseUrl());
        sendHeartbeat(tenantId, runtimeClusterId, "slb-worker-a");

        JsonNode first = testEndpoint(authorization, projectId, runtimeClusterId);
        JsonNode second = testEndpoint(authorization, projectId, runtimeClusterId);
        assertThat(first.path("lastTestStatus").asText())
                .as("first endpoint test: %s", first.path("lastTestMessage").asText()).isEqualTo("SUCCESS");
        assertThat(second.path("lastTestStatus").asText())
                .as("second endpoint test: %s", second.path("lastTestMessage").asText()).isEqualTo("SUCCESS");
        assertThat(slb.backendPorts()).containsExactly(WORKERS.get(0).port, WORKERS.get(1).port);
        assertThat(slb.requestHeaders("X-Studio-Internal-Token"))
                .allMatch(TEST_INTERNAL_API_TOKEN::equals);
        assertThat(slb.requestHeaders("X-Studio-Target-Cluster-Id"))
                .allMatch(String.valueOf(runtimeClusterId)::equals);
        assertThat(slb.requestHeaders(CONFIGURED_SLB_HEADER))
                .allMatch(CONFIGURED_SLB_HEADER_VALUE::equals);

        WORKERS.get(0).close();
        JsonNode oneBackendRemaining = testEndpoint(authorization, projectId, runtimeClusterId);
        assertThat(oneBackendRemaining.path("lastTestStatus").asText()).isEqualTo("SUCCESS");
        assertThat(slb.backendPorts()).last().isEqualTo(WORKERS.get(1).port);

        WORKERS.get(1).close();
        JsonNode allBackendsOffline = testEndpoint(authorization, projectId, runtimeClusterId);
        assertThat(allBackendsOffline.path("lastTestStatus").asText()).isEqualTo("FAILED");
        assertThat(allBackendsOffline.path("lastTestMessage").asText()).isEqualTo("HTTP 503");

        WORKERS.get(0).restart();
        JsonNode recovered = testEndpoint(authorization, projectId, runtimeClusterId);
        assertThat(recovered.path("lastTestStatus").asText()).isEqualTo("SUCCESS");
    }

    private Long createClusterAndEndpoint(String authorization, Long projectId, String endpointUrl) throws Exception {
        Map<String, Object> clusterPayload = new LinkedHashMap<String, Object>();
        clusterPayload.put("code", CLUSTER_CODE);
        clusterPayload.put("name", "SLB routing integration cluster");
        clusterPayload.put("enabled", Boolean.TRUE);
        JsonNode cluster = requireSuccess(postJson("/api/v1/runtime-clusters", authorization, projectId, clusterPayload));
        Long clusterId = cluster.path("data").path("id").asLong();

        Map<String, Object> authorizationPayload = new LinkedHashMap<String, Object>();
        authorizationPayload.put("projectId", projectId);
        authorizationPayload.put("runtimeClusterId", clusterId);
        authorizationPayload.put("enabled", Boolean.TRUE);
        authorizationPayload.put("preferred", Boolean.TRUE);
        authorizationPayload.put("allowManualOverride", Boolean.FALSE);
        requireSuccess(postJson("/api/v1/runtime-clusters/project-authorizations",
                authorization, projectId, authorizationPayload));

        Map<String, Object> endpointPayload = new LinkedHashMap<String, Object>();
        endpointPayload.put("runtimeClusterId", clusterId);
        endpointPayload.put("mode", "HTTP");
        endpointPayload.put("endpointUrl", endpointUrl);
        endpointPayload.put("headers", Collections.singletonMap(CONFIGURED_SLB_HEADER, CONFIGURED_SLB_HEADER_VALUE));
        endpointPayload.put("connectTimeoutMillis", Integer.valueOf(3000));
        endpointPayload.put("readTimeoutMillis", Integer.valueOf(5000));
        endpointPayload.put("enabled", Boolean.TRUE);
        JsonNode endpoint = requireSuccess(postJson("/api/v1/runtime-clusters/endpoints",
                authorization, projectId, endpointPayload));
        return endpoint.path("data").path("runtimeClusterId").asLong();
    }

    private JsonNode testEndpoint(String authorization, Long projectId, Long runtimeClusterId) throws Exception {
        JsonNode endpoints = requireSuccess(get("/api/v1/runtime-clusters/" + runtimeClusterId + "/endpoints",
                authorization, projectId));
        long endpointId = endpoints.path("data").get(0).path("id").asLong();
        return requireSuccess(postJson("/api/v1/runtime-clusters/endpoints/" + endpointId + "/test",
                authorization, projectId, Collections.emptyMap())).path("data");
    }

    private void sendHeartbeat(String tenantId, Long runtimeClusterId, String instanceId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("tenantId", tenantId);
        payload.put("clusterCode", CLUSTER_CODE);
        payload.put("instanceId", instanceId);
        payload.put("version", "test");
        payload.put("summary", "slb-routing-it:" + runtimeClusterId);
        requireSuccess(postJson("/api/v1/runtime-clusters/internal/heartbeat", null, null, payload,
                Collections.singletonMap(StudioConstants.INTERNAL_API_TOKEN_HEADER, TEST_INTERNAL_API_TOKEN)));
    }

    private static SlbProxy ensureSlbStarted() {
        SlbProxy existing = proxy;
        if (existing != null) {
            return existing;
        }
        synchronized (RUNTIME_MONITOR) {
            if (proxy == null) {
                WORKERS.add(WorkerRuntime.start("slb-worker-a"));
                WORKERS.add(WorkerRuntime.start("slb-worker-b"));
                proxy = SlbProxy.start(WORKERS);
            }
            return proxy;
        }
    }

    private static final class WorkerRuntime implements AutoCloseable {
        private final String instanceId;
        private ConfigurableApplicationContext context;
        private int port;

        private WorkerRuntime(String instanceId) {
            this.instanceId = instanceId;
        }

        static WorkerRuntime start(String instanceId) {
            WorkerRuntime worker = new WorkerRuntime(instanceId);
            worker.restart();
            return worker;
        }

        synchronized void restart() {
            close();
            Map<String, Object> properties = new LinkedHashMap<String, Object>();
            properties.put("server.address", "127.0.0.1");
            properties.put("server.port", "0");
            properties.put("spring.application.name", "studio-worker");
            properties.put("spring.profiles.active", "test");
            properties.put("spring.config.import", "");
            properties.put("spring.main.banner-mode", "off");
            properties.put("spring.datasource.url", "jdbc:sqlite:" + SQLITE_DB.toAbsolutePath().normalize().toString().replace('\\', '/'));
            properties.put("spring.datasource.driver-class-name", "org.sqlite.JDBC");
            properties.put("spring.datasource.username", "");
            properties.put("spring.datasource.password", "");
            properties.put("spring.datasource.hikari.connection-init-sql", "PRAGMA busy_timeout=30000");
            properties.put("spring.datasource.hikari.maximum-pool-size", "1");
            properties.put("spring.sql.init.mode", "never");
            properties.put("spring.quartz.auto-startup", "false");
            properties.put("spring.cloud.nacos.config.enabled", "false");
            properties.put("spring.cloud.nacos.discovery.enabled", "false");
            properties.put("spring.autoconfigure.exclude", "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
            properties.put("studio.schema.auto-upgrade-on-startup", "false");
            properties.put("studio.aggregation-home", AGGREGATION_HOME.toAbsolutePath().normalize().toString());
            properties.put("studio.internal-api-token", TEST_INTERNAL_API_TOKEN);
            properties.put("studio.encryption-secret", TEST_ENCRYPTION_SECRET);
            properties.put("studio.runtime-cluster-code", CLUSTER_CODE);
            properties.put("studio.instance-id", instanceId);
            properties.put("studio.worker-code", instanceId);
            properties.put("studio.worker-group-code", instanceId);
            properties.put("studio.worker.lifecycle.enabled", "false");
            properties.put("studio.runtime-invocation-idempotency.cleanup-enabled", "false");
            properties.put("studio.datasource-health.enabled", "false");
            properties.put("studio.scan-plugins-on-startup", "false");
            properties.put("studio.alert.enabled", "false");
            properties.put("studio.runtime-log-dir", TEST_RUNTIME_DIR.resolve(instanceId + "-logs").toString());
            List<String> arguments = new ArrayList<String>();
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                arguments.add("--" + entry.getKey() + "=" + entry.getValue());
            }
            context = new SpringApplicationBuilder(StudioWorkerApplication.class)
                    .run(arguments.toArray(new String[0]));
            if (!(context instanceof WebServerApplicationContext)) {
                context.close();
                context = null;
                throw new IllegalStateException("Studio Worker test context did not start a web server");
            }
            port = ((WebServerApplicationContext) context).getWebServer().getPort();
        }

        synchronized boolean isRunning() {
            return context != null && context.isRunning();
        }

        @Override
        public synchronized void close() {
            if (context != null) {
                context.close();
                context = null;
            }
        }
    }

    private static final class SlbProxy implements AutoCloseable {
        private final HttpServer server;
        private final List<WorkerRuntime> workers;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        private final ExecutorService executor;
        private final AtomicInteger nextWorker = new AtomicInteger();
        private final List<Integer> backendPorts = new CopyOnWriteArrayList<Integer>();
        private final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();

        private SlbProxy(HttpServer server, List<WorkerRuntime> workers, ExecutorService executor) {
            this.server = server;
            this.workers = workers;
            this.executor = executor;
        }

        static SlbProxy start(List<WorkerRuntime> workers) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = Executors.newCachedThreadPool();
                SlbProxy proxy = new SlbProxy(server, workers, executor);
                server.createContext("/", proxy::forward);
                server.setExecutor(executor);
                server.start();
                return proxy;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to start SLB test proxy", ex);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<Integer> backendPorts() {
            return new ArrayList<Integer>(backendPorts);
        }

        List<String> requestHeaders(String name) {
            synchronized (headers) {
                return new ArrayList<String>(headers.getOrDefault(name, Collections.emptyList()));
            }
        }

        private void forward(HttpExchange exchange) throws IOException {
            WorkerRuntime target = selectRunningWorker();
            recordHeaders(exchange);
            if (target == null) {
                reply(exchange, 503, "No healthy runtime Worker");
                return;
            }
            backendPorts.add(target.port);
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            URI targetUri = URI.create("http://127.0.0.1:" + target.port + exchange.getRequestURI());
            HttpRequest.Builder request = HttpRequest.newBuilder(targetUri)
                    .timeout(Duration.ofSeconds(10))
                    .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBody));
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!isHopByHopOrManagedRequestHeader(name)) {
                    values.forEach(value -> request.header(name, value));
                }
            });
            try {
                HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                response.headers().map().forEach((name, values) -> {
                    if (!"transfer-encoding".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name)) {
                        exchange.getResponseHeaders().put(name, values);
                    }
                });
                reply(exchange, response.statusCode(), response.body());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                reply(exchange, 503, "Runtime Worker request interrupted");
            } catch (Exception ex) {
                reply(exchange, 503, "Runtime Worker unavailable");
            }
        }

        private WorkerRuntime selectRunningWorker() {
            List<WorkerRuntime> available = new ArrayList<WorkerRuntime>();
            for (WorkerRuntime worker : workers) {
                if (worker.isRunning()) {
                    available.add(worker);
                }
            }
            if (available.isEmpty()) {
                return null;
            }
            return available.get(Math.floorMod(nextWorker.getAndIncrement(), available.size()));
        }

        private void recordHeaders(HttpExchange exchange) {
            synchronized (headers) {
                exchange.getRequestHeaders().forEach((name, values) -> headers
                        .computeIfAbsent(name, ignored -> new ArrayList<String>()).addAll(values));
            }
        }

        private boolean isHopByHopOrManagedRequestHeader(String name) {
            return "host".equalsIgnoreCase(name)
                    || "content-length".equalsIgnoreCase(name)
                    || "connection".equalsIgnoreCase(name)
                    || "expect".equalsIgnoreCase(name)
                    || "upgrade".equalsIgnoreCase(name)
                    || "http2-settings".equalsIgnoreCase(name)
                    || "te".equalsIgnoreCase(name)
                    || "transfer-encoding".equalsIgnoreCase(name);
        }

        private void reply(HttpExchange exchange, int status, String body) throws IOException {
            reply(exchange, status, body.getBytes(StandardCharsets.UTF_8));
        }

        private void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
