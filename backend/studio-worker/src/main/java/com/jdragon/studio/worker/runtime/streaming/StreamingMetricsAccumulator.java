package com.jdragon.studio.worker.runtime.streaming;

import com.jdragon.aggregation.core.statistics.communication.Communication;
import com.jdragon.aggregation.core.statistics.communication.CommunicationTool;
import com.jdragon.aggregation.core.streaming.job.StreamingMetricsSnapshot;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Converts cumulative source counters plus per-batch Writer counters into minute deltas. */
final class StreamingMetricsAccumulator {

    private final Object monitor = new Object();
    private final Map<String, Long> previousSourceCounters = new LinkedHashMap<String, Long>();
    private final Map<String, Long> pendingWriterCounters = new LinkedHashMap<String, Long>();
    private final Map<String, Long> bucketCounters = new LinkedHashMap<String, Long>();
    private StreamingMetricsSnapshot lastSnapshot = StreamingMetricsSnapshot.empty();
    private long pendingRetryCount;
    private LocalDateTime pendingCheckpointAt;
    private LocalDateTime bucketStart;
    private LocalDateTime bucketCheckpointAt;

    void onBatchRetry() {
        synchronized (monitor) {
            pendingRetryCount++;
        }
    }

    void onBatchFailed(com.jdragon.aggregation.core.streaming.job.StreamingBatch batch) {
        synchronized (monitor) {
            long failedRecords = batch == null || batch.getRecords() == null
                    ? 0L : batch.getRecords().size();
            addPending("writeFailedRecords", failedRecords);
        }
    }

    void onBatchCommitted(Communication communication, StreamingMetricsSnapshot snapshot) {
        synchronized (monitor) {
            addPending("writeSucceedRecords", communication == null ? 0L
                    : Math.max(0L, communication.getLongCounter(CommunicationTool.WRITE_RECEIVED_RECORDS)
                    - communication.getLongCounter(CommunicationTool.WRITE_FAILED_RECORDS)));
            addPending("writeFailedRecords", communication == null ? 0L
                    : Math.max(0L, communication.getLongCounter(CommunicationTool.WRITE_FAILED_RECORDS)));
            addPending("dirtyRecords", communication == null ? 0L
                    : Math.max(0L, communication.getLongCounter(CommunicationTool.TOTAL_DIRTY_RECORDS)));
            addPending("batchCount", 1L);
            pendingCheckpointAt = LocalDateTime.now();
        }
    }

    MetricDelta sample(StreamingMetricsSnapshot snapshot) {
        synchronized (monitor) {
            remember(snapshot);
            Map<String, Long> delta = new LinkedHashMap<String, Long>();
            addSourceDelta(delta, "recordsRead", "recordsRead");
            addSourceDelta(delta, "bytesRead", "bytesRead");
            addSourceDelta(delta, "batchCount", "batchesRead");
            addSourceDelta(delta, "dirtyRecords", "dirtyRecords");
            addSourceDelta(delta, "rebalanceCount", "rebalanceCount");

            // Kafka exposes batchesRead as the authoritative source-side count. For a
            // custom StreamingSource that omits it, retain the committed-batch fallback.
            if (!lastSnapshot.getCounters().containsKey("batchesRead")) {
                addDeltaValue(delta, "batchCount", pendingWriterCounters.get("batchCount"));
            }
            addDeltaValue(delta, "writeSucceedRecords", pendingWriterCounters.get("writeSucceedRecords"));
            addDeltaValue(delta, "writeFailedRecords", pendingWriterCounters.get("writeFailedRecords"));
            addDeltaValue(delta, "dirtyRecords", pendingWriterCounters.get("dirtyRecords"));
            addDeltaValue(delta, "retryCount", pendingRetryCount);

            Map<String, Long> gauges = new LinkedHashMap<String, Long>();
            gauges.put("currentLag", value(lastSnapshot.getGauges().get("lag")));
            gauges.put("maxLag", value(lastSnapshot.getGauges().get("maxLag")));
            Long lastMessage = lastSnapshot.getLastMessageTimestamp();
            LocalDateTime lastMessageAt = lastMessage == null || lastMessage.longValue() <= 0L
                    ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(lastMessage), ZoneId.systemDefault());
            LocalDateTime checkpointAt = pendingCheckpointAt;

            LocalDateTime currentBucket = LocalDateTime.now().withSecond(0).withNano(0);
            if (!currentBucket.equals(bucketStart)) {
                bucketStart = currentBucket;
                bucketCounters.clear();
                bucketCheckpointAt = null;
            }
            for (Map.Entry<String, Long> entry : delta.entrySet()) {
                bucketCounters.put(entry.getKey(), value(bucketCounters.get(entry.getKey())) + value(entry.getValue()));
            }
            if (checkpointAt != null) {
                bucketCheckpointAt = checkpointAt;
            }
            pendingWriterCounters.clear();
            pendingRetryCount = 0L;
            pendingCheckpointAt = null;
            return new MetricDelta(new LinkedHashMap<String, Long>(bucketCounters), gauges,
                    lastMessageAt, bucketCheckpointAt, bucketStart);
        }
    }

    MetricDelta flush() {
        return sample(lastSnapshot);
    }

    private void remember(StreamingMetricsSnapshot snapshot) {
        if (snapshot != null) {
            lastSnapshot = snapshot;
        }
    }

    private void addSourceDelta(Map<String, Long> target, String targetKey, String sourceKey) {
        long current = value(lastSnapshot.getCounters().get(sourceKey));
        long previous = value(previousSourceCounters.put(sourceKey, current));
        addDeltaValue(target, targetKey, Math.max(0L, current - previous));
    }

    private void addPending(String key, long value) {
        pendingWriterCounters.put(key, value(pendingWriterCounters.get(key)) + Math.max(0L, value));
    }

    private void addDeltaValue(Map<String, Long> target, String key, Long value) {
        if (value != null && value.longValue() > 0L) {
            target.put(key, value(target.get(key)) + value.longValue());
        }
    }

    private long value(Long value) {
        return value == null ? 0L : Math.max(0L, value.longValue());
    }

    static final class MetricDelta {
        private final Map<String, Long> counters;
        private final Map<String, Long> gauges;
        private final LocalDateTime lastMessageAt;
        private final LocalDateTime lastCheckpointAt;
        private final LocalDateTime bucketStart;

        MetricDelta(Map<String, Long> counters,
                    Map<String, Long> gauges,
                    LocalDateTime lastMessageAt,
                    LocalDateTime lastCheckpointAt,
                    LocalDateTime bucketStart) {
            this.counters = counters;
            this.gauges = gauges;
            this.lastMessageAt = lastMessageAt;
            this.lastCheckpointAt = lastCheckpointAt;
            this.bucketStart = bucketStart;
        }

        Map<String, Long> getCounters() { return counters; }
        Map<String, Long> getGauges() { return gauges; }
        LocalDateTime getLastMessageAt() { return lastMessageAt; }
        LocalDateTime getLastCheckpointAt() { return lastCheckpointAt; }
        LocalDateTime getBucketStart() { return bucketStart; }
    }
}
