package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeClusterSecurityValidatorTest {

    @Test
    void shouldRejectDefaultEncryptionSecretRegardlessOfDeprecatedMode() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("non-default-internal-token");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-server");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRejectFormerApplicationYamlTokenDefault() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("api_token");
        properties.setEncryptionSecret("non-default-encryption-secret");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-worker");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldAcceptNonDefaultRuntimeSecrets() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("non-default-internal-token");
        properties.setEncryptionSecret("non-default-encryption-secret");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-server");

        assertDoesNotThrow(validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRejectWorkerWithoutExplicitRuntimeClusterCode() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("non-default-internal-token");
        properties.setEncryptionSecret("non-default-encryption-secret");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-worker");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRejectWorkerWithoutAggregationHome() {
        StudioPlatformProperties properties = validWorkerProperties();
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-worker");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRejectIncompleteWorkerAggregationHome(@TempDir Path tempDirectory) {
        StudioPlatformProperties properties = validWorkerProperties();
        properties.setAggregationHome(tempDirectory.toString());
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-worker");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldAcceptCompleteWorkerAggregationHome(@TempDir Path tempDirectory) throws IOException {
        createCompleteAggregationHome(tempDirectory);
        StudioPlatformProperties properties = validWorkerProperties();
        properties.setAggregationHome(tempDirectory.toString());
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-worker");

        assertDoesNotThrow(validator::validateRuntimeSecurity);
    }

    @Test
    void shouldAcceptLazyWorkerAndInitializeWritableCacheRoot(@TempDir Path tempDirectory) {
        Path runtimeHome = tempDirectory.resolve("lazy-runtime-home");
        StudioPlatformProperties properties = validLazyWorkerProperties();
        properties.setAggregationHome(runtimeHome.toString());
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-worker");

        assertDoesNotThrow(validator::validateRuntimeSecurity);
        org.junit.jupiter.api.Assertions.assertTrue(Files.isDirectory(runtimeHome.resolve("cache")));
    }

    @Test
    void shouldRejectLazyWorkerWithIncompleteObjectStorage(@TempDir Path tempDirectory) {
        StudioPlatformProperties properties = validLazyWorkerProperties();
        properties.setAggregationHome(tempDirectory.resolve("lazy-runtime-home").toString());
        properties.getObjectStorage().setSecretKey(null);
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-worker");

        assertThrows(IllegalStateException.class, validator::validateRuntimeSecurity);
    }

    @Test
    void shouldNotRequireRuntimeClusterCodeForServer() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("non-default-internal-token");
        properties.setEncryptionSecret("non-default-encryption-secret");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-server");

        assertDoesNotThrow(validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRejectWorkerIdentityOnServer() {
        StudioPlatformProperties properties = validServerProperties();
        properties.setRuntimeClusterCode("DEFAULT-LOCAL");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-server");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRejectAggregationHomeOnServer() {
        StudioPlatformProperties properties = validServerProperties();
        properties.setAggregationHome("C:/should-not-be-mounted-on-server");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-server");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRejectLegacyAggregationHomeSystemPropertyOnServer() {
        String previousValue = System.getProperty("aggregation.home");
        try {
            System.setProperty("aggregation.home", "C:/should-not-be-mounted-on-server");
            RuntimeClusterSecurityValidator validator = runtimeValidator(validServerProperties(), "studio-server");

            assertThrows(IllegalStateException.class,
                    validator::validateRuntimeSecurity);
        } finally {
            if (previousValue == null) {
                System.clearProperty("aggregation.home");
            } else {
                System.setProperty("aggregation.home", previousValue);
            }
        }
    }

    @Test
    void shouldRejectDefaultGatewaySecretWhenGatewayTrustIsEnabled() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("non-default-internal-token");
        properties.setEncryptionSecret("non-default-encryption-secret");
        properties.getGateway().setTrustEnabled(true);
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-server");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldAcceptNonDefaultGatewaySecretWhenGatewayTrustIsEnabled() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("non-default-internal-token");
        properties.setEncryptionSecret("non-default-encryption-secret");
        properties.getGateway().setTrustEnabled(true);
        properties.getGateway().setSharedSecret("non-default-gateway-shared-secret");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-server");

        assertDoesNotThrow(validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRequireSharedRuntimeSecretsForFlinkPlanningService() {
        RuntimeClusterSecurityValidator validator = runtimeValidator(
                new StudioPlatformProperties(), "studio-flink");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldAcceptFlinkPlanningServiceWithoutWorkerIdentity() {
        RuntimeClusterSecurityValidator validator = runtimeValidator(
                validServerProperties(), "studio-flink");

        assertDoesNotThrow(validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRejectWorkerIdentityOnFlinkPlanningService() {
        StudioPlatformProperties properties = validServerProperties();
        properties.setRuntimeClusterCode("DEFAULT-LOCAL");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-flink");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRejectAggregationHomeOnFlinkPlanningService() {
        StudioPlatformProperties properties = validServerProperties();
        properties.setAggregationHome("C:/should-not-be-mounted-on-flink-planner");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-flink");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRejectGatewayTrustOnFlinkPlanningService() {
        StudioPlatformProperties properties = validServerProperties();
        properties.getGateway().setTrustEnabled(true);
        properties.getGateway().setSharedSecret("non-default-gateway-shared-secret");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-flink");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldRejectGatewayTrustOnWorkerRuntime(@TempDir Path tempDirectory) throws IOException {
        createCompleteAggregationHome(tempDirectory);
        StudioPlatformProperties properties = validWorkerProperties();
        properties.setAggregationHome(tempDirectory.toString());
        properties.getGateway().setTrustEnabled(true);
        properties.getGateway().setSharedSecret("non-default-gateway-shared-secret");
        RuntimeClusterSecurityValidator validator = runtimeValidator(properties, "studio-worker");

        assertThrows(IllegalStateException.class,
                validator::validateRuntimeSecurity);
    }

    @Test
    void shouldIgnoreUnrelatedApplications() {
        RuntimeClusterSecurityValidator validator = runtimeValidator(
                new StudioPlatformProperties(), "some-unrelated-application");

        assertDoesNotThrow(validator::validateRuntimeSecurity);
    }

    private RuntimeClusterSecurityValidator runtimeValidator(StudioPlatformProperties properties,
                                                              String applicationName) {
        RuntimeClusterSecurityValidator validator = new RuntimeClusterSecurityValidator(properties);
        ReflectionTestUtils.setField(validator, "applicationName", applicationName);
        return validator;
    }

    private StudioPlatformProperties validServerProperties() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("non-default-internal-token");
        properties.setEncryptionSecret("non-default-encryption-secret");
        return properties;
    }

    private StudioPlatformProperties validWorkerProperties() {
        StudioPlatformProperties properties = validServerProperties();
        properties.setRuntimeClusterCode("DEFAULT-LOCAL");
        return properties;
    }

    private StudioPlatformProperties validLazyWorkerProperties() {
        StudioPlatformProperties properties = validWorkerProperties();
        properties.setRuntimeVersion("1.0_jdk17-SNAPSHOT");
        properties.getPluginRuntime().setMode("LAZY_OBJECT_STORAGE");
        properties.getObjectStorage().setProvider("OSS");
        properties.getObjectStorage().setEndpoint("https://oss.example.invalid");
        properties.getObjectStorage().setAccessKey("test-access-key");
        properties.getObjectStorage().setSecretKey("test-secret-key");
        properties.getObjectStorage().setBucket("test-plugin-bucket");
        return properties;
    }

    private void createCompleteAggregationHome(Path root) throws IOException {
        Files.createDirectories(root.resolve("conf"));
        Files.writeString(root.resolve("conf").resolve("core.json"), "{}");
        for (String pluginType : new String[]{"source", "reader", "writer", "transformer"}) {
            Files.createDirectories(root.resolve("plugin").resolve(pluginType));
        }
    }
}
