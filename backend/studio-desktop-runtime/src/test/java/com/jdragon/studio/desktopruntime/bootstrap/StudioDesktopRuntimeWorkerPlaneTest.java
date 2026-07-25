package com.jdragon.studio.desktopruntime.bootstrap;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeExecutor;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import com.jdragon.studio.infra.service.ScriptEnvironmentRuntimeService;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import com.jdragon.studio.worker.runtime.runner.WorkerLifecycleRunner;
import com.jdragon.studio.worker.web.controller.InternalDatasourceProbeController;
import com.jdragon.studio.worker.web.controller.InternalRunLogController;
import com.jdragon.studio.worker.web.controller.InternalRuntimeInvocationController;
import com.jdragon.studio.worker.web.controller.InternalScriptEnvironmentHintController;
import com.jdragon.studio.worker.web.filter.FlinkRuntimeCapabilityFilter;
import com.jdragon.studio.worker.web.filter.InternalApiTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = StudioDesktopRuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudioDesktopRuntimeWorkerPlaneTest {

    private static final Path TEST_DIRECTORY = locateProjectRoot()
            .resolve("backend")
            .resolve("studio-desktop-runtime")
            .resolve("target")
            .resolve("worker-plane-test");
    private static final Path SQLITE_DATABASE = TEST_DIRECTORY.resolve("studio.db");

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private StudioPlatformProperties properties;

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        prepareDatabase();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + normalizePath(SQLITE_DATABASE));
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.datasource.username", () -> "");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "PRAGMA busy_timeout=30000");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        registry.add("studio.schema.auto-upgrade-on-startup", () -> "false");
        registry.add("studio.aggregation-home", () -> normalizePath(
                locateProjectRoot().getParent().resolve("package_all").resolve("aggregation")));
        registry.add("studio.internal-api-token", () -> "desktop-worker-plane-token-20260721");
        registry.add("studio.encryption-secret", () -> "desktop-worker-plane-secret-20260721");
        registry.add("studio.alert.enabled", () -> "false");
        registry.add("studio.alert.evaluation-enabled", () -> "false");
        registry.add("studio.alert.delivery-enabled", () -> "false");
    }

    @Test
    void desktopStartsAsControlPlaneWithOneHttpWorkerExecutionPlane() {
        assertThat(applicationContext.getBeansOfType(WorkerLifecycleRunner.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(AggregationSourceCapabilityProvider.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(RuntimeDatasourceProbeExecutor.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(InternalRuntimeInvocationController.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(InternalDatasourceProbeController.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(InternalRunLogController.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(InternalScriptEnvironmentHintController.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ScriptEnvironmentRuntimeService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(InternalApiTokenFilter.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(FlinkRuntimeCapabilityFilter.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(SecurityFilterChain.class)).hasSize(2);

        Map<String, Object> cluster = jdbcTemplate.queryForMap(
                "select id,code,enabled from studio_runtime_cluster where tenant_id='default'");
        Long clusterId = number(cluster.get("id"));
        assertThat(cluster.get("code")).isEqualTo("DEFAULT-LOCAL");
        assertThat(number(cluster.get("enabled"))).isEqualTo(1L);

        Map<String, Object> endpoint = jdbcTemplate.queryForMap(
                "select mode,endpoint_ciphertext,enabled from studio_runtime_endpoint "
                        + "where runtime_cluster_id=?", clusterId);
        assertThat(endpoint.get("mode")).isEqualTo("HTTP");
        assertThat(number(endpoint.get("enabled"))).isEqualTo(1L);
        assertThat(encryptionService.decrypt(String.valueOf(endpoint.get("endpoint_ciphertext"))))
                .isEqualTo("http://127.0.0.1:" + port);
        assertThat(properties.getWorkerApiBaseUrl()).isEqualTo("http://127.0.0.1:" + port);
        assertThat(properties.getRuntimeClusterCode()).isEqualTo("DEFAULT-LOCAL");

        Integer authorizedProjects = jdbcTemplate.queryForObject(
                "select count(*) from studio_project_runtime_cluster "
                        + "where runtime_cluster_id=? and deleted=0 and enabled=1 and preferred=1",
                Integer.class, clusterId);
        assertThat(authorizedProjects).isEqualTo(1);

        ResponseEntity<String> unauthorized = restTemplate.getForEntity(
                "/internal/runtime/health", String.class);
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthorized.getHeaders().getFirst(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER))
                .isEqualTo(RuntimeInternalHeaders.INTERNAL_AUTHENTICATION);

        ResponseEntity<String> missingFlinkCapability = restTemplate.getForEntity(
                "/api/flink/runtime/resolve", String.class);
        assertThat(missingFlinkCapability.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missingFlinkCapability.getBody()).contains("Invalid or expired Flink runtime capability");

        ResponseEntity<String> controlPlaneWithoutJwt = restTemplate.getForEntity(
                "/api/v1/runtime-clusters/options", String.class);
        assertThat(controlPlaneWithoutJwt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Studio-Internal-Token", properties.getInternalApiToken());
        headers.set("X-Studio-Target-Cluster-Id", String.valueOf(clusterId));
        ResponseEntity<String> authenticated = restTemplate.exchange(
                "/internal/runtime/health", HttpMethod.GET, new HttpEntity<Void>(headers), String.class);
        assertThat(authenticated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authenticated.getHeaders().getFirst(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER))
                .isEqualTo(RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
        assertThat(authenticated.getBody()).contains("\"success\":true");
    }

    private static void prepareDatabase() {
        try {
            Files.createDirectories(TEST_DIRECTORY);
            Files.deleteIfExists(SQLITE_DATABASE);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to prepare Desktop Worker plane test database", ex);
        }
    }

    private static Long number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private static String normalizePath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static Path locateProjectRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("backend").resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("backend")
                    .resolve("studio-desktop-runtime")
                    .resolve("src")
                    .resolve("main")
                    .resolve("resources")
                    .resolve("schema-sqlite.sql"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate data-aggregation-studio project root");
    }
}
