package com.jdragon.studio.worker.runtime.streaming;

import com.jdragon.aggregation.core.statistics.communication.Communication;
import com.jdragon.aggregation.core.statistics.communication.CommunicationTool;
import com.jdragon.aggregation.core.streaming.job.StreamingMetricsSnapshot;
import com.jdragon.aggregation.core.streaming.job.StreamingBatch;
import com.jdragon.aggregation.core.streaming.job.StreamingCheckpoint;
import com.jdragon.aggregation.commons.element.Record;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingMetricsAccumulatorTest {

    @Test
    void convertsCumulativeSourceCountersToIdempotentBucketTotals() {
        StreamingMetricsAccumulator accumulator = new StreamingMetricsAccumulator();
        StreamingMetricsAccumulator.MetricDelta first = accumulator.sample(snapshot(5L, 50L, 1L, 3L));
        StreamingMetricsAccumulator.MetricDelta second = accumulator.sample(snapshot(8L, 80L, 2L, 1L));
        StreamingMetricsAccumulator.MetricDelta repeated = accumulator.sample(snapshot(8L, 80L, 2L, 1L));

        assertThat(first.getCounters()).containsEntry("recordsRead", 5L)
                .containsEntry("bytesRead", 50L).containsEntry("batchCount", 1L);
        assertThat(second.getCounters()).containsEntry("recordsRead", 8L)
                .containsEntry("bytesRead", 80L).containsEntry("batchCount", 2L);
        assertThat(repeated.getCounters()).isEqualTo(second.getCounters());
        assertThat(second.getGauges()).containsEntry("currentLag", 1L)
                .containsEntry("maxLag", 3L);
    }

    @Test
    void addsPerBatchWriterAndRetryCountersWithoutDoubleCountingSource() {
        StreamingMetricsAccumulator accumulator = new StreamingMetricsAccumulator();
        accumulator.sample(snapshot(4L, 40L, 1L, 0L));
        Communication communication = new Communication();
        communication.setLongCounter(CommunicationTool.WRITE_RECEIVED_RECORDS, 10L);
        communication.setLongCounter(CommunicationTool.WRITE_FAILED_RECORDS, 1L);
        communication.setLongCounter(CommunicationTool.TOTAL_DIRTY_RECORDS, 2L);
        accumulator.onBatchRetry();
        accumulator.onBatchCommitted(communication, snapshot(4L, 40L, 1L, 0L));

        StreamingMetricsAccumulator.MetricDelta delta = accumulator.sample(snapshot(4L, 40L, 1L, 0L));
        assertThat(delta.getCounters()).containsEntry("recordsRead", 4L)
                .containsEntry("writeSucceedRecords", 9L)
                .containsEntry("writeFailedRecords", 1L)
                .containsEntry("dirtyRecords", 2L)
                .containsEntry("retryCount", 1L);
    }

    @Test
    void failedBatchAddsWriterFailuresOnceAfterRetriesAreExhausted() {
        StreamingMetricsAccumulator accumulator = new StreamingMetricsAccumulator();
        accumulator.sample(snapshot(0L, 0L, 0L, 0L));
        StreamingBatch batch = new StreamingBatch(
                List.<Record>of(org.mockito.Mockito.mock(Record.class), org.mockito.Mockito.mock(Record.class)),
                new StreamingCheckpoint("batch-1", Map.of()), 1_700_000_000_000L, 0L);
        accumulator.onBatchFailed(batch);

        StreamingMetricsAccumulator.MetricDelta delta = accumulator.sample(snapshot(0L, 0L, 0L, 0L));
        assertThat(delta.getCounters()).containsEntry("writeFailedRecords", 2L);
    }

    private StreamingMetricsSnapshot snapshot(long records, long bytes,
                                              long batches, long lag) {
        Map<String, Long> counters = new LinkedHashMap<String, Long>();
        counters.put("recordsRead", records);
        counters.put("bytesRead", bytes);
        counters.put("batchesRead", batches);
        Map<String, Long> gauges = new LinkedHashMap<String, Long>();
        gauges.put("lag", lag);
        gauges.put("maxLag", Math.max(lag, 3L));
        return new StreamingMetricsSnapshot(counters, gauges, 1_700_000_000_000L);
    }
}
