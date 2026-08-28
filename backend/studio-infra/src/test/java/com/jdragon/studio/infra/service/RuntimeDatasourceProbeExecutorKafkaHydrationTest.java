package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.ModelKind;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeDatasourceProbeExecutorKafkaHydrationTest {

    @Test
    void selectiveKafkaHydrationRetainsTopicModelKind() {
        AggregationSourceCapabilityProvider provider = mock(AggregationSourceCapabilityProvider.class);
        RuntimeDatasourceProbeExecutor executor = new RuntimeDatasourceProbeExecutor(
                provider, mock(DataDevelopmentSqlExecutor.class));
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(42L);
        datasource.setTypeCode("kafka");

        when(provider.hydrateDiscoveredModels(any(DataSourceDefinition.class), any(List.class)))
                .thenAnswer(invocation -> {
                    List<DataModelDefinition> candidates = invocation.getArgument(1);
                    DataModelDefinition candidate = candidates.get(0);
                    assertEquals(ModelKind.TOPIC, candidate.getModelKind());
                    return List.of(new AggregationSourceCapabilityProvider.HydrationResult(
                            candidate.getPhysicalLocator(), candidate, null));
                });

        var result = executor.hydrate(datasource, List.of("orders"));

        assertEquals(ModelKind.TOPIC, result.getItems().get(0).getDefinition().getModelKind());
        assertEquals("orders", result.getItems().get(0).getPhysicalLocator());
    }
}
