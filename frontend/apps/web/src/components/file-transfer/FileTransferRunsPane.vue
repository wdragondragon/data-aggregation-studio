<template>
  <div class="transfer-runs">
    <div class="section-toolbar transfer-runs__toolbar">
      <div class="transfer-run-filters">
        <el-select v-model="filters.taskId" clearable filterable placeholder="全部预设任务">
          <el-option v-for="task in taskOptions" :key="String(task.id)" :label="task.name" :value="task.id" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="全部状态">
          <el-option v-for="status in runStatuses" :key="status" :label="fileTransferStatusLabel(status)" :value="status" />
        </el-select>
        <el-button type="primary" @click="searchRuns">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <div>
        <el-button :icon="DataLine" @click="openMetrics">传输指标</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadRuns">刷新</el-button>
      </div>
    </div>

    <StudioTableShell min-width="1480px">
      <el-table v-loading="loading" :data="runs" size="small" table-layout="fixed" max-height="calc(100vh - 300px)">
        <el-table-column label="触发" min-width="180">
          <template #default="{ row }">
            <div class="stack-cell">
              <el-button link type="primary" @click="openDetail(row)">{{ fileTransferRunTriggerName(row) }}</el-button>
              <span v-if="row.taskName">{{ row.taskName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="从 / 到" min-width="440">
          <template #default="{ row }">
            <div class="run-route-cell">
              <div class="run-endpoint-line">
                <b>从</b>
                <span class="studio-mono" :title="runEndpointText(row, true)">{{ runEndpointText(row, true) }}</span>
              </div>
              <div class="run-endpoint-line">
                <b>到</b>
                <span class="studio-mono" :title="runEndpointText(row, false)">{{ runEndpointText(row, false) }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="文件摘要" min-width="210">
          <template #default="{ row }">
            <div class="run-summary-cell">
              <span>总 {{ formatTransferNumber(row.totalFiles) }}</span>
              <span class="is-success">成功 {{ formatTransferNumber(row.successFiles) }}</span>
              <span class="is-muted">跳过 {{ formatTransferNumber(row.skippedFiles) }}</span>
              <span class="is-danger">失败 {{ formatTransferNumber(row.failedFiles) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="字节 / 速度" min-width="190">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ formatBytes(row.transferredBytes) }} / {{ formatBytes(row.totalBytes) }}</span>
              <span>{{ formatTransferSpeed(row.currentBytesPerSecond) }} · 峰值 {{ formatTransferSpeed(row.peakBytesPerSecond) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="132" align="center">
          <template #default="{ row }">
            <StatusPill :label="fileTransferStatusLabel(row.status)" :tone="fileTransferStatusTone(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="开始 / 结束" min-width="190">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ formatTransferDate(row.startedAt || row.createdAt) }}</span>
              <span>{{ formatTransferDate(row.endedAt) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="消息" min-width="210" show-overflow-tooltip prop="message" />
        <el-table-column label="操作" width="154" fixed="right" align="center">
          <template #default="{ row }">
            <div class="run-row-actions">
              <el-tooltip content="暂停" placement="top">
                <el-button link :icon="VideoPause" :disabled="!canPause(row.status)" aria-label="暂停" @click="pauseRun(row)" />
              </el-tooltip>
              <el-tooltip content="恢复" placement="top">
                <el-button link type="success" :icon="VideoPlay" :disabled="!canResume(row.status)" aria-label="恢复" @click="resumeRun(row)" />
              </el-tooltip>
              <el-tooltip content="取消" placement="top">
                <el-button link type="danger" :icon="Close" :disabled="isTerminal(row.status)" aria-label="取消" @click="cancelRun(row)" />
              </el-tooltip>
              <el-tooltip content="运行日志" placement="top">
                <el-button link type="primary" :icon="Document" :disabled="!row.runRecordId" aria-label="运行日志" @click="openLog(row)" />
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </StudioTableShell>

    <div class="table-pagination">
      <el-pagination
        v-model:current-page="pagination.pageNo"
        v-model:page-size="pagination.pageSize"
        background
        layout="total, sizes, prev, pager, next"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        @current-change="loadRuns"
        @size-change="handlePageSizeChange"
      />
    </div>

    <el-drawer v-model="detailVisible" title="文件传输运行详情" size="min(1120px, 96vw)">
      <template v-if="activeRun">
        <div class="run-detail-summary">
          <div><span>触发</span><strong>{{ fileTransferRunTriggerName(activeRun) }}</strong></div>
          <div><span>状态</span><StatusPill :label="fileTransferStatusLabel(activeRun.status)" :tone="fileTransferStatusTone(activeRun.status)" /></div>
          <div><span>已传输</span><strong>{{ formatBytes(activeRun.transferredBytes) }}</strong></div>
          <div><span>当前速度</span><strong>{{ formatTransferSpeed(activeRun.currentBytesPerSecond) }}</strong></div>
          <div><span>活动文件</span><strong>{{ activeRun.activeFiles || 0 }}</strong></div>
          <div><span>累计重试</span><strong>{{ activeRun.retryCount || 0 }}</strong></div>
        </div>

        <div class="run-detail-toolbar">
          <span>{{ activeRun.message || '--' }}</span>
          <div>
            <el-button :icon="VideoPause" :disabled="!canPause(activeRun.status)" @click="pauseRun(activeRun)">暂停</el-button>
            <el-button :icon="VideoPlay" :disabled="!canResume(activeRun.status)" @click="resumeRun(activeRun)">恢复</el-button>
            <el-button type="danger" plain :icon="Close" :disabled="isTerminal(activeRun.status)" @click="cancelRun(activeRun)">取消</el-button>
            <el-button :icon="Document" :disabled="!activeRun.runRecordId" @click="openLog(activeRun)">日志</el-button>
          </div>
        </div>

        <StudioTableShell min-width="1240px">
          <el-table v-loading="detailLoading" :data="runItems" size="small" table-layout="fixed" height="calc(100vh - 310px)">
            <el-table-column label="文件" min-width="190">
              <template #default="{ row }">
                <div class="stack-cell">
                  <strong :title="row.sourcePath">{{ fileName(row.sourcePath) }}</strong>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="从 / 到" min-width="500">
              <template #default="{ row }">
                <div class="run-route-cell">
                  <div class="run-endpoint-line">
                    <b>从</b>
                    <span class="studio-mono" :title="fileTransferEndpointText(row.sourceRuntimeClusterName, row.sourceDatasourceName, row.sourcePath)">
                      {{ fileTransferEndpointText(row.sourceRuntimeClusterName, row.sourceDatasourceName, row.sourcePath) }}
                    </span>
                  </div>
                  <div class="run-endpoint-line">
                    <b>到</b>
                    <span class="studio-mono" :title="fileTransferEndpointText(row.targetRuntimeClusterName, row.targetDatasourceName, row.targetPath)">
                      {{ fileTransferEndpointText(row.targetRuntimeClusterName, row.targetDatasourceName, row.targetPath) }}
                    </span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="100" align="right">
              <template #default="{ row }">{{ formatBytes(row.fileSize) }}</template>
            </el-table-column>
            <el-table-column label="进度" width="180">
              <template #default="{ row }">
                <el-progress :percentage="transferProgress(row.transferredBytes, row.fileSize)" :stroke-width="8" />
              </template>
            </el-table-column>
            <el-table-column label="状态" width="142" align="center">
              <template #default="{ row }"><StatusPill :label="fileTransferStatusLabel(row.status)" :tone="fileTransferStatusTone(row.status)" /></template>
            </el-table-column>
            <el-table-column label="速度 / 续传" min-width="150">
              <template #default="{ row }">
                <div class="stack-cell">
                  <span>{{ formatTransferSpeed(row.currentBytesPerSecond) }}</span>
                  <span>续传 {{ formatBytes(row.resumedBytes) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="SHA-256" min-width="220">
              <template #default="{ row }">
                <div class="stack-cell studio-mono checksum-cell">
                  <span :title="row.sourceChecksum">源 {{ row.sourceChecksum || '--' }}</span>
                  <span :title="row.targetChecksum">目标 {{ row.targetChecksum || '--' }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="错误" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ row.errorCode ? `${row.errorCode}: ` : '' }}{{ row.errorMessage || '--' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="86" fixed="right" align="center">
              <template #default="{ row }">
                <el-button
                  link
                  type="warning"
                  :icon="RefreshRight"
                  :disabled="!canRetryItem(row.status) || !row.id"
                  @click="retryItem(row)"
                >重试</el-button>
              </template>
            </el-table-column>
          </el-table>
        </StudioTableShell>
      </template>
    </el-drawer>

    <RunLogDrawer v-model="logVisible" :run-record-id="activeRunRecordId" />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { Close, DataLine, Document, Refresh, RefreshRight, VideoPause, VideoPlay } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type {
  EntityId,
  FileTransferQueueEventView,
  FileTransferRunItemView,
  FileTransferRunView,
  FileTransferTaskDefinitionView,
} from "@studio/api-sdk";
import { StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";
import RunLogDrawer from "@/components/RunLogDrawer.vue";
import {
  fileTransferEndpointText,
  fileTransferRunTriggerName,
  fileTransferStatusLabel,
  fileTransferStatusTone,
  formatBytes,
  formatTransferDate,
  formatTransferNumber,
  formatTransferSpeed,
  transferProgress,
} from "@/utils/fileTransfer";
import { subscribeFileTransferEvents } from "@/utils/fileTransferEvents";

const router = useRouter();
const runs = ref<FileTransferRunView[]>([]);
const taskOptions = ref<FileTransferTaskDefinitionView[]>([]);
const loading = ref(false);
const total = ref(0);
const pagination = reactive({ pageNo: 1, pageSize: 20 });
const filters = reactive<{ taskId?: EntityId; status?: string }>({});
const detailVisible = ref(false);
const detailLoading = ref(false);
const activeRun = ref<FileTransferRunView>();
const runItems = ref<FileTransferRunItemView[]>([]);
const logVisible = ref(false);
const activeRunRecordId = ref<EntityId>();
let runsReloadRequested = false;
let unsubscribeEvents: (() => void) | undefined;

const runStatuses = ["QUEUED", "RUNNING", "PAUSED", "SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELED"];

async function loadOptions() {
  const page = await studioApi.fileTransfer.tasks.list({ pageNo: 1, pageSize: 200 });
  taskOptions.value = page.items;
}

async function loadRuns() {
  if (loading.value) {
    runsReloadRequested = true;
    return;
  }
  loading.value = true;
  try {
    const page = await studioApi.fileTransfer.runs.list({
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      taskId: filters.taskId,
      status: filters.status,
    });
    runs.value = page.items;
    total.value = page.total;
  } catch (error) {
    showError(error, "加载文件传输运行失败");
  } finally {
    loading.value = false;
    if (runsReloadRequested) {
      runsReloadRequested = false;
      void loadRuns();
    }
  }
}

function searchRuns() {
  pagination.pageNo = 1;
  void loadRuns();
}

function resetFilters() {
  filters.taskId = undefined;
  filters.status = undefined;
  searchRuns();
}

function handlePageSizeChange() {
  pagination.pageNo = 1;
  void loadRuns();
}

async function openDetail(run: FileTransferRunView) {
  if (!run.id) return;
  activeRun.value = run;
  runItems.value = [];
  detailVisible.value = true;
  await loadDetail(run.id);
}

async function loadDetail(runId: EntityId) {
  detailLoading.value = true;
  try {
    const [run, items] = await Promise.all([
      studioApi.fileTransfer.runs.get(runId, { studioSkipGlobalLoading: true }),
      studioApi.fileTransfer.runs.items(runId, { pageNo: 1, pageSize: 1000 }, { studioSkipGlobalLoading: true }),
    ]);
    activeRun.value = run;
    runItems.value = items.items;
    const index = runs.value.findIndex((item) => String(item.id) === String(run.id));
    if (index >= 0) runs.value[index] = run;
  } catch (error) {
    showError(error, "加载运行详情失败");
  } finally {
    detailLoading.value = false;
  }
}

function canPause(status?: string) {
  return ["QUEUED", "RUNNING"].includes(String(status ?? "").toUpperCase());
}

function canResume(status?: string) {
  return ["PAUSED", "FAILED"].includes(String(status ?? "").toUpperCase());
}

function canRetryItem(status?: string) {
  return ["FAILED", "CONFLICT", "POST_ACTION_FAILED", "CANCELED"].includes(String(status ?? "").toUpperCase());
}

function isTerminal(status?: string) {
  return ["SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELED"].includes(String(status ?? "").toUpperCase());
}

async function pauseRun(run: FileTransferRunView) {
  if (!run.id) return;
  await runAction(() => studioApi.fileTransfer.runs.pause(run.id as EntityId), "暂停请求已提交");
}

async function resumeRun(run: FileTransferRunView) {
  if (!run.id) return;
  await runAction(() => studioApi.fileTransfer.runs.resume(run.id as EntityId), "运行已重新入队");
}

async function cancelRun(run: FileTransferRunView) {
  if (!run.id) return;
  try {
    await ElMessageBox.confirm("取消会停止后续调度并清理临时数据，确认继续？", "取消文件传输", { type: "warning" });
  } catch {
    return;
  }
  await runAction(() => studioApi.fileTransfer.runs.cancel(run.id as EntityId), "取消请求已提交");
}

async function retryItem(item: FileTransferRunItemView) {
  if (!activeRun.value?.id || !item.id) return;
  await runAction(
    () => studioApi.fileTransfer.runs.retryItem(activeRun.value?.id as EntityId, item.id as EntityId),
    "文件已重新入队",
  );
}

async function runAction(action: () => Promise<unknown>, message: string) {
  try {
    const result = await action();
    if (result && typeof result === "object") applyRunChanged(result as FileTransferRunView, []);
    ElMessage.success(message);
  } catch (error) {
    showError(error, "运行操作失败");
  }
}

function applyFileTransferEvent(event: FileTransferQueueEventView) {
  const type = String(event.type ?? "").toUpperCase();
  if (type === "SNAPSHOT_REQUIRED") {
    void loadRuns();
    return;
  }
  if (loading.value) runsReloadRequested = true;
  if (type === "RUN_REMOVED" && event.runId != null) {
    const existed = runs.value.some((run) => String(run.id) === String(event.runId));
    runs.value = runs.value.filter((run) => String(run.id) !== String(event.runId));
    if (existed) total.value = Math.max(0, total.value - 1);
    if (String(activeRun.value?.id) === String(event.runId)) {
      detailVisible.value = false;
      activeRun.value = undefined;
      runItems.value = [];
    }
    return;
  }
  if (type === "ITEM_REMOVED" && event.itemId != null) {
    runItems.value = runItems.value.filter((item) => String(item.id) !== String(event.itemId));
  }
  if (event.run) applyRunChanged(event.run, event.items ?? []);
}

function applyRunChanged(run: FileTransferRunView, changedItems: FileTransferRunItemView[]) {
  if (!run.id) return;
  const index = runs.value.findIndex((item) => String(item.id) === String(run.id));
  const matches = matchesFilters(run);
  if (index >= 0) {
    if (matches) {
      runs.value[index] = run;
    } else {
      runs.value.splice(index, 1);
      total.value = Math.max(0, total.value - 1);
    }
  } else if (matches && pagination.pageNo === 1) {
    runs.value = [run, ...runs.value].slice(0, pagination.pageSize);
  }
  if (String(activeRun.value?.id) !== String(run.id)) return;
  activeRun.value = run;
  if (!changedItems.length) return;
  const next = [...runItems.value];
  for (const changed of changedItems) {
    const itemIndex = next.findIndex((item) => String(item.id ?? item.coreItemId)
      === String(changed.id ?? changed.coreItemId));
    if (itemIndex >= 0) next[itemIndex] = changed;
    else next.push(changed);
  }
  runItems.value = next;
}

function matchesFilters(run: FileTransferRunView) {
  if (filters.taskId != null && String(run.taskId) !== String(filters.taskId)) return false;
  return !filters.status || String(run.status ?? "").toUpperCase() === filters.status;
}

function openLog(run: FileTransferRunView) {
  if (!run.runRecordId) return;
  activeRunRecordId.value = run.runRecordId;
  logVisible.value = true;
}

function openMetrics() {
  void router.push({ path: "/file-transfer", query: { view: "metrics" } });
}

function runEndpointText(run: FileTransferRunView, source: boolean) {
  const clusterName = source
    ? run.sourceRuntimeClusterName || (run.sourceRuntimeClusterId == null ? "多个来源集群" : "未知运行集群")
    : run.targetRuntimeClusterName || (run.targetRuntimeClusterId == null ? "多个目标集群" : "未知运行集群");
  const datasourceName = source
    ? run.sourceDatasourceName || (run.sourceDatasourceId == null ? "多个来源数据源" : "未知数据源")
    : run.targetDatasourceName || (run.targetDatasourceId == null ? "多个目标数据源" : "未知数据源");
  return fileTransferEndpointText(clusterName, datasourceName, runEndpointPath(run, source));
}

function runEndpointPath(run: FileTransferRunView, source: boolean) {
  const manualItems = Array.isArray(run.resolvedSpec?.manualItems)
    ? run.resolvedSpec.manualItems as Array<Record<string, unknown>>
    : [];
  if (manualItems.length === 1) {
    return String(manualItems[0]?.[source ? "sourcePath" : "targetPath"] ?? "--");
  }
  if (manualItems.length > 1) return `${manualItems.length} 个选择项`;
  const config = run.resolvedSpec?.[source ? "selection" : "mapping"];
  if (!config || typeof config !== "object") return "--";
  const values = config as Record<string, unknown>;
  const path = source
    ? values.rootPath ?? values.basePath
    : values.targetRootPath ?? values.targetBasePath;
  return String(path ?? "--");
}

function fileName(path?: string) {
  const text = String(path ?? "--");
  return text.split(/[\\/]/).filter(Boolean).pop() || text;
}

function showError(error: unknown, fallback: string) {
  ElMessage.error(error instanceof Error && error.message ? error.message : fallback);
}

onMounted(async () => {
  unsubscribeEvents = subscribeFileTransferEvents(applyFileTransferEvent);
  try {
    await Promise.all([loadOptions(), loadRuns()]);
  } catch (error) {
    showError(error, "加载文件传输运行失败");
  }
});

onBeforeUnmount(() => unsubscribeEvents?.());
</script>

<style scoped>
.transfer-runs {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.transfer-runs__toolbar > div,
.transfer-run-filters,
.run-row-actions,
.run-detail-toolbar,
.run-detail-toolbar > div {
  display: flex;
  align-items: center;
  gap: 8px;
}

.transfer-run-filters .el-select:first-child {
  width: min(280px, 42vw);
}

.transfer-run-filters .el-select:nth-child(2) {
  width: 160px;
}

.stack-cell {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.run-route-cell {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.run-endpoint-line {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.run-endpoint-line:last-child {
  color: var(--studio-text-soft);
}

.run-endpoint-line b {
  color: var(--studio-text-soft);
  font-size: 12px;
  font-weight: 600;
}

.run-endpoint-line span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stack-cell > span,
.stack-cell > strong,
.stack-cell .el-button {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stack-cell span:last-child {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.run-summary-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  font-size: 12px;
}

.is-success { color: var(--studio-success); }
.is-danger { color: var(--studio-danger); }
.is-muted { color: var(--studio-text-soft); }

.table-pagination {
  display: flex;
  justify-content: flex-end;
}

.run-detail-summary {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 1px;
  margin-bottom: 14px;
  overflow: hidden;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: var(--studio-border);
}

.run-detail-summary > div {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 12px;
  background: var(--studio-surface-strong);
}

.run-detail-summary span:first-child {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.run-detail-toolbar {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.run-detail-toolbar > span {
  min-width: 0;
  overflow: hidden;
  color: var(--studio-text-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.checksum-cell span {
  font-size: 11px;
}

@media (max-width: 980px) {
  .transfer-runs__toolbar,
  .transfer-run-filters,
  .transfer-runs__toolbar > div:last-child,
  .run-detail-toolbar {
    align-items: stretch;
    flex-direction: column;
    width: 100%;
  }

  .transfer-run-filters .el-select:first-child,
  .transfer-run-filters .el-select:nth-child(2) {
    width: 100%;
  }

  .run-detail-summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 520px) {
  .run-detail-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .run-detail-toolbar > div {
    flex-wrap: wrap;
  }
}
</style>
