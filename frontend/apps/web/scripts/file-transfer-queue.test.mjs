import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";

const center = await readFile(new URL("../src/components/file-transfer/FileTransferCenterPane.vue", import.meta.url), "utf8");
const runs = await readFile(new URL("../src/components/file-transfer/FileTransferRunsPane.vue", import.meta.url), "utf8");
const browserPanel = await readFile(new URL("../src/components/file-transfer/FileTransferBrowserPanel.vue", import.meta.url), "utf8");
const unstructuredManagement = await readFile(new URL("../src/views/UnstructuredManagementView.vue", import.meta.url), "utf8");
const apiClient = await readFile(new URL("../../../packages/api-sdk/src/client.ts", import.meta.url), "utf8");
const queueSource = await readFile(new URL("../src/utils/fileTransferQueue.ts", import.meta.url), "utf8");
const eventSource = await readFile(new URL("../src/utils/fileTransferEvents.ts", import.meta.url), "utf8");
const queueModuleSource = ts.transpileModule(queueSource, {
  compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 },
}).outputText;
const queueModule = await import(`data:text/javascript;base64,${Buffer.from(queueModuleSource).toString("base64")}`);

assert.match(center, /pageSize:\s*1000/, "queue must page through run items at the backend maximum page size");
assert.match(center, /items\.length\s*>=\s*page\.total/, "queue item pagination must continue to the reported total");
assert.match(center, /mapWithConcurrency\(runs,\s*4,/, "queue item loading must have bounded concurrency");
assert.match(center, /initialQueueLoadPending/, "initial SSE snapshot must coalesce with the initial queue load");
assert.match(center, /queueLoading\.value\)\s*queueReloadRequested\s*=\s*true/,
  "queue events received during a snapshot load must schedule one merged reload");
assert.doesNotMatch(runs, /runsInitialized/, "run-list SSE snapshots must not be ignored after initial load");
assert.match(runs, /loading\.value\)\s*runsReloadRequested\s*=\s*true/,
  "run-list events received during a snapshot load must schedule one merged reload");
