package com.jdragon.studio.test.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.service.DataModelIndexRebuildQueueService;
import com.jdragon.studio.infra.service.StudioInitializationService;
import com.jdragon.studio.server.bootstrap.StudioServerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = StudioServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class StudioApiRegressionTestSupport {

    private static final Path WORKSPACE_ROOT = locateWorkspaceRoot();
    private static final Path TEST_RUNTIME_DIR = WORKSPACE_ROOT
            .resolve("data-aggregation-studio")
            .resolve("backend")
            .resolve("studio-test")
            .resolve("target")
            .resolve("test-runtime")
            .resolve("run-" + Long.toUnsignedString(System.nanoTime()));
    private static final Path SQLITE_DB = TEST_RUNTIME_DIR.resolve("studio-regression.db");
    private static final Path SQLITE_SCHEMA = WORKSPACE_ROOT
            .resolve("data-aggregation-studio")
            .resolve("backend")
            .resolve("studio-desktop-runtime")
            .resolve("src")
            .resolve("main")
            .resolve("resources")
            .resolve("schema-sqlite.sql");
    private static final String JAVA_EXECUTABLE = locateJavaExecutable();
    private static final String TEST_CLASSPATH = System.getProperty("java.class.path");

    @Autowired
    protected MockMvc mockMvc;

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
        registry.add("studio.internal-api-token", () -> "studio-regression-internal-token-20260721");
        registry.add("studio.encryption-secret", () -> "studio-regression-encryption-secret-20260721");
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

    protected String adminAuthorizationHeader() throws Exception {
        return adminAuthorizationHeader(loginAsAdmin());
    }

    protected String loginAndGetAdminToken() throws Exception {
        return loginAsAdmin().path("data").path("token").asText();
    }

    protected JsonNode loginAsAdmin() throws Exception {
        Map<String, String> payload = new LinkedHashMap<String, String>();
        payload.put("username", StudioConstants.DEFAULT_ADMIN_USERNAME);
        payload.put("password", StudioConstants.DEFAULT_ADMIN_PASSWORD);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = readBody(result);
        JsonNode tokenNode = body.path("data").path("token");
        if (tokenNode.isMissingNode() || tokenNode.isNull() || tokenNode.asText().trim().isEmpty()) {
            throw new IllegalStateException("Admin login did not return a JWT token");
        }
        return body;
    }

    protected String adminAuthorizationHeader(JsonNode loginBody) {
        JsonNode tokenNode = loginBody.path("data").path("token");
        if (tokenNode.isMissingNode() || tokenNode.isNull() || tokenNode.asText().trim().isEmpty()) {
            throw new IllegalStateException("Admin login did not return a JWT token");
        }
        return "Bearer " + tokenNode.asText();
    }

    protected Long currentProjectId() throws Exception {
        return currentProjectId(loginAsAdmin());
    }

    protected Long currentProjectId(JsonNode loginBody) {
        JsonNode projectNode = loginBody.path("data").path("currentProjectId");
        if (projectNode.isMissingNode() || projectNode.isNull() || projectNode.asText().trim().isEmpty()) {
            throw new IllegalStateException("Admin login did not return currentProjectId");
        }
        return Long.valueOf(projectNode.asText());
    }

    protected Long createAndAuthorizeTestRuntimeCluster(String authorization, Long projectId) throws Exception {
        return createAndAuthorizeRuntimeCluster(authorization, projectId,
                "TEST_" + projectId, "Test Runtime Cluster " + projectId);
    }

    protected Long createAndAuthorizeDefaultLocalRuntimeCluster(String authorization, Long projectId) throws Exception {
        return createAndAuthorizeRuntimeCluster(authorization, projectId,
                "DEFAULT-LOCAL", "Default Local Runtime Cluster");
    }

    private Long createAndAuthorizeRuntimeCluster(String authorization,
                                                  Long projectId,
                                                  String code,
                                                  String name) throws Exception {
        Map<String, Object> clusterPayload = new LinkedHashMap<String, Object>();
        clusterPayload.put("code", code);
        clusterPayload.put("name", name);
        clusterPayload.put("enabled", Boolean.TRUE);
        clusterPayload.put("version", "test");

        MvcResult clusterResult = mockMvc.perform(post("/api/v1/runtime-clusters")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clusterPayload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode clusterBody = readBody(clusterResult);
        if (!clusterBody.path("success").asBoolean()) {
            throw new IllegalStateException("Failed to create test runtime cluster: " + clusterBody.path("message").asText());
        }
        Long runtimeClusterId = clusterBody.path("data").path("id").asLong();

        authorizeTestRuntimeCluster(authorization, projectId, runtimeClusterId);
        return runtimeClusterId;
    }

    protected void authorizeTestRuntimeCluster(String authorization,
                                               Long projectId,
                                               Long runtimeClusterId) throws Exception {
        Map<String, Object> authorizationPayload = new LinkedHashMap<String, Object>();
        authorizationPayload.put("projectId", projectId);
        authorizationPayload.put("runtimeClusterId", runtimeClusterId);
        authorizationPayload.put("enabled", Boolean.TRUE);
        authorizationPayload.put("preferred", Boolean.TRUE);
        authorizationPayload.put("allowManualOverride", Boolean.TRUE);
        MvcResult authorizationResult = mockMvc.perform(post("/api/v1/runtime-clusters/project-authorizations")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(authorizationPayload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode authorizationBody = readBody(authorizationResult);
        if (!authorizationBody.path("success").asBoolean()) {
            throw new IllegalStateException("Failed to authorize test runtime cluster: "
                    + authorizationBody.path("message").asText());
        }
    }

    protected JsonNode readBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected HttpHeaders authorizedJsonHeaders() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(loginAndGetAdminToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        return headers;
    }

    protected void awaitIndexQueueIdle() {
        boolean idle = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            idle = dataModelIndexRebuildQueueService.awaitIdle(java.time.Duration.ofSeconds(5));
            if (!idle) {
                continue;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for index rebuild queue", ex);
            }
            idle = dataModelIndexRebuildQueueService.awaitIdle(java.time.Duration.ofSeconds(1));
            if (idle) {
                return;
            }
        }
        if (!idle) {
            throw new IllegalStateException("Index rebuild queue did not become idle within timeout");
        }
    }

    private static void prepareRuntimeFiles() {
        try {
            Files.createDirectories(TEST_RUNTIME_DIR);
            Files.deleteIfExists(SQLITE_DB);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to prepare regression test runtime files", ex);
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
        throw new IllegalStateException("Unable to locate DataAggregation workspace root for regression tests");
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
        throw new IllegalStateException("Unable to locate java executable for regression tests");
    }
}
