package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.commons.element.Record;
import com.jdragon.aggregation.commons.element.StringColumn;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.core.plugin.Transformer;
import com.jdragon.aggregation.core.plugin.PluginType;
import com.jdragon.aggregation.core.transformer.TransformerExecution;
import com.jdragon.aggregation.core.transformer.TransformerRegistry;
import com.jdragon.aggregation.core.utils.TransformerUtil;
import com.jdragon.aggregation.pluginloader.ConfigParser;
import com.jdragon.aggregation.pluginloader.JarLoader;
import com.jdragon.aggregation.pluginloader.JarLoaderCenter;
import com.jdragon.aggregation.pluginloader.LoadUtil;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeResolvers;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.jdragon.aggregation.pluginloader.type.IPluginType;
import com.jdragon.studio.dto.enums.DataServiceParamPosition;
import com.jdragon.studio.dto.enums.DataServiceResponseType;
import com.jdragon.studio.dto.enums.DataServiceSourceType;
import com.jdragon.studio.dto.enums.DataServiceType;
import com.jdragon.studio.dto.model.DataServiceDefinitionView;
import com.jdragon.studio.dto.model.DataServicePublishParamView;
import com.jdragon.studio.dto.model.DataServiceRequestParamView;
import com.jdragon.studio.dto.model.DataServiceResponseParamView;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.TransformerBinding;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataServicePublishParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceRequestParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceResponseParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceSubscriptionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataServicePluginRuntimeSessionTest {

    private static final String SESSION_OBSERVER_TRANSFORMER = "dx_data_service_session_observer";
    private static final String LEASE_PROBE_TRANSFORMER = "lease_probe_transformer";
    private static final IPluginType TRANSFORMER_TYPE = () -> "transformer";
    private static final List<PluginRuntimeSession> OBSERVED_TRANSFORMER_SESSIONS =
            Collections.synchronizedList(new ArrayList<PluginRuntimeSession>());

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void registerSessionObserverTransformer() {
        TransformerRegistry.registTransformer(new SessionObserverTransformer());
    }

    @BeforeEach
    void clearObservedSessions() {
        OBSERVED_TRANSFORMER_SESSIONS.clear();
    }

    @AfterEach
    void resetPluginRuntime() {
        ConfigParser.clearCache();
        PluginRuntimeResolvers.reset();
        JarLoaderCenter.clearJarLoader();
        assertNull(PluginRuntimeSession.current());
    }

    @Test
    void dataServicePinsCountDataAndResponseTransformersToOneOperationSession() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(42L);
        when(dataSourceService.getInternal(42L)).thenReturn(datasource);

        DataServiceService service = dataService(dataSourceService);
        SessionObservingSqlExecutor sqlExecutor = new SessionObservingSqlExecutor();
        service.setDataDevelopmentSqlExecutor(sqlExecutor);

        Object result = ReflectionTestUtils.invokeMethod(service, "execute", dataServiceView(),
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(), Boolean.FALSE);

        assertNotNull(result);
        assertEquals(2, sqlExecutor.sessions.size());
        assertEquals(1, OBSERVED_TRANSFORMER_SESSIONS.size());
        assertNotNull(sqlExecutor.sessions.get(0));
        assertSame(sqlExecutor.sessions.get(0), sqlExecutor.sessions.get(1));
        assertSame(sqlExecutor.sessions.get(0), OBSERVED_TRANSFORMER_SESSIONS.get(0));
        assertNull(PluginRuntimeSession.current());
    }

    @Test
    void standaloneExternalResponseTransformerKeepsItsLeaseUntilEvaluateCompletes() throws Exception {
        Path pluginDirectory = createLeaseProbePlugin("single");
        ResolvedPlugin resolvedPlugin = new ResolvedPlugin(TRANSFORMER_TYPE, LEASE_PROBE_TRANSFORMER,
                pluginDirectory, "lease-probe-v1");
        PluginRuntimeResolvers.install((type, name) -> {
            if (!"transformer".equals(type.getName()) || !LEASE_PROBE_TRANSFORMER.equals(name)) {
                throw new IllegalArgumentException("Unexpected plugin coordinate: " + type.getName() + "/" + name);
            }
            return resolvedPlugin;
        });

        StudioTransformerExecutionSupport support = new StudioTransformerExecutionSupport(
                new StudioTransformerSupport(new ObjectMapper()));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("value", "before");

        List<Map<String, Object>> transformed = support.applyOnlineResponseTransformers(
                Collections.singletonList(row),
                Collections.singletonList(responseParam("value", LEASE_PROBE_TRANSFORMER)));

        assertEquals("true", transformed.get(0).get("value"));
        assertFalse(JarLoaderCenter.isDirectoryInUse(pluginDirectory));
        assertNull(PluginRuntimeSession.current());
    }

    @Test
    void reactivatedTransformerIdentityBuildsAgainstTheCurrentSessionLoader() throws Exception {
        Path v1Directory = createLeaseProbePlugin("v1");
        Path v2Directory = createLeaseProbePlugin("v2");
        ResolvedPlugin v1 = new ResolvedPlugin(TRANSFORMER_TYPE, LEASE_PROBE_TRANSFORMER,
                v1Directory, "lease-probe-v1");
        ResolvedPlugin v2 = new ResolvedPlugin(TRANSFORMER_TYPE, LEASE_PROBE_TRANSFORMER,
                v2Directory, "lease-probe-v2");
        AtomicReference<ResolvedPlugin> active = new AtomicReference<ResolvedPlugin>(v1);
        PluginRuntimeResolvers.install((type, name) -> active.get());

        assertTransformerUsesCurrentLease();
        active.set(v2);
        assertTransformerUsesCurrentLease();
        active.set(v1);
        // v1 was retired while v2 was active. A rollback must not reuse the
        // stale TransformerInfo whose URLClassLoader was already closed.
        assertTransformerUsesCurrentLease();
    }

    private void assertTransformerUsesCurrentLease() {
        PluginRuntimeSession session = PluginRuntimeSession.createDetached();
        try {
            ClassLoader transformerClassLoader = session.call(() -> {
                Configuration config = Configuration.newDefault();
                Map<String, Object> transformer = new LinkedHashMap<String, Object>();
                transformer.put("name", LEASE_PROBE_TRANSFORMER);
                Map<String, Object> parameter = new LinkedHashMap<String, Object>();
                parameter.put("columnIndex", Integer.valueOf(0));
                parameter.put("paras", Collections.emptyList());
                transformer.put("parameter", parameter);
                config.set("transformer", Collections.singletonList(transformer));
                List<TransformerExecution> executions = TransformerUtil.buildTransformerInfo(
                        config, Collections.emptyList());
                assertEquals(1, executions.size());
                return executions.get(0).getClassLoader();
            });
            JarLoader leasedLoader = session.call(
                    () -> LoadUtil.getJarLoader(PluginType.TRANSFORMER, LEASE_PROBE_TRANSFORMER));
            assertSame(leasedLoader, transformerClassLoader);
        } finally {
            session.close();
        }
    }

    private DataServiceService dataService(DataSourceService dataSourceService) {
        return new DataServiceService(
                mock(DataServiceDefinitionMapper.class),
                mock(DataServiceRequestParamMapper.class),
                mock(DataServiceResponseParamMapper.class),
                mock(DataServicePublishParamMapper.class),
                mock(DataServiceSubscriptionMapper.class),
                mock(DataServiceAccessLogMapper.class),
                mock(DataServiceAccessCounterMapper.class),
                dataSourceService,
                mock(DataModelService.class),
                mock(DatasourceTypeCapabilityService.class),
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class),
                mock(DataServiceResponseCacheService.class),
                new StudioTransformerSupport(new ObjectMapper()),
                mock(OpenServiceInvocationLogService.class));
    }

    private DataServiceDefinitionView dataServiceView() {
        DataServiceDefinitionView view = new DataServiceDefinitionView();
        view.setId(101L);
        view.setServiceType(DataServiceType.MODEL_PUBLISH);
        view.setResponseType(DataServiceResponseType.JSON);
        view.setSourceType(DataServiceSourceType.TABLE);
        view.setDatasourceId(42L);
        view.setModelPhysicalLocator("session_probe_table");
        view.setCacheEnabled(Boolean.FALSE);
        view.setRequestParams(Collections.singletonList(requestParam()));
        view.setPublishParams(Collections.singletonList(publishParam()));
        view.setResponseParams(Collections.singletonList(responseParam("value", SESSION_OBSERVER_TRANSFORMER)));
        return view;
    }

    private DataServiceRequestParamView requestParam() {
        DataServiceRequestParamView value = new DataServiceRequestParamView();
        value.setParamName("pageNum");
        value.setFieldName("value");
        return value;
    }

    private DataServicePublishParamView publishParam() {
        DataServicePublishParamView value = new DataServicePublishParamView();
        value.setFrontendParamName("pageNum");
        value.setBackendParamName("pageNum");
        value.setPosition(DataServiceParamPosition.QUERY);
        return value;
    }

    private DataServiceResponseParamView responseParam(String name, String transformerCode) {
        TransformerBinding transformer = new TransformerBinding();
        transformer.setTransformerCode(transformerCode);

        DataServiceResponseParamView value = new DataServiceResponseParamView();
        value.setEnabled(Boolean.TRUE);
        value.setParamName(name);
        value.setFieldName(name);
        value.setTransformers(Collections.singletonList(transformer));
        return value;
    }

    private Path createLeaseProbePlugin(String version) throws Exception {
        Path pluginDirectory = Files.createDirectories(temporaryDirectory.resolve("lease-probe-plugin-" + version));
        Path sourceDirectory = Files.createDirectories(temporaryDirectory.resolve("lease-probe-source-" + version));
        Path classesDirectory = Files.createDirectories(temporaryDirectory.resolve("lease-probe-classes-" + version));
        Path source = sourceDirectory.resolve("e2e").resolve("LeaseProbeTransformer.java");
        Files.createDirectories(source.getParent());

        String escapedDirectory = pluginDirectory.toAbsolutePath().normalize().toString()
                .replace("\\", "\\\\");
        Files.writeString(source,
                "package e2e;\n"
                        + "import com.jdragon.aggregation.commons.element.Record;\n"
                        + "import com.jdragon.aggregation.commons.element.StringColumn;\n"
                        + "import com.jdragon.aggregation.core.plugin.Transformer;\n"
                        + "import com.jdragon.aggregation.pluginloader.JarLoaderCenter;\n"
                        + "import java.nio.file.Path;\n"
                        + "public final class LeaseProbeTransformer extends Transformer {\n"
                        + "  public LeaseProbeTransformer() { setTransformerName(\"" + LEASE_PROBE_TRANSFORMER + "\"); }\n"
                        + "  @Override public Record evaluate(Record record, Object... paras) {\n"
                        + "    record.setColumn(0, new StringColumn(String.valueOf(JarLoaderCenter.isDirectoryInUse(Path.of(\""
                        + escapedDirectory + "\")))));\n"
                        + "    return record;\n"
                        + "  }\n"
                        + "}\n",
                StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK, not a JRE");
        int compilationResult = compiler.run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDirectory.toString(), source.toString());
        assertEquals(0, compilationResult, "Synthetic external transformer compilation failed");

        Path jar = pluginDirectory.resolve("lease-probe-transformer.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("e2e/LeaseProbeTransformer.class"));
            Files.copy(classesDirectory.resolve("e2e").resolve("LeaseProbeTransformer.class"), output);
            output.closeEntry();
        }
        Files.writeString(pluginDirectory.resolve("transformer.json"),
                "{\"name\":\"" + LEASE_PROBE_TRANSFORMER + "\",\"class\":\"e2e.LeaseProbeTransformer\"}",
                StandardCharsets.UTF_8);
        return pluginDirectory;
    }

    private static final class SessionObservingSqlExecutor extends DataDevelopmentSqlExecutor {
        private final List<PluginRuntimeSession> sessions = new ArrayList<PluginRuntimeSession>();
        private int executions;

        private SessionObservingSqlExecutor() {
            super(mock(EncryptionService.class), mock(DatasourceTypeCapabilityService.class));
        }

        @Override
        public SqlExecutionResultView executePreparedQuery(DataSourceDefinition datasource,
                                                           String sql,
                                                           List<Object> parameters,
                                                           Integer maxRows) {
            sessions.add(PluginRuntimeSession.current());
            SqlExecutionResultView result = new SqlExecutionResultView();
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            if (executions++ == 0) {
                row.put("total_count", Long.valueOf(1L));
            } else {
                row.put("value", "data");
            }
            result.setRows(Collections.singletonList(row));
            return result;
        }
    }

    private static final class SessionObserverTransformer extends Transformer {
        private SessionObserverTransformer() {
            setTransformerName(SESSION_OBSERVER_TRANSFORMER);
        }

        @Override
        public Record evaluate(Record record, Object... paras) {
            OBSERVED_TRANSFORMER_SESSIONS.add(PluginRuntimeSession.current());
            return record;
        }
    }
}
