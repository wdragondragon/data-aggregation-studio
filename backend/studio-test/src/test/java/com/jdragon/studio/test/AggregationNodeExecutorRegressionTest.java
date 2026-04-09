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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private static class StubJobContainer extends JobContainer {

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
            String aggregationHome = "C:\\dev\\ideaProject\\DataAggregation\\package_all\\aggregation";
            System.setProperty("aggregation.home", aggregationHome);
            SystemConstants.HOME = aggregationHome;
            SystemConstants.PLUGIN_HOME = aggregationHome + "\\plugin";
            SystemConstants.CORE_CONFIG = aggregationHome + "\\conf\\core.json";
            return Configuration.newDefault();
        }
    }
}
