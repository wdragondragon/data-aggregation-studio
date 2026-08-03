package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.pluginloader.ConfigParser;
import com.jdragon.aggregation.pluginloader.JarLoaderCenter;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeResolvers;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.RuntimeDatasourceHydrationResultView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import com.jdragon.studio.infra.service.script.DataDevelopmentExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginRuntimeBusinessSessionTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long RUNTIME_CLUSTER_ID = 9L;
    private static final Long DATASOURCE_ID = 12L;

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetPluginRuntime() {
        JavaDataDevelopmentExecutor.clearCompiledCache();
        ConfigParser.clearCache();
        PluginRuntimeResolvers.reset();
        JarLoaderCenter.clearJarLoader();
        assertNull(PluginRuntimeSession.current());
    }

    @Test
    void javaScriptPinsAllSqlCallsAndNextScriptUsesNewIdentity() throws Exception {
        VersionedRuntime runtime = versionedRuntime("java");
        ObservingSqlExecutor sqlExecutor = new ObservingSqlExecutor(runtime);
        DataSourceService dataSourceService = dataSourceService();
        DatasourceClusterBindingService bindingService = bindingService();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            JavaDataDevelopmentExecutor executor = javaExecutor(
                    dataSourceService, sqlExecutor, bindingService, classLoader);

            DataScriptExecutionResultView first = executor.execute(
                    context(101L, javaScriptWithTwoQueries()));

            assertTrue(first.getSuccess());
            assertEquals(Arrays.asList("java-v1", "java-v1"), sqlExecutor.identities);
            assertSame(sqlExecutor.sessions.get(0), sqlExecutor.sessions.get(1));
            PluginRuntimeSession firstSession = sqlExecutor.sessions.get(0);
            assertNull(PluginRuntimeSession.current());

            DataScriptExecutionResultView second = executor.execute(
                    context(102L, javaScriptWithTwoQueries()));

            assertTrue(second.getSuccess());
            assertEquals(Arrays.asList("java-v1", "java-v1", "java-v2", "java-v2"),
                    sqlExecutor.identities);
            assertSame(sqlExecutor.sessions.get(2), sqlExecutor.sessions.get(3));
            assertNotSame(firstSession, sqlExecutor.sessions.get(2));
            assertFalse(JarLoaderCenter.isDirectoryInUse(runtime.v1.getDirectory()));
            assertFalse(JarLoaderCenter.isDirectoryInUse(runtime.v2.getDirectory()));
            assertNull(PluginRuntimeSession.current());
        }
    }

    @Test
    void pythonScriptPinsBridgeThreadCallsAndNextScriptUsesNewIdentity() throws Exception {
        VersionedRuntime runtime = versionedRuntime("python");
        ObservingSqlExecutor sqlExecutor = new ObservingSqlExecutor(runtime);
        StudioPlatformProperties properties = pythonProperties();
        PythonDataDevelopmentExecutor executor = new PythonDataDevelopmentExecutor(
                properties,
                new ObjectMapper(),
                dataSourceService(),
                mock(DataModelService.class),
                sqlExecutor,
                bindingService());

        DataScriptExecutionResultView first = executor.execute(context(201L, "# executed by test bridge"));

        assertTrue(first.getSuccess(), first.getMessage());
        assertEquals(Arrays.asList("python-v1", "python-v1"), sqlExecutor.identities);
        assertSame(sqlExecutor.sessions.get(0), sqlExecutor.sessions.get(1));
        PluginRuntimeSession firstSession = sqlExecutor.sessions.get(0);
        assertNull(PluginRuntimeSession.current());

        DataScriptExecutionResultView second = executor.execute(context(202L, "# executed by test bridge"));

        assertTrue(second.getSuccess(), second.getMessage());
        assertEquals(Arrays.asList("python-v1", "python-v1", "python-v2", "python-v2"),
                sqlExecutor.identities);
        assertSame(sqlExecutor.sessions.get(2), sqlExecutor.sessions.get(3));
        assertNotSame(firstSession, sqlExecutor.sessions.get(2));
        assertFalse(JarLoaderCenter.isDirectoryInUse(runtime.v1.getDirectory()));
        assertFalse(JarLoaderCenter.isDirectoryInUse(runtime.v2.getDirectory()));
        assertNull(PluginRuntimeSession.current());
    }

    @Test
    void assetAutoHydratePinsDiscoveryAndHydrationToOneIdentity() throws Exception {
        VersionedRuntime runtime = versionedRuntime("asset");
        SessionObservingSourceProvider provider = new SessionObservingSourceProvider(runtime);
        RuntimeDatasourceProbeExecutor executor = new RuntimeDatasourceProbeExecutor(
                provider, mock(DataDevelopmentSqlExecutor.class));
        DataSourceDefinition datasource = datasource();

        RuntimeDatasourceHydrationResultView first = executor.hydrate(datasource, Collections.emptyList());

        assertEquals(1, first.getItems().size());
        assertTrue(Boolean.TRUE.equals(first.getItems().get(0).getSuccess()));
        assertEquals(Arrays.asList("asset-v1", "asset-v1"), provider.identities);
        assertSame(provider.sessions.get(0), provider.sessions.get(1));
        PluginRuntimeSession firstSession = provider.sessions.get(0);
        assertNull(PluginRuntimeSession.current());

        RuntimeDatasourceHydrationResultView second = executor.hydrate(datasource, Collections.emptyList());

        assertEquals(1, second.getItems().size());
        assertTrue(Boolean.TRUE.equals(second.getItems().get(0).getSuccess()));
        assertEquals(Arrays.asList("asset-v1", "asset-v1", "asset-v2", "asset-v2"),
                provider.identities);
        assertSame(provider.sessions.get(2), provider.sessions.get(3));
        assertNotSame(firstSession, provider.sessions.get(2));
        assertNull(PluginRuntimeSession.current());
    }

    private VersionedRuntime versionedRuntime(String prefix) throws Exception {
        Path v1Directory = Files.createDirectories(temporaryDirectory.resolve(prefix + "-v1"));
        Path v2Directory = Files.createDirectories(temporaryDirectory.resolve(prefix + "-v2"));
        VersionedRuntime runtime = new VersionedRuntime(
                new ResolvedPlugin(SourcePluginType.SOURCE, "mysql8", v1Directory, prefix + "-v1"),
                new ResolvedPlugin(SourcePluginType.SOURCE, "mysql8", v2Directory, prefix + "-v2"));
        PluginRuntimeResolvers.install((type, name) -> {
            assertEquals(SourcePluginType.SOURCE.getName(), type.getName());
            assertEquals("mysql8", name);
            return runtime.active.get();
        });
        return runtime;
    }

    private JavaDataDevelopmentExecutor javaExecutor(DataSourceService dataSourceService,
                                                       DataDevelopmentSqlExecutor sqlExecutor,
                                                       DatasourceClusterBindingService bindingService,
                                                       URLClassLoader classLoader) {
        ScriptEnvironmentRuntimeService environmentService = mock(ScriptEnvironmentRuntimeService.class);
        ScriptEnvironmentRuntimeService.RuntimeLease lease = mock(ScriptEnvironmentRuntimeService.RuntimeLease.class);
        ScriptEnvironmentRuntimeService.RuntimeClassLoaderHolder holder =
                mock(ScriptEnvironmentRuntimeService.RuntimeClassLoaderHolder.class);
        when(environmentService.resolveRuntime(null)).thenReturn(lease);
        when(lease.getRuntime()).thenReturn(holder);
        when(holder.getClassLoader()).thenReturn(classLoader);
        when(holder.getEnvironmentId()).thenReturn(1L);
        when(holder.getEnvironmentVersion()).thenReturn(1L);
        return new JavaDataDevelopmentExecutor(
                dataSourceService,
                mock(DataModelService.class),
                sqlExecutor,
                environmentService,
                bindingService);
    }

    private StudioPlatformProperties pythonProperties() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path javaExecutable = javaHome.resolve("bin").resolve("java.exe");
        if (!Files.isRegularFile(javaExecutable)) {
            javaExecutable = javaHome.resolve("bin").resolve("java");
        }
        assertTrue(Files.isRegularFile(javaExecutable), "Java executable is required for the Python bridge test");
        Path testClasses = Path.of(PluginRuntimeBusinessSessionTest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        properties.getPython().setExecutable(javaExecutable.toString());
        properties.getPython().setExecutableArgs(Arrays.asList(
                "-cp", testClasses.toString(), FakePythonProcess.class.getName()));
        properties.getPython().setExecutionTimeoutSeconds(30L);
        properties.getPython().setTempDir(temporaryDirectory.resolve("python-work").toString());
        return properties;
    }

    private DataSourceService dataSourceService() {
        DataSourceService service = mock(DataSourceService.class);
        when(service.getInternalForProject(PROJECT_ID, DATASOURCE_ID)).thenReturn(datasource());
        return service;
    }

    private DatasourceClusterBindingService bindingService() {
        DatasourceClusterBindingService service = mock(DatasourceClusterBindingService.class);
        when(service.filterApplicableDatasourceIds(
                PROJECT_ID, RUNTIME_CLUSTER_ID, Collections.singleton(DATASOURCE_ID)))
                .thenReturn(Collections.singleton(DATASOURCE_ID));
        return service;
    }

    private DataSourceDefinition datasource() {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(DATASOURCE_ID);
        datasource.setName("session-source");
        datasource.setTypeCode("mysql8");
        return datasource;
    }

    private DataDevelopmentExecutionContext context(Long scriptId, String content) {
        DataDevelopmentExecutionContext context = new DataDevelopmentExecutionContext();
        context.setScriptId(scriptId);
        context.setContent(content);
        context.setTenantId("tenant-a");
        context.setUsername("tester");
        context.setRuntimeContext(Map.of(
                "projectId", PROJECT_ID,
                "runtimeClusterId", RUNTIME_CLUSTER_ID));
        return context;
    }

    private String javaScriptWithTwoQueries() {
        return "import com.jdragon.studio.infra.script.java.JavaDataScript;\n"
                + "import com.jdragon.studio.infra.script.java.JavaDataScriptContext;\n"
                + "import com.jdragon.studio.infra.script.java.JavaDataScriptResult;\n"
                + "public class PluginSessionScript implements JavaDataScript {\n"
                + "  public JavaDataScriptResult execute(JavaDataScriptContext context) {\n"
                + "    context.getServices().executeSql(Long.valueOf(12L), \"select 1\", Integer.valueOf(20));\n"
                + "    context.getServices().executeSql(Long.valueOf(12L), \"select 2\", Integer.valueOf(20));\n"
                + "    return new JavaDataScriptResult();\n"
                + "  }\n"
                + "}\n";
    }

    private static final class VersionedRuntime {
        private final ResolvedPlugin v1;
        private final ResolvedPlugin v2;
        private final AtomicReference<ResolvedPlugin> active;

        private VersionedRuntime(ResolvedPlugin v1, ResolvedPlugin v2) {
            this.v1 = v1;
            this.v2 = v2;
            this.active = new AtomicReference<ResolvedPlugin>(v1);
        }

        private void activateV2() {
            active.set(v2);
        }
    }

    private static final class ObservingSqlExecutor extends DataDevelopmentSqlExecutor {
        private final VersionedRuntime runtime;
        private final AtomicBoolean switched = new AtomicBoolean(false);
        private final List<PluginRuntimeSession> sessions = new ArrayList<PluginRuntimeSession>();
        private final List<String> identities = new ArrayList<String>();

        private ObservingSqlExecutor(VersionedRuntime runtime) {
            super(mock(EncryptionService.class), mock(DatasourceTypeCapabilityService.class));
            this.runtime = runtime;
        }

        @Override
        public SqlExecutionResultView executeSql(DataSourceDefinition datasource,
                                                 String scriptContent,
                                                 Integer maxRows) {
            PluginRuntimeSession session = PluginRuntimeSession.current();
            assertNotNull(session);
            sessions.add(session);
            identities.add(session.resolve(SourcePluginType.SOURCE, "mysql8").getIdentity());
            if (switched.compareAndSet(false, true)) {
                runtime.activateV2();
            }
            return new SqlExecutionResultView();
        }
    }

    private static final class SessionObservingSourceProvider extends AggregationSourceCapabilityProvider {
        private final VersionedRuntime runtime;
        private final AtomicBoolean switched = new AtomicBoolean(false);
        private final List<PluginRuntimeSession> sessions = new ArrayList<PluginRuntimeSession>();
        private final List<String> identities = new ArrayList<String>();

        private SessionObservingSourceProvider(VersionedRuntime runtime) {
            super(new StudioPlatformProperties(),
                    mock(EncryptionService.class),
                    mock(BusinessMetaModelMetadataService.class));
            this.runtime = runtime;
        }

        @Override
        public ModelDiscoveryResult discoverModels(DataSourceDefinition definition,
                                                   String keyword,
                                                   Integer pageNo,
                                                   Integer pageSize) {
            observe();
            if (switched.compareAndSet(false, true)) {
                runtime.activateV2();
            }
            DataModelDefinition model = new DataModelDefinition();
            model.setDatasourceId(definition.getId());
            model.setName("orders");
            model.setPhysicalLocator("orders");
            ModelDiscoveryResult result = new ModelDiscoveryResult();
            result.getModels().add(model);
            return result;
        }

        @Override
        public List<HydrationResult> hydrateDiscoveredModels(
                DataSourceDefinition definition,
                List<DataModelDefinition> definitionModels) {
            observe();
            List<HydrationResult> results = new ArrayList<HydrationResult>();
            for (DataModelDefinition model : definitionModels) {
                results.add(new HydrationResult(model.getPhysicalLocator(), model, null));
            }
            return results;
        }

        private void observe() {
            PluginRuntimeSession session = PluginRuntimeSession.current();
            assertNotNull(session);
            sessions.add(session);
            identities.add(session.resolve(SourcePluginType.SOURCE, "mysql8").getIdentity());
        }
    }

    public static final class FakePythonProcess {
        private static final Pattern BASE_URL = Pattern.compile("\\\"baseUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        private static final Pattern TOKEN = Pattern.compile("\\\"token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

        private FakePythonProcess() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length < 4) {
                throw new IllegalArgumentException("Expected runner, user script, context and result paths");
            }
            Path contextPath = Path.of(args[args.length - 2]);
            Path resultPath = Path.of(args[args.length - 1]);
            String context = Files.readString(contextPath, StandardCharsets.UTF_8);
            String baseUrl = capture(BASE_URL, context);
            String token = capture(TOKEN, context);
            String requestBody = "{\"action\":\"execute_sql\",\"payload\":{"
                    + "\"datasourceId\":12,\"sql\":\"select 1\",\"maxRows\":20}}";
            HttpClient client = HttpClient.newHttpClient();
            for (int index = 0; index < 2; index++) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/invoke"))
                        .header("Content-Type", "application/json")
                        .header("X-Studio-Python-Token", token)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200 || !response.body().contains("\"success\":true")) {
                    throw new IllegalStateException("Bridge invocation failed: " + response.statusCode());
                }
            }
            Files.writeString(resultPath,
                    "{\"success\":true,\"status\":\"SUCCESS\",\"message\":\"ok\",\"resultJson\":{}}",
                    StandardCharsets.UTF_8);
        }

        private static String capture(Pattern pattern, String value) {
            Matcher matcher = pattern.matcher(value);
            if (!matcher.find()) {
                throw new IllegalArgumentException("Python bridge context is incomplete");
            }
            return matcher.group(1);
        }
    }
}
