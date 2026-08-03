package com.jdragon.studio.flink.service;

import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.pluginloader.JarLoaderCenter;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeResolvers;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.flink.connector.AggregationFlinkRuntimeRegistry;
import com.jdragon.studio.flink.connector.AggregationFlinkTableRuntime;
import com.jdragon.studio.flink.execution.FlinkExecutionClient;
import com.jdragon.studio.flink.execution.FlinkExecutionClientRouter;
import com.jdragon.studio.flink.execution.FlinkExecutionRequest;
import com.jdragon.studio.flink.execution.FlinkExecutionResult;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.HttpReaderOptionSecurityService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.RuntimeClusterSelectionService;
import org.apache.flink.table.api.DataTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlinkSqlExecutionServicePluginRevisionTest {
    private static final Pattern RUNTIME_TOKEN = Pattern.compile("'runtime\\.token' = '([^']+)'");

    @AfterEach
    void restorePluginRuntime() {
        PluginRuntimeResolvers.reset();
        JarLoaderCenter.clearJarLoader();
    }

    @Test
    void multiModelExecutionPinsOneRevisionAndNextExecutionSeesNewRevision(
            @TempDir Path tempDirectory) throws Exception {
        Path v1Directory = pluginDirectory(tempDirectory.resolve("v1"));
        Path v2Directory = pluginDirectory(tempDirectory.resolve("v2"));
        ResolvedPlugin v1 = new ResolvedPlugin(SourcePluginType.SOURCE, "mysql8",
                v1Directory, "release-v1");
        ResolvedPlugin v2 = new ResolvedPlugin(SourcePluginType.SOURCE, "mysql8",
                v2Directory, "release-v2");
        AtomicReference<ResolvedPlugin> active = new AtomicReference<ResolvedPlugin>(v1);
        AtomicInteger resolverCalls = new AtomicInteger();
        PluginRuntimeResolvers.install((pluginType, pluginName) -> {
            ResolvedPlugin selected = active.get();
            if (resolverCalls.incrementAndGet() == 1) {
                active.set(v2);
            }
            return selected;
        });

        String accessKeySentinel = "oss-access-key-must-not-leak";
        String secretKeySentinel = "oss-secret-key-must-not-leak";
        CapturingGatewayClient client = new CapturingGatewayClient(accessKeySentinel, secretKeySentinel);
        FlinkSqlExecutionService service = executionService(client, accessKeySentinel, secretKeySentinel);
        FlinkSqlExecuteRequest request = new FlinkSqlExecuteRequest();
        request.setRuntimeClusterId(9L);
        request.setModelIds(Arrays.asList(1L, 2L));
        request.setSql("SELECT * FROM m_1 JOIN m_2 ON m_1.id = m_2.id");

        service.execute(request);
        service.execute(request);

        assertEquals(Arrays.asList("release-v1", "release-v1"), client.identities.get(0));
        assertEquals(Arrays.asList("release-v2", "release-v2"), client.identities.get(1));
        assertEquals(2, resolverCalls.get(), "each task must resolve the shared coordinate only once");
        assertFalse(JarLoaderCenter.isDirectoryInUse(v1Directory));
        assertFalse(JarLoaderCenter.isDirectoryInUse(v2Directory));
    }

    private FlinkSqlExecutionService executionService(CapturingGatewayClient client,
                                                       String accessKeySentinel,
                                                       String secretKeySentinel) {
        DataModelDefinition firstModel = model(1L, 100L, "orders");
        DataModelDefinition secondModel = model(2L, 100L, "customers");
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(100L);
        datasource.setName("mysql-source");
        datasource.setTypeCode("mysql8");

        DataModelService dataModelService = mock(DataModelService.class);
        when(dataModelService.get(1L)).thenReturn(firstModel);
        when(dataModelService.get(2L)).thenReturn(secondModel);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        when(dataSourceService.getInternal(100L)).thenReturn(datasource);
        AggregationFlinkRuntimeBuilder runtimeBuilder = mock(AggregationFlinkRuntimeBuilder.class);
        when(runtimeBuilder.build(any(), any(), any())).thenAnswer(invocation ->
                runtime((DataModelDefinition) invocation.getArgument(1)));

        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getFlink().setExecutionMode("gateway");
        properties.getFlink().setRuntimeEndpoint("https://worker.example.test");
        properties.getObjectStorage().setAccessKey(accessKeySentinel);
        properties.getObjectStorage().setSecretKey(secretKeySentinel);
        ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
        when(projectAccess.requireCurrentProjectId()).thenReturn(7L);

        return new FlinkSqlExecutionService(
                dataModelService,
                dataSourceService,
                mock(HttpReaderOptionSecurityService.class),
                runtimeBuilder,
                new FlinkSqlGuard(),
                properties,
                new FlinkExecutionClientRouter(List.of(client)),
                projectAccess,
                mock(RuntimeClusterSelectionService.class));
    }

    private DataModelDefinition model(long id, long datasourceId, String name) {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(id);
        model.setDatasourceId(datasourceId);
        model.setName(name);
        model.setPhysicalLocator(name);
        return model;
    }

    private AggregationFlinkTableRuntime runtime(DataModelDefinition model) {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setDatasourceId(model.getDatasourceId());
        runtime.setModelId(model.getId());
        runtime.setPluginName("mysql8");
        runtime.setTableName(model.getName());
        runtime.setPhysicalLocator(model.getPhysicalLocator());
        runtime.setScanMode("bounded");
        runtime.setProducedDataType(DataTypes.ROW(DataTypes.FIELD("id", DataTypes.BIGINT())));
        runtime.setFieldNames(List.of("id"));
        return runtime;
    }

    private Path pluginDirectory(Path directory) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("plugin.json"), "{\"plugin\":{}}");
        Files.writeString(directory.resolve("plugin.jar"), "plugin");
        return directory;
    }

    private static final class CapturingGatewayClient implements FlinkExecutionClient {
        private final String accessKeySentinel;
        private final String secretKeySentinel;
        private final List<List<String>> identities = new ArrayList<List<String>>();

        private CapturingGatewayClient(String accessKeySentinel, String secretKeySentinel) {
            this.accessKeySentinel = accessKeySentinel;
            this.secretKeySentinel = secretKeySentinel;
        }

        @Override
        public String executionMode() {
            return "gateway";
        }

        @Override
        public FlinkExecutionResult execute(FlinkExecutionRequest request) {
            List<String> taskIdentities = new ArrayList<String>();
            for (String ddl : request.getCreateTableDdls()) {
                assertFalse(ddl.contains(accessKeySentinel));
                assertFalse(ddl.contains(secretKeySentinel));
                Matcher matcher = RUNTIME_TOKEN.matcher(ddl);
                assertTrue(matcher.find(), "gateway DDL must contain a capability token");
                try (AggregationFlinkRuntimeRegistry.PluginArtifactLease lease =
                             AggregationFlinkRuntimeRegistry.acquirePinnedPlugin(
                                     matcher.group(1), SourcePluginType.SOURCE, "mysql8")) {
                    taskIdentities.add(lease.getPlugin().getIdentity());
                }
            }
            identities.add(taskIdentities);
            return new FlinkExecutionResult();
        }
    }
}
