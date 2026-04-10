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
            .resolve("test-runtime");
    private static final Path SQLITE_DB = TEST_RUNTIME_DIR.resolve("studio-regression.db");
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
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> SQLITE_SCHEMA.toUri().toString());
        registry.add("studio.aggregation-home", () -> AGGREGATION_HOME.toAbsolutePath().normalize().toString());
        registry.add("studio.scan-plugins-on-startup", () -> "false");
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
        return "Bearer " + loginAndGetAdminToken();
    }

    protected String loginAndGetAdminToken() throws Exception {
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
        return tokenNode.asText();
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
