package com.jdragon.studio.test.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.service.DataModelIndexRebuildQueueService;
import com.jdragon.studio.infra.service.StudioInitializationService;
import com.jdragon.studio.server.bootstrap.StudioServerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@SpringBootTest(classes = StudioServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class StudioHttpIntegrationTestSupport {

    private static final Path WORKSPACE_ROOT = locateWorkspaceRoot();
    private static final Path TEST_RUNTIME_DIR = WORKSPACE_ROOT
            .resolve("data-aggregation-studio")
            .resolve("backend")
            .resolve("studio-test")
            .resolve("target")
            .resolve("http-test-runtime")
            .resolve("run-" + Long.toUnsignedString(System.nanoTime()));
    private static final Path SQLITE_DB = TEST_RUNTIME_DIR.resolve("studio-http-integration.db");
    private static final Path SQLITE_SCHEMA = WORKSPACE_ROOT
            .resolve("data-aggregation-studio")
            .resolve("backend")
            .resolve("studio-desktop-runtime")
            .resolve("src")
            .resolve("main")
            .resolve("resources")
            .resolve("schema-sqlite.sql");
    private static final Path AGGREGATION_HOME = WORKSPACE_ROOT.resolve("package_all").resolve("aggregation");
    private static final String JAVA_EXECUTABLE = locateJavaExecutable();
    private static final String TEST_CLASSPATH = System.getProperty("java.class.path");

    protected final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected StudioInitializationService studioInitializationService;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected DataModelIndexRebuildQueueService dataModelIndexRebuildQueueService;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        prepareRuntimeFiles();
        registry.add("server.port", () -> "0");
        registry.add("spring.quartz.auto-startup", () -> "false");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + normalizeSqlitePath(SQLITE_DB));
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.datasource.username", () -> "");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "PRAGMA busy_timeout=30000");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> SQLITE_SCHEMA.toUri().toString());
        registry.add("studio.aggregation-home", () -> AGGREGATION_HOME.toAbsolutePath().normalize().toString());
        registry.add("studio.scan-plugins-on-startup", () -> "false");
        registry.add("studio.alert.enabled", () -> "false");
        registry.add("studio.python.executable", () -> JAVA_EXECUTABLE);
        registry.add("studio.python.executable-args[0]", () -> "-cp");
        registry.add("studio.python.executable-args[1]", () -> TEST_CLASSPATH);
        registry.add("studio.python.executable-args[2]", () -> FakePythonInterpreter.class.getName());
        registry.add("studio.python.execution-timeout-seconds", () -> "30");
        registry.add("studio.python.temp-dir", () -> TEST_RUNTIME_DIR.resolve("python").toAbsolutePath().normalize().toString());
    }

    @BeforeEach
    void resetStudioData() {
        awaitIndexQueueIdle();
        studioInitializationService.initialize(true);
        awaitIndexQueueIdle();
    }

    protected URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    protected JsonNode loginAsAdminHttp() throws Exception {
        Map<String, String> payload = new LinkedHashMap<String, String>();
        payload.put("username", StudioConstants.DEFAULT_ADMIN_USERNAME);
        payload.put("password", StudioConstants.DEFAULT_ADMIN_PASSWORD);
        JsonNode body = requireSuccess(postJson("/api/v1/auth/login", null, null, payload));
        JsonNode tokenNode = body.path("data").path("token");
        if (tokenNode.isMissingNode() || tokenNode.isNull() || tokenNode.asText().trim().isEmpty()) {
            throw new IllegalStateException("Admin login did not return a JWT token");
        }
        return body;
    }

    protected String bearer(JsonNode loginBody) {
        JsonNode tokenNode = loginBody.path("data").path("token");
        if (tokenNode.isMissingNode() || tokenNode.isNull() || tokenNode.asText().trim().isEmpty()) {
            throw new IllegalStateException("Admin login did not return a JWT token");
        }
        return "Bearer " + tokenNode.asText();
    }

    protected Long currentProjectId(JsonNode loginBody) {
        JsonNode projectNode = loginBody.path("data").path("currentProjectId");
        if (projectNode.isMissingNode() || projectNode.isNull() || projectNode.asText().trim().isEmpty()) {
            throw new IllegalStateException("Admin login did not return currentProjectId");
        }
        return Long.valueOf(projectNode.asText());
    }

    protected HttpResponse<String> get(String path, String authorization, Long projectId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .header("Accept", "application/json");
        addAuthHeaders(builder, authorization, projectId);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> postJson(String path, String authorization, Long projectId, Object payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        addAuthHeaders(builder, authorization, projectId);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> postXml(String path,
                                           String xml,
                                           Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(xml))
                .header("Content-Type", "text/xml;charset=UTF-8")
                .header("Accept", "text/xml");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected JsonNode requireSuccess(HttpResponse<String> response) throws Exception {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AssertionError("Expected 2xx HTTP status but got " + response.statusCode() + ": " + response.body());
        }
        JsonNode body = objectMapper.readTree(response.body());
        if (!body.path("success").asBoolean(false)) {
            throw new AssertionError("Expected success response but got: " + response.body());
        }
        return body;
    }

    protected void awaitIndexQueueIdle() {
        boolean idle = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            idle = dataModelIndexRebuildQueueService.awaitIdle(Duration.ofSeconds(5));
            if (!idle) {
                continue;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for index rebuild queue", ex);
            }
            idle = dataModelIndexRebuildQueueService.awaitIdle(Duration.ofSeconds(1));
            if (idle) {
                return;
            }
        }
        if (!idle) {
            throw new IllegalStateException("Index rebuild queue did not become idle within timeout");
        }
    }

    private void addAuthHeaders(HttpRequest.Builder builder, String authorization, Long projectId) {
        if (authorization != null && !authorization.trim().isEmpty()) {
            builder.header("Authorization", authorization);
        }
        if (projectId != null) {
            builder.header("X-Project-Id", String.valueOf(projectId));
        }
    }

    private static void prepareRuntimeFiles() {
        try {
            Files.createDirectories(TEST_RUNTIME_DIR);
            Files.deleteIfExists(SQLITE_DB);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to prepare HTTP integration test runtime files", ex);
        }
    }

    private static String normalizeSqlitePath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static Path locateWorkspaceRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("data-aggregation-studio"))
                    && Files.isDirectory(current.resolve("package_all").resolve("aggregation"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate DataAggregation workspace root for integration tests");
    }

    private static String locateJavaExecutable() {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path windows = javaHome.resolve("bin").resolve("java.exe");
        if (Files.exists(windows)) {
            return windows.toAbsolutePath().normalize().toString();
        }
        Path unix = javaHome.resolve("bin").resolve("java");
        if (Files.exists(unix)) {
            return unix.toAbsolutePath().normalize().toString();
        }
        throw new IllegalStateException("Unable to locate java executable for integration tests");
    }
}
