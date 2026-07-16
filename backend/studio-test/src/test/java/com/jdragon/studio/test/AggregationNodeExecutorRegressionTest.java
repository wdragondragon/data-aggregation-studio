package com.jdragon.studio.test;

import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.core.enums.State;
import com.jdragon.aggregation.core.job.JobContainer;
import com.jdragon.aggregation.core.plugin.spi.reporter.JobPointReporter;
import com.jdragon.aggregation.core.statistics.communication.Communication;
import com.jdragon.aggregation.pluginloader.constant.SystemConstants;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.worker.runtime.AggregationNodeExecutor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AggregationNodeExecutorRegressionTest {

    @Test
    void shouldReturnFailedStatusWhenJobContainerFinishesWithFailedState() {
        AggregationNodeExecutor executor = new AggregationNodeExecutor() {
            @Override
            protected JobContainer createJobContainer(Map<String, Object> config) {
                return new StubJobContainer(State.FAILED, new IllegalStateException("writer failed"));
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

    private static class StubJobContainer extends JobContainer {

        private static final Path CORE_CONFIG = createCoreConfig();

        private final JobPointReporter reporter;

        private StubJobContainer(State state, Throwable throwable) {
            super(prepareConfiguration());
            Communication communication = new Communication();
            communication.setState(state);
            communication.setThrowable(throwable);
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
