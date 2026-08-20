package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.RunMetricSummaryView;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.service.RunMetricSummaryMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunMetricSummaryMapperRegressionTest {

    private final RunMetricSummaryMapper mapper = new RunMetricSummaryMapper();

    @Test
    void shouldNormalizeFailedRunMetricsFromPayload() {
        RunMetricSummaryView summary = mapper.fromPayload(Map.of(
                "status", "FAILED",
                "summary", Map.of(
                        "collectedRecords", 2L,
                        "readSucceedRecords", 2L,
                        "writeSucceedRecords", 2L,
                        "failedRecords", 0L)));

        assertThat(summary.getSuccessRecords()).isZero();
        assertThat(summary.getWriteSucceedRecords()).isZero();
        assertThat(summary.getFailedRecords()).isEqualTo(2L);
    }

    @Test
    void shouldNormalizeHistoricalFailedEntityMetrics() {
        RunRecordEntity entity = new RunRecordEntity();
        entity.setStatus("FAILED");
        entity.setCollectedRecords(1L);
        entity.setSuccessRecords(1L);
        entity.setWriteSucceedRecords(1L);
        entity.setFailedRecords(0L);

        RunMetricSummaryView summary = mapper.fromEntity(entity);

        assertThat(summary.getSuccessRecords()).isZero();
        assertThat(summary.getWriteSucceedRecords()).isZero();
        assertThat(summary.getFailedRecords()).isEqualTo(1L);
    }
}
