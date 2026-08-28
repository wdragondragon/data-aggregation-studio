import type { StreamingMetricBucketView } from "@studio/api-sdk";

export type MetricDisplayRow = {
  metric: StreamingMetricBucketView;
  bucketLabel: string;
  idleBucketCount: number;
};

type InternalMetricDisplayRow = MetricDisplayRow & {
  lastMetric: StreamingMetricBucketView;
};

const metricCounterKeys: Array<keyof StreamingMetricBucketView> = [
  "recordsRead",
  "writeSucceedRecords",
  "writeFailedRecords",
  "dirtyRecords",
  "bytesRead",
  "batchCount",
  "retryCount",
  "rebalanceCount",
];

export function hasMetricRecords(metric: StreamingMetricBucketView): boolean {
  return Number(metric.recordsRead ?? 0) > 0;
}

function isIdleMetric(metric: StreamingMetricBucketView): boolean {
  return metricCounterKeys.every((key) => Number(metric[key] ?? 0) === 0);
}

function sameValue(left: unknown, right: unknown): boolean {
  return String(left ?? "") === String(right ?? "");
}

function sameIdleState(left: StreamingMetricBucketView, right: StreamingMetricBucketView): boolean {
  return sameValue(left.runId, right.runId)
    && sameValue(left.attemptId, right.attemptId)
    && sameValue(left.currentLag, right.currentLag)
    && sameValue(left.maxLag, right.maxLag)
    && sameValue(left.lastMessageAt, right.lastMessageAt)
    && sameValue(left.lastCheckpointAt, right.lastCheckpointAt);
}

function bucketMillis(value: unknown): number | null {
  if (value == null) return null;
  const timestamp = Date.parse(String(value));
  return Number.isNaN(timestamp) ? null : timestamp;
}

function areAdjacentMinutes(left: StreamingMetricBucketView, right: StreamingMetricBucketView): boolean {
  const leftMillis = bucketMillis(left.bucketStart);
  const rightMillis = bucketMillis(right.bucketStart);
  return leftMillis != null && rightMillis != null && Math.abs(leftMillis - rightMillis) === 60_000;
}

export function compactIdleMetrics(items: StreamingMetricBucketView[]): MetricDisplayRow[] {
  const rows: InternalMetricDisplayRow[] = [];
  for (const metric of items) {
    const previous = rows[rows.length - 1];
    const previousMetric = previous?.lastMetric;
    if (previous && previousMetric
      && isIdleMetric(metric)
      && isIdleMetric(previousMetric)
      && areAdjacentMinutes(metric, previousMetric)
      && sameIdleState(metric, previousMetric)) {
      previous.bucketLabel = `${String(metric.bucketStart)} ~ ${String(previous.metric.bucketStart)}`;
      previous.idleBucketCount += 1;
      previous.lastMetric = metric;
      continue;
    }
    rows.push({
      metric,
      bucketLabel: String(metric.bucketStart ?? ""),
      idleBucketCount: 1,
      lastMetric: metric,
    });
  }
  return rows.map(({ lastMetric: _lastMetric, ...row }) => row);
}
