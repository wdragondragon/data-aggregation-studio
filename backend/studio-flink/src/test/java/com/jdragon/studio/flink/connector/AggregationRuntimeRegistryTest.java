package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AggregationRuntimeRegistryTest {

    @Test
    void resolvesRuntimeAsJsonFriendlyPayloadAndUpdatesAudit() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setDatasourceId(1L);
        runtime.setModelId(10L);
        runtime.setPluginName("mysql8");
        BaseDataSourceDTO dto = new BaseDataSourceDTO();
        dto.setHost("127.0.0.1");
        dto.setPassword("secret");
        runtime.setDataSourceDTO(dto);

        String ref = AggregationFlinkRuntimeRegistry.register(runtime, 300);
        try {
            AggregationFlinkTableRuntimePayload payload = AggregationFlinkRuntimeRegistry.resolvePayload(ref);
            AggregationFlinkTableRuntime resolved = payload.toRuntime();
            assertEquals("mysql8", resolved.getPluginName());
            assertEquals("secret", resolved.getDataSourceDTO().getPassword());

            resolved.setPushedFilters(Collections.singletonList("biz_date = '2026-07-05'"));
            resolved.addResolvedSourceSql("SELECT * FROM t WHERE biz_date = '2026-07-05'");
            AggregationFlinkRuntimeRegistry.updateAudit(ref, AggregationFlinkTableRuntimePayload.fromRuntime(resolved));

            AggregationFlinkTableRuntime updated = AggregationFlinkRuntimeRegistry.required(ref);
            assertEquals(Collections.singletonList("biz_date = '2026-07-05'"), updated.getPushedFilters());
            assertEquals(Collections.singletonList("SELECT * FROM t WHERE biz_date = '2026-07-05'"),
                    updated.getResolvedSourceSql());
        } finally {
            AggregationFlinkRuntimeRegistry.remove(ref);
        }
    }

    @Test
    void rejectsMissingRuntimeToken() {
        assertThrows(IllegalStateException.class, () -> AggregationFlinkRuntimeRegistry.required("missing"));
    }
}
