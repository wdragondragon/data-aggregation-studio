package com.jdragon.studio.infra.service.execution;

import com.jdragon.studio.dto.model.DataSourceDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AggregationModelMetadataSupportTest {

    @Test
    void kafkaModelMetadataKeepsOnlyTheModelLocator() {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setTypeCode("kafka");
        Map<String, Object> datasourceMetadata = new LinkedHashMap<>();
        datasourceMetadata.put("bootstrap.servers", "broker:9092");
        datasourceMetadata.put("topic", "legacy-topic");
        datasourceMetadata.put("consumerGroup", "legacy-group");
        datasourceMetadata.put("tag", "legacy-tag");

        Map<String, Object> metadata = AggregationModelMetadataSupport.buildQueueMetadata(
                datasource, datasourceMetadata, "orders");

        assertThat(metadata).containsEntry("sourceType", "kafka")
                .containsEntry("physicalName", "orders")
                .doesNotContainKeys("topic", "queue", "queueName", "brokers", "consumerGroup", "tag");
    }
}
