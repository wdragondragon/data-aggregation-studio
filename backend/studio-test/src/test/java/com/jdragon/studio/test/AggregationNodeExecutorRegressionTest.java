package com.jdragon.studio.test;

import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.core.enums.State;
import com.jdragon.aggregation.core.job.JobContainer;
import com.jdragon.aggregation.core.plugin.spi.reporter.JobPointReporter;
import com.jdragon.aggregation.core.statistics.communication.Communication;
import com.jdragon.aggregation.core.statistics.communication.CommunicationTool;
import com.jdragon.aggregation.pluginloader.constant.SystemConstants;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.worker.runtime.AggregationNodeExecutor;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AggregationNodeExecutorRegressionTest {

    private static final String ORIGINAL_AGGREGATION_HOME = System.getProperty("aggregation.home");
    private static final String ORIGINAL_HOME = SystemConstants.HOME;
    private static final String ORIGINAL_PLUGIN_HOME = SystemConstants.PLUGIN_HOME;
    private static final String ORIGINAL_CORE_CONFIG = SystemConstants.CORE_CONFIG;

    @AfterEach
    void restoreAggregationRuntimeGlobals() {
        if (ORIGINAL_AGGREGATION_HOME == null) {
            System.clearProperty("aggregation.home");
        } else {
            System.setProperty("aggregation.home", ORIGINAL_AGGREGATION_HOME);
        }
        SystemConstants.HOME = ORIGINAL_HOME;
        SystemConstants.PLUGIN_HOME = ORIGINAL_PLUGIN_HOME;
        SystemConstants.CORE_CONFIG = ORIGINAL_CORE_CONFIG;
    }

    @Test
    void shouldReturnFailedStatusWhenJobContainerFinishesWithFailedState() {
        AggregationNodeExecutor executor = new AggregationNodeExecutor() {
            @Override
            protected JobContainer createJobContainer(Map<String, Object> config) {
                return new StubJobContainer(State.FAILED, new IllegalStateException("writer failed"), 2L, 2L);
            }
        };

        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeType(NodeType.COLLECTION_TASK);
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("reader", Collections.singletonMap("type", "mysql8"));
        config.put("writer", Collections.singletonMap("type", "mysql8"));
        node.setConfig(config);

        Map<String, Object> result = executor.execute(node, new LinkedHashMap<String, Object>());

        assertThat(result.get("status")).isEqualTo("FAILED");
        assertThat(result.get("jobState")).isEqualTo("FAILED");
        assertThat(String.valueOf(result.get("message"))).contains("failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat(summary.get("collectedRecords")).isEqualTo(2L);
        assertThat(summary.get("successRecords")).isEqualTo(0L);
        assertThat(summary.get("writeSucceedRecords")).isEqualTo(0L);
        assertThat(summary.get("failedRecords")).isEqualTo(2L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSeedInitialIncrementalCursorAndExposeUpdatedCursor() {
        AtomicReference<Object> seededValue = new AtomicReference<Object>();
        AggregationNodeExecutor executor = new AggregationNodeExecutor() {
            @Override
            protected JobContainer createJobContainer(Map<String, Object> config) {
                return new StubJobContainer(State.SUCCEEDED, null) {
                    @Override
                    public void start() {
                        seededValue.set(getJobPointReporter().get("pkValue", null));
                        getJobPointReporter().put("pkValue", Long.valueOf(108L));
                    }
                };
            }
        };

        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeType(NodeType.COLLECTION_TASK);
        Map<String, Object> readerConfig = new LinkedHashMap<String, Object>();
        readerConfig.put("sourceAlias", "src1");
        readerConfig.put("incrColumn", "id");
        readerConfig.put("incrModel", ">");
        readerConfig.put("pkValue", Long.valueOf(42L));
        Map<String, Object> reader = new LinkedHashMap<String, Object>();
        reader.put("type", "mysql8");
        reader.put("config", readerConfig);
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("reader", reader);
        config.put("writer", Collections.singletonMap("type", "mysql8"));
        node.setConfig(config);

        Map<String, Object> result = executor.execute(node, new LinkedHashMap<String, Object>());

        assertThat(seededValue.get()).isEqualTo(Long.valueOf(42L));
        Map<String, Object> cursors = (Map<String, Object>) result.get("incrementalCursors");
        Map<String, Object> cursor = (Map<String, Object>) cursors.get("src1");
        assertThat(cursor.get("incrColumn")).isEqualTo("id");
        assertThat(cursor.get("pkValue")).isEqualTo(Long.valueOf(108L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposePinnedPluginRevisionsInRunPayload() {
        AggregationNodeExecutor executor = new AggregationNodeExecutor() {
            @Override
            protected JobContainer createJobContainer(Map<String, Object> config) {
                return new StubJobContainer(State.SUCCEEDED, null) {
                    @Override
                    public void start() {
                        setRunContext("pluginRevisions", Map.of(
                                "reader/mysql8reader", "codex-e2e-v2-identity",
                                "writer/mysql8writer", "codex-e2e-v2-identity"));
                    }
                };
            }
        };

        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeType(NodeType.ETL_SINGLE);
        node.setConfig(Map.of(
                "reader", Map.of("type", "mysql8reader"),
                "writer", Map.of("type", "mysql8writer")));

        Map<String, Object> result = executor.execute(node, new LinkedHashMap<String, Object>());

        assertThat(result.get("pluginRevisions")).isEqualTo(Map.of(
                "reader/mysql8reader", "codex-e2e-v2-identity",
                "writer/mysql8writer", "codex-e2e-v2-identity"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPreservePinnedPluginRevisionsInRuntimeContextWhenJobStartThrows() {
        AggregationNodeExecutor executor = new AggregationNodeExecutor() {
            @Override
            protected JobContainer createJobContainer(Map<String, Object> config) {
                return new StubJobContainer(State.FAILED, new IllegalStateException("writer failed")) {
                    @Override
                    public void start() {
                        setRunContext("pluginRevisions", Map.of(
                                "reader/mysql8reader", "codex-e2e-v1-identity",
                                "writer/mysql8writer", "codex-e2e-v1-identity"));
                        throw new IllegalStateException("job start failed");
                    }
                };
            }
        };
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeType(NodeType.ETL_SINGLE);
        node.setConfig(Map.of(
                "reader", Map.of("type", "mysql8reader"),
                "writer", Map.of("type", "mysql8writer")));
        Map<String, Object> runtimeContext = new LinkedHashMap<String, Object>();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.execute(node, runtimeContext));

        assertThat(failure.getMessage()).isEqualTo("job start failed");
        assertThat((Map<String, String>) runtimeContext.get("pluginRevisions")).containsExactlyInAnyOrderEntriesOf(
                Map.of(
                        "reader/mysql8reader", "codex-e2e-v1-identity",
                        "writer/mysql8writer", "codex-e2e-v1-identity"));
    }

    @Test
    void shouldHydrateConsistencyResourceBindingsBeforeCreatingJobContainer() {
        CollectionTaskAssemblerService assembler = mock(CollectionTaskAssemblerService.class);
        Map<String, Object> stored = Map.of(
                "ruleId", "studio-consistency",
                "leftBinding", Map.of("datasourceId", 1L, "modelId", 10L),
                "rightBinding", Map.of("datasourceId", 2L, "modelId", 11L),
                "outputBinding", Map.of("datasourceId", 3L, "modelId", 20L),
                "matchKeys", Collections.singletonList("id"),
                "compareFields", Collections.singletonList("value"));
        Map<String, Object> hydrated = Map.of(
                "reader", Map.of("type", "consistency", "config", Map.of(
                        "ruleId", "studio-consistency",
                        "dataSources", Arrays.asList(
                                Map.of("datasourceType", "mysql8"),
                                Map.of("datasourceType", "mysql8")))),
                "writer", Map.of("type", "mysql8", "config", Collections.emptyMap()));
        when(assembler.assembleConsistency(stored)).thenReturn(hydrated);
        AtomicReference<Map<String, Object>> captured = new AtomicReference<Map<String, Object>>();
        DatasourceTypeCapabilityService capabilities = mock(DatasourceTypeCapabilityService.class);
        AggregationNodeExecutor executor = new AggregationNodeExecutor(
                capabilities, assembler) {
            @Override
            protected JobContainer createJobContainer(Map<String, Object> config) {
                captured.set(config);
                return new StubJobContainer(State.SUCCEEDED, null);
            }
        };
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeType(NodeType.CONSISTENCY);
        node.setConfig(stored);

        Map<String, Object> result = executor.execute(node, new LinkedHashMap<String, Object>());

        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(captured.get()).isEqualTo(hydrated);
        verify(assembler).assembleConsistency(stored);
        verify(capabilities, times(2)).ensureReadable("mysql8");
        verify(capabilities).ensureWritable("mysql8");
        verify(capabilities, never()).ensureReadable("consistency");
    }

    private static class StubJobContainer extends JobContainer {

        private static final Path CORE_CONFIG = createCoreConfig();

        private final JobPointReporter reporter;

        private StubJobContainer(State state, Throwable throwable) {
            this(state, throwable, 0L, 0L);
        }

        private StubJobContainer(State state, Throwable throwable, long readSucceedRecords, long writeReceivedRecords) {
            super(prepareConfiguration());
            Communication communication = new Communication();
            communication.setState(state);
            communication.setThrowable(throwable);
            communication.setLongCounter(CommunicationTool.READ_SUCCEED_RECORDS, readSucceedRecords);
            communication.setLongCounter(CommunicationTool.WRITE_RECEIVED_RECORDS, writeReceivedRecords);
            this.reporter = new JobPointReporter(Configuration.newDefault(), new LinkedHashMap<String, Object>());
            this.reporter.setTrackCommunication(communication);
        }

        @Override
        public void start() {
            // no-op
        }

        @Override
        public JobPointReporter getJobPointReporter() {
            return reporter;
        }

        private static Configuration prepareConfiguration() {
            String aggregationHome = CORE_CONFIG.getParent().getParent().toString();
            System.setProperty("aggregation.home", aggregationHome);
            SystemConstants.HOME = aggregationHome;
            SystemConstants.PLUGIN_HOME = CORE_CONFIG.getParent().getParent().resolve("plugin").toString();
            SystemConstants.CORE_CONFIG = CORE_CONFIG.toString();
            return Configuration.newDefault();
        }

        private static Path createCoreConfig() {
            try {
                Path aggregationHome = Files.createTempDirectory("studio-aggregation-node-executor-");
                Path config = Files.createDirectories(aggregationHome.resolve("conf")).resolve("core.json");
                Files.writeString(config, "{}");
                config.toFile().deleteOnExit();
                config.getParent().toFile().deleteOnExit();
                aggregationHome.toFile().deleteOnExit();
                return config;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to prepare aggregation core configuration", ex);
            }
        }
    }
}