assert.match(apiClient, /triggerType\?:\s*string/, "run list client must expose the triggerType query");
assert.match(apiClient, /statusGroup\?:\s*"ACTIVE"\s*\|\s*"TERMINAL"/, "run list client must expose bounded status groups");
assert.doesNotMatch(center + runs, /setInterval\s*\(/, "file transfer views must not poll");
assert.match(unstructuredManagement, /@update:datasource-id="selectSource"/,
  "the browser datasource selector must update the management page selection");
assert.match(unstructuredManagement, /async function selectSource\(id\?: EntityId\)/,
  "clearing the browser datasource selector must be supported");
assert.match(browserPanel, /placeholder="文件数据源"[\s\S]*?clearable[\s\S]*?@update:model-value="emit\('update:datasourceId', \$event\)"/,
  "the browser datasource selector must expose its supported clear action");
assert.match(unstructuredManagement, /browserRequestSequence/,
  "stale browser responses must be ignored after a datasource switch");
assert.match(center, /side\.initialPathPending\s*=\s*true[\s\S]*?page\.initialPath/,
  "the transfer center must apply the remote pwd only when a datasource is first selected");
assert.match(unstructuredManagement, /initialPathPending\s*=\s*true[\s\S]*?page\.initialPath/,
  "unstructured management must apply the remote pwd only when a datasource is first selected");
assert.doesNotMatch(unstructuredManagement, /setInterval\s*\(/,
  "unstructured management must not poll");
assert.match(unstructuredManagement, /createDownloadTicket\s*\(/,
  "unstructured management must create a short-lived ticket before downloading");
assert.match(unstructuredManagement, /resolveStudioApiBaseUrl\("\/unstructured-management\/download\/native"\)/,
  "the browser must own the native download request");
assert.doesNotMatch(unstructuredManagement, /createObjectURL|saveBlob|requestBlob/,
  "unstructured management must not buffer downloads in a browser Blob");
assert.match(apiClient, /url:\s*"\/unstructured-management\/download"[\s\S]*?timeout:\s*0/,
  "the compatibility single-file Blob API must not retain the default request timeout");
assert.match(eventSource, /line\.startsWith\("id:"\)/,
  "the SSE parser must read business event IDs");
assert.match(eventSource, /"Last-Event-ID": lastEventId/,
  "reconnect requests must carry the last successfully handled event ID");
assert.match(eventSource, /compareEventIds\(event\.id, lastEventId\) <= 0/,
  "duplicate and stale events must be discarded before list listeners run");
assert.match(eventSource, /for \(const listener of listeners\) listener\(payload\);[\s\S]*?lastEventId = event\.id/,
  "the local cursor must advance only after listeners successfully handle the event");
assert.match(eventSource, /normalizeNumericEventId/,
  "snowflake event IDs must be compared as strings without JavaScript number coercion");
assert.match(eventSource, /currentScope !== lastEventScope[\s\S]*?lastEventId = null/,
  "switching tenant or project must reset the browser connection cursor");
assert.doesNotMatch(eventSource, /setInterval\s*\(/,
  "the SSE implementation must not introduce polling");
assert.match(center + runs, /REBUILDING_CHECKSUM/,
  "queue and run detail views must distinguish checksum reconstruction from target transfer");
assert.match(center + runs, /resumeCheckedBytes/,
  "checksum reconstruction must expose its own validation progress");
assert.match(center + runs, /verificationBytes/,
  "target checksum verification must expose its own validation progress");
assert.match(center + runs, /isTargetChecksumVerifying/,
  "queue and run detail progress bars must switch to target verification progress");
assert.match(center + runs, /目标校验/,
  "target checksum progress must be labeled independently from transferred bytes");
assert.match(center + runs, /校验速度/,
  "checksum reconstruction speed must not be labeled as transfer speed");
assert.match(center + runs, /if \(isChecksumRebuilding\(item\)\) return item\.transferredBytes/,
  "checksum reconstruction must keep confirmed transfer progress unchanged");

const activeRuns = Array.from({ length: 201 }, (_, index) => ({
  id: `active-${index}`,
  status: "RUNNING",
  createdAt: `2026-08-10T10:${String(index).padStart(3, "0")}`,
}));
const terminalRuns = Array.from({ length: 50 }, (_, index) => ({
  id: `terminal-${index}`,
  status: "SUCCESS",
  createdAt: `2026-08-09T10:${String(index).padStart(3, "0")}`,
}));
const calls = [];
const loadedRuns = await queueModule.loadBoundedManualRuns(async (params) => {
  calls.push(params);
  const values = params.statusGroup === "ACTIVE" ? activeRuns : terminalRuns;
  const offset = (params.pageNo - 1) * params.pageSize;
  return { items: values.slice(offset, offset + params.pageSize), total: values.length };
});

assert.equal(loadedRuns.length, 211, "queue must retain every active run and only ten terminal runs");
assert.equal(calls.filter((call) => call.statusGroup === "TERMINAL").length, 1,
  "terminal history must require exactly one bounded request");
assert.deepEqual(calls.filter((call) => call.statusGroup === "ACTIVE").map((call) => call.pageNo), [1, 2],
  "active runs may paginate without scanning terminal history");
assert.ok(calls.every((call) => call.triggerType === "MANUAL" && call.statusGroup),
  "every queue request must be server-filtered");

const transitionedRun = { id: "transitioned", status: "RUNNING", createdAt: "2026-08-10T10:00:00" };
const transitionedResult = queueModule.selectQueueRuns([
  transitionedRun,
  { ...transitionedRun, status: "SUCCESS" },
]);
assert.equal(transitionedResult.length, 1,
  "a run transitioning while active and terminal pages load must not be duplicated");
assert.equal(transitionedResult[0].status, "SUCCESS",
  "the later terminal snapshot must replace the stale active snapshot");

let activeWorkers = 0;
let peakWorkers = 0;
await queueModule.mapWithConcurrency(Array.from({ length: 12 }, (_, index) => index), 3, async () => {
  activeWorkers += 1;
  peakWorkers = Math.max(peakWorkers, activeWorkers);
  await new Promise((resolve) => setTimeout(resolve, 2));
  activeWorkers -= 1;
});
assert.equal(peakWorkers, 3, "item loading must honor its concurrency bound");

console.log("file transfer queue regression checks passed");
