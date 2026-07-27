package com.jdragon.studio.server.bootstrap;

import com.jdragon.studio.core.spi.ModelDiscoveryProvider;
import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.core.spi.SourceCapabilityProvider;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.AssistantScriptSkillExecutionService;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.DataIngestionService;
import com.jdragon.studio.infra.service.DataIngestionExecutionSupport;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DataServiceService;
import com.jdragon.studio.infra.service.FlinkQuestionSqlDataDevelopmentExecutor;
import com.jdragon.studio.infra.service.JavaDataDevelopmentExecutor;
import com.jdragon.studio.infra.service.PythonDataDevelopmentExecutor;
import com.jdragon.studio.infra.service.QualityTaskExecutionPlanService;
import com.jdragon.studio.infra.service.QualityTaskExecutionService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeExecutor;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeRouter;
import com.jdragon.studio.infra.service.ScriptEnvironmentArtifactLoader;
import com.jdragon.studio.infra.service.ScriptEnvironmentRuntimeService;
import com.jdragon.studio.infra.service.StudioTransformerExecutionSupport;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import com.jdragon.studio.infra.service.script.DataDevelopmentScriptExecutor;
import com.jdragon.studio.server.web.service.AssistantScriptRuntimeRouter;
import com.jdragon.studio.server.web.service.ScriptEnvironmentHintRuntimeRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = StudioServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.config.import=",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"
        })
class StudioServerPluginlessStartupTest {
    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path TEST_RUNTIME_DIR = PROJECT_ROOT.resolve("backend")
            .resolve("studio-server")
            .resolve("target")
            .resolve("pluginless-startup-test");
    private static final Path SQLITE_DB = TEST_RUNTIME_DIR.resolve("studio.db");
    private static final Path SQLITE_SCHEMA = PROJECT_ROOT.resolve("backend")
            .resolve("studio-desktop-runtime")
            .resolve("src")
            .resolve("main")
            .resolve("resources")
            .resolve("schema-sqlite.sql");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private StudioPlatformProperties properties;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        prepareDatabase();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + normalizePath(SQLITE_DB));
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.datasource.username", () -> "");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "PRAGMA busy_timeout=30000");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> SQLITE_SCHEMA.toUri().toString());
        registry.add("spring.quartz.auto-startup", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        registry.add("studio.internal-api-token", () -> "pluginless-test-internal-token-20260721");
        registry.add("studio.encryption-secret", () -> "pluginless-test-encryption-secret-20260721");
        registry.add("studio.scan-plugins-on-startup", () -> "false");
        registry.add("studio.alert.enabled", () -> "false");
    }

    @Test
    void serverStartsWithoutPluginDirectoryOrPluginExecutionBeans() {
        assertThat(properties.getAggregationHome()).isBlank();
        assertThat(applicationContext.getBeansOfType(AggregationSourceCapabilityProvider.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(RuntimeDatasourceProbeExecutor.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SourceCapabilityProvider.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ModelDiscoveryProvider.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(DataDevelopmentScriptExecutor.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(NodeExecutor.class)).isEmpty();
        Map<String, Object> localExecutionBeans = new LinkedHashMap<String, Object>();
        localExecutionBeans.putAll(applicationContext.getBeansOfType(DataDevelopmentSqlExecutor.class));
        localExecutionBeans.putAll(applicationContext.getBeansOfType(DataIngestionExecutionSupport.class));
        localExecutionBeans.putAll(applicationContext.getBeansOfType(StudioTransformerExecutionSupport.class));
        localExecutionBeans.putAll(applicationContext.getBeansOfType(JavaDataDevelopmentExecutor.class));
        localExecutionBeans.putAll(applicationContext.getBeansOfType(PythonDataDevelopmentExecutor.class));
        localExecutionBeans.putAll(applicationContext.getBeansOfType(FlinkQuestionSqlDataDevelopmentExecutor.class));
        localExecutionBeans.putAll(applicationContext.getBeansOfType(QualityTaskExecutionService.class));
        localExecutionBeans.putAll(applicationContext.getBeansOfType(AssistantScriptSkillExecutionService.class));
        localExecutionBeans.putAll(applicationContext.getBeansOfType(ScriptEnvironmentArtifactLoader.class));
        localExecutionBeans.putAll(applicationContext.getBeansOfType(ScriptEnvironmentRuntimeService.class));
        assertThat(localExecutionBeans).isEmpty();
        ClassLoader serverClassLoader = applicationContext.getClassLoader();
        assertThat(ClassUtils.isPresent(
                "com.jdragon.studio.worker.bootstrap.StudioWorkerApplication", serverClassLoader)).isFalse();
        assertThat(ClassUtils.isPresent(
                "com.jdragon.studio.worker.runtime.DataDevelopmentNodeExecutor", serverClassLoader)).isFalse();
        assertThat(ClassUtils.isPresent(
                "com.jdragon.studio.flink.service.FlinkSqlExecutionService", serverClassLoader)).isFalse();
        assertThat(ClassUtils.isPresent(
                "com.jdragon.aggregation.core.job.JobContainer", serverClassLoader)).isFalse();
        assertThat(ClassUtils.isPresent(
                "com.jdragon.aggregation.pluginloader.LoadUtil", serverClassLoader)).isFalse();
        assertThat(ClassUtils.isPresent(
                "com.jdragon.aggregation.datasource.file.s3.minio.MinioSourcePlugin", serverClassLoader)).isFalse();
        assertThat(applicationContext.getBeansOfType(AssistantScriptRuntimeRouter.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ScriptEnvironmentHintRuntimeRouter.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(RuntimeDatasourceProbeRouter.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(DataSourceService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(DataModelService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(QualityTaskExecutionPlanService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(QualityTaskService.class)).hasSize(1);

        // The control-plane services retain their metadata APIs but must not quietly cache an executor.
        DataIngestionService ingestionService = applicationContext.getBean(DataIngestionService.class);
        DataServiceService dataServiceService = applicationContext.getBean(DataServiceService.class);
        assertThat(ReflectionTestUtils.getField(ingestionService, "executionSupport")).isNull();
        assertThat(ReflectionTestUtils.getField(dataServiceService, "sqlExecutor")).isNull();
        assertThat(ReflectionTestUtils.getField(dataServiceService, "transformerExecutionSupport")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pluginlessServerCanBuildCollectionRuntimeOptionMaps() throws Exception {
        Class<?> mergerType = Class.forName(
                "com.jdragon.studio.infra.service.CollectionTaskRuntimeOptionMerger");
        java.lang.reflect.Constructor<?> constructor = mergerType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object merger = constructor.newInstance();
        Method merge = mergerType.getDeclaredMethod("merge", Map.class, Map.class,
                String.class, Iterable.class);
        merge.setAccessible(true);

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("connect", new LinkedHashMap<String, Object>());
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("test.param", "value");
        options.put("connect.host", "must-not-override");

        merge.invoke(merger, config, options, "reader", Collections.singleton("connect"));

        assertThat(config).containsKey("test");
        assertThat((Map<String, Object>) config.get("test")).containsEntry("param", "value");
        assertThat((Map<String, Object>) config.get("connect")).doesNotContainKey("host");
    }

    private static void prepareDatabase() {
        try {
            Files.createDirectories(TEST_RUNTIME_DIR);
            Files.deleteIfExists(SQLITE_DB);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to prepare pluginless startup database", ex);
        }
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
