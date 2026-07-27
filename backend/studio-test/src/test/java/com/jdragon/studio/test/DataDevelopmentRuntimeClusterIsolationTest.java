package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.script.python.PythonBridgeConnectionInfo;
import com.jdragon.studio.infra.script.python.PythonExecutionServiceBridge;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;
import com.jdragon.studio.infra.service.JavaDataDevelopmentExecutor;
import com.jdragon.studio.infra.service.ScriptEnvironmentRuntimeService;
import com.jdragon.studio.infra.service.script.DataDevelopmentExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataDevelopmentRuntimeClusterIsolationTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long RUNTIME_CLUSTER_ID = 9L;

    @AfterEach
    void clearCompiledScripts() {
        JavaDataDevelopmentExecutor.clearCompiledCache();
    }

    @Test
    void javaExecutionShouldExposeOnlyDatasourcesApplicableToRuntimeCluster() throws Exception {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        DataDevelopmentSqlExecutor sqlExecutor = mock(DataDevelopmentSqlExecutor.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        DataSourceDefinition clusterDatasource = datasource(11L, "cluster-a");
        DataSourceDefinition otherDatasource = datasource(12L, "cluster-b");
        when(dataSourceService.listForProject(PROJECT_ID)).thenReturn(Arrays.asList(clusterDatasource, otherDatasource));
        when(bindingService.filterApplicableDatasourceIds(
                eq(PROJECT_ID), eq(RUNTIME_CLUSTER_ID), anyCollection()))
                .thenReturn(new LinkedHashSet<Long>(Collections.singleton(11L)));

        try (URLClassLoader classLoader = testClassLoader()) {
            JavaDataDevelopmentExecutor executor = javaExecutor(
                    dataSourceService, dataModelService, sqlExecutor, bindingService, classLoader);
            DataDevelopmentExecutionContext context = context(101L, javaListDatasourceScript());

            DataScriptExecutionResultView result = executor.execute(context);

            assertThat(result.getSuccess()).isTrue();
            assertThat(result.getResultJson())
                    .containsEntry("count", 1)
                    .containsEntry("firstId", 11L);
            verify(bindingService).filterApplicableDatasourceIds(
                    eq(PROJECT_ID), eq(RUNTIME_CLUSTER_ID), anyCollection());
        }
    }

    @Test
    void javaExecutionShouldRejectOtherClusterDatasourceBeforeSqlExecution() throws Exception {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        DataDevelopmentSqlExecutor sqlExecutor = mock(DataDevelopmentSqlExecutor.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        when(dataSourceService.getInternalForProject(PROJECT_ID, 12L)).thenReturn(datasource(12L, "cluster-b"));
        when(bindingService.filterApplicableDatasourceIds(
                PROJECT_ID, RUNTIME_CLUSTER_ID, Collections.singleton(12L)))
                .thenReturn(Collections.emptySet());

        try (URLClassLoader classLoader = testClassLoader()) {
            JavaDataDevelopmentExecutor executor = javaExecutor(
                    dataSourceService, dataModelService, sqlExecutor, bindingService, classLoader);
            DataDevelopmentExecutionContext context = context(102L, javaExecuteSqlScript());

            DataScriptExecutionResultView result = executor.execute(context);

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getMessage())
                    .isEqualTo("Datasource is not applicable to the selected runtime cluster");
            verify(sqlExecutor, never()).executeSql(
                    org.mockito.ArgumentMatchers.any(), anyString(), anyInt());
        }
    }

    @Test
    void pythonBridgeShouldApplyTheSameRuntimeClusterIsolation() throws Exception {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        DataDevelopmentSqlExecutor sqlExecutor = mock(DataDevelopmentSqlExecutor.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        DataSourceDefinition clusterDatasource = datasource(11L, "cluster-a");
        DataSourceDefinition otherDatasource = datasource(12L, "cluster-b");
        when(dataSourceService.listForProject(PROJECT_ID)).thenReturn(Arrays.asList(clusterDatasource, otherDatasource));
        when(dataSourceService.getInternalForProject(PROJECT_ID, 12L)).thenReturn(otherDatasource);
        when(bindingService.filterApplicableDatasourceIds(
                eq(PROJECT_ID), eq(RUNTIME_CLUSTER_ID), anyCollection()))
                .thenAnswer(invocation -> applicableDatasourceIds(invocation.getArgument(2)));

        ObjectMapper objectMapper = new ObjectMapper();
        com.jdragon.studio.infra.script.java.DefaultJavaDataScriptServices services =
                new com.jdragon.studio.infra.script.java.DefaultJavaDataScriptServices(
                        dataSourceService,
                        dataModelService,
                        sqlExecutor,
                        bindingService,
                        PROJECT_ID,
                        RUNTIME_CLUSTER_ID);
        try (PythonExecutionServiceBridge bridge = new PythonExecutionServiceBridge(objectMapper, services)) {
            PythonBridgeConnectionInfo connection = bridge.buildConnectionInfo();

            JsonNode listResponse = invokeBridge(objectMapper, connection,
                    Map.of("action", "list_datasources", "payload", Collections.emptyMap()));
            assertThat(listResponse.path("success").asBoolean()).isTrue();
            assertThat(listResponse.path("data").size()).isEqualTo(1);
            assertThat(listResponse.path("data").get(0).path("id").asLong()).isEqualTo(11L);

            JsonNode sqlResponse = invokeBridge(objectMapper, connection,
                    Map.of("action", "execute_sql", "payload", Map.of(
                            "datasourceId", 12L,
                            "sql", "select 1",
                            "maxRows", 20)));
            assertThat(sqlResponse.path("success").asBoolean()).isFalse();
            assertThat(sqlResponse.path("error").asText())
                    .isEqualTo("Datasource is not applicable to the selected runtime cluster");
            verify(sqlExecutor, never()).executeSql(
                    org.mockito.ArgumentMatchers.any(), anyString(), anyInt());
        }
    }

    @Test
    void javaExecutionShouldFailClosedWhenRuntimeClusterIsMissing() throws Exception {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        try (URLClassLoader classLoader = testClassLoader()) {
            JavaDataDevelopmentExecutor executor = javaExecutor(
                    dataSourceService,
                    mock(DataModelService.class),
                    mock(DataDevelopmentSqlExecutor.class),
                    bindingService,
                    classLoader);
            DataDevelopmentExecutionContext context = context(103L, javaListDatasourceScript());
            context.setRuntimeContext(Map.of("projectId", PROJECT_ID));

            DataScriptExecutionResultView result = executor.execute(context);

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getMessage())
                    .isEqualTo("Runtime cluster is required for data script execution");
            verify(dataSourceService, never()).listForProject(PROJECT_ID);
            verify(bindingService, never()).filterApplicableDatasourceIds(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyCollection());
        }
    }

    @Test
    void pythonBridgeShouldRejectDatasourceOutsideExecutionProject() throws Exception {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        DataDevelopmentSqlExecutor sqlExecutor = mock(DataDevelopmentSqlExecutor.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        when(dataSourceService.getInternalForProject(PROJECT_ID, 88L))
                .thenThrow(new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: 88"));

        ObjectMapper objectMapper = new ObjectMapper();
        com.jdragon.studio.infra.script.java.DefaultJavaDataScriptServices services =
                new com.jdragon.studio.infra.script.java.DefaultJavaDataScriptServices(
                        dataSourceService, dataModelService, sqlExecutor, bindingService,
                        PROJECT_ID, RUNTIME_CLUSTER_ID);
        try (PythonExecutionServiceBridge bridge = new PythonExecutionServiceBridge(objectMapper, services)) {
            JsonNode response = invokeBridge(objectMapper, bridge.buildConnectionInfo(),
                    Map.of("action", "get_datasource", "payload", Map.of("datasourceId", 88L)));

            assertThat(response.path("success").asBoolean()).isFalse();
            assertThat(response.path("error").asText()).isEqualTo("Datasource not found: 88");
            verify(bindingService, never()).filterApplicableDatasourceIds(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyCollection());
        }
    }

    private JavaDataDevelopmentExecutor javaExecutor(DataSourceService dataSourceService,
                                                      DataModelService dataModelService,
                                                      DataDevelopmentSqlExecutor sqlExecutor,
                                                      DatasourceClusterBindingService bindingService,
                                                      URLClassLoader classLoader) {
        ScriptEnvironmentRuntimeService environmentService = mock(ScriptEnvironmentRuntimeService.class);
        ScriptEnvironmentRuntimeService.RuntimeLease lease = mock(ScriptEnvironmentRuntimeService.RuntimeLease.class);
        ScriptEnvironmentRuntimeService.RuntimeClassLoaderHolder runtime =
                mock(ScriptEnvironmentRuntimeService.RuntimeClassLoaderHolder.class);
        when(environmentService.resolveRuntime(null)).thenReturn(lease);
        when(lease.getRuntime()).thenReturn(runtime);
        when(runtime.getClassLoader()).thenReturn(classLoader);
        when(runtime.getEnvironmentId()).thenReturn(1L);
        when(runtime.getEnvironmentVersion()).thenReturn(1L);
        return new JavaDataDevelopmentExecutor(
                dataSourceService,
                dataModelService,
                sqlExecutor,
                environmentService,
                bindingService);
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

    private URLClassLoader testClassLoader() {
        return new URLClassLoader(new URL[0], getClass().getClassLoader());
    }

    private LinkedHashSet<Long> applicableDatasourceIds(Collection<Long> datasourceIds) {
        if (datasourceIds != null && datasourceIds.contains(11L)) {
            return new LinkedHashSet<Long>(Collections.singleton(11L));
        }
        return new LinkedHashSet<Long>();
    }

    private JsonNode invokeBridge(ObjectMapper objectMapper,
                                  PythonBridgeConnectionInfo connection,
                                  Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(connection.getBaseUrl() + "/invoke"))
                .header("Content-Type", "application/json")
                .header("X-Studio-Python-Token", connection.getToken())
                .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private DataSourceDefinition datasource(Long id, String name) {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(id);
        datasource.setName(name);
        return datasource;
    }

    private String javaListDatasourceScript() {
        return "import com.jdragon.studio.infra.script.java.JavaDataScript;\n"
                + "import com.jdragon.studio.infra.script.java.JavaDataScriptContext;\n"
                + "import com.jdragon.studio.infra.script.java.JavaDataScriptResult;\n"
                + "import com.jdragon.studio.dto.model.DataSourceDefinition;\n"
                + "public class RuntimeClusterListScript implements JavaDataScript {\n"
                + "  public JavaDataScriptResult execute(JavaDataScriptContext context) {\n"
                + "    java.util.List<DataSourceDefinition> sources = context.getServices().listDatasources();\n"
                + "    java.util.Map<String, Object> output = new java.util.LinkedHashMap<String, Object>();\n"
                + "    output.put(\"count\", Integer.valueOf(sources.size()));\n"
                + "    DataSourceDefinition first = (DataSourceDefinition) sources.get(0);\n"
                + "    output.put(\"firstId\", first.getId());\n"
                + "    JavaDataScriptResult result = new JavaDataScriptResult();\n"
                + "    result.setResultJson(output);\n"
                + "    return result;\n"
                + "  }\n"
                + "}\n";
    }

    private String javaExecuteSqlScript() {
        return "import com.jdragon.studio.infra.script.java.JavaDataScript;\n"
                + "import com.jdragon.studio.infra.script.java.JavaDataScriptContext;\n"
                + "import com.jdragon.studio.infra.script.java.JavaDataScriptResult;\n"
                + "public class RuntimeClusterSqlScript implements JavaDataScript {\n"
                + "  public JavaDataScriptResult execute(JavaDataScriptContext context) {\n"
                + "    context.getServices().executeSql(Long.valueOf(12L), \"select 1\", Integer.valueOf(20));\n"
                + "    return new JavaDataScriptResult();\n"
                + "  }\n"
                + "}\n";
    }
}
