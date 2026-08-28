import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { Buffer } from "node:buffer";
import { transform } from "esbuild";

const sourceUrl = new URL("../src/components/collection-task/streamingMetricsDisplay.ts", import.meta.url);
const source = await readFile(sourceUrl, "utf8");
const transformed = await transform(source, {
  format: "esm",
  loader: "ts",
  sourcemap: "inline",
  sourcefile: sourceUrl.pathname,
  target: "node20",
});
const display = await import(`data:text/javascript;base64,${Buffer.from(transformed.code).toString("base64")}`);

const idle = (minute, overrides = {}) => ({
  bucketStart: `2026-08-27T09:${String(minute).padStart(2, "0")}:00`,
  recordsRead: 0,
  writeSucceedRecords: 0,
  writeFailedRecords: 0,
  dirtyRecords: 0,
  bytesRead: 0,
  batchCount: 0,
  retryCount: 0,
  rebalanceCount: 0,
  runId: 7,
  attemptId: 8,
  currentLag: 3,
  maxLag: 3,
  lastMessageAt: null,
  lastCheckpointAt: null,
  ...overrides,
});

const threeIdle = [idle(49), idle(48), idle(47)];
const threeRows = display.compactIdleMetrics(threeIdle);
assert.equal(threeRows.length, 1);
assert.equal(threeRows[0].idleBucketCount, 3);
assert.equal(threeRows[0].bucketLabel, "2026-08-27T09:47:00 ~ 2026-08-27T09:49:00");

const sevenRows = display.compactIdleMetrics([49, 48, 47, 46, 45, 44, 43].map((minute) => idle(minute)));
assert.equal(sevenRows.length, 1);
assert.equal(sevenRows[0].idleBucketCount, 7);

const interrupted = display.compactIdleMetrics([idle(49), idle(48), idle(47, { recordsRead: 1 }), idle(46)]);
assert.equal(interrupted.length, 3);
assert.equal(interrupted[1].metric.recordsRead, 1);

assert.equal(display.compactIdleMetrics([idle(49), idle(47)]).length, 2);
assert.equal(display.compactIdleMetrics([idle(49), idle(48, { currentLag: 4 })]).length, 2);
assert.equal(display.compactIdleMetrics([idle(49), idle(48, { attemptId: 9 })]).length, 2);
assert.equal(display.hasMetricRecords(idle(49)), false);
assert.equal(display.hasMetricRecords(idle(49, { recordsRead: "2" })), true);
assert.equal(display.hasMetricRecords(idle(49, { recordsRead: -1 })), false);

const drawerUrl = new URL("../src/components/collection-task/StreamingTaskMonitorDrawer.vue", import.meta.url);
const drawer = await readFile(drawerUrl, "utf8");
assert.match(drawer, /onlyWithRecords/);
assert.match(drawer, /streamingLogChunkPreview/);
assert.match(drawer, /previewChunkLog/);

console.log("collection task streaming metrics tests passed");
