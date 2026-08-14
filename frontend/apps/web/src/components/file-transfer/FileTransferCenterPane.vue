<template>
  <div class="transfer-center">
    <div class="transfer-cluster-picker">
      <span>运行集群</span>
      <el-select
        v-model="selectedRuntimeClusterId"
        placeholder="请选择运行集群"
        filterable
        clearable
        class="transfer-cluster-picker__select"
        @change="handleSharedClusterChange"
      >
        <el-option
          v-for="cluster in runtimeClusters"
          :key="String(cluster.id)"
          :label="cluster.name || cluster.code || `#${cluster.id}`"
          :value="cluster.id"
          :disabled="cluster.enabled === false"
        />
      </el-select>
    </div>
    <div class="transfer-browser-grid">
      <FileTransferBrowserPanel
        title="左侧文件源"
        :runtime-cluster-id="left.runtimeClusterId"
        :datasource-id="left.datasourceId"
        :runtime-clusters="runtimeClusters"
        :show-runtime-cluster-selector="false"
        :datasources="left.datasources"
        :path="left.path"
        :entries="left.entries"
        :loading="left.loading"
        :has-more="left.hasMore"
        :page-number="left.cursorHistory.length"
        :selected-count="left.selected.length"
        @update:datasource-id="handleDatasourceChange(left, $event)"
        @browse="browse(left, $event)"
        @parent="browse(left, parentTransferPath(left.path))"
        @refresh="loadBrowser(left)"
        @previous-page="previousPage(left)"
        @next-page="nextPage(left)"
        @selection-change="left.selected = $event"
      />

      <div class="transfer-directions">
        <el-tooltip content="左侧传到右侧" placement="top">
          <el-button
            type="primary"
            :icon="ArrowRight"
            circle
            size="large"
            aria-label="左侧传到右侧"
            :disabled="!canQueue(left, right)"
            @click="openTransferDialog('LEFT_TO_RIGHT')"
          />
        </el-tooltip>
        <el-tooltip content="右侧传到左侧" placement="top">
          <el-button
            type="primary"
            plain
            :icon="ArrowLeft"
            circle
            size="large"
            aria-label="右侧传到左侧"
            :disabled="!canQueue(right, left)"
            @click="openTransferDialog('RIGHT_TO_LEFT')"
          />
        </el-tooltip>
      </div>

      <FileTransferBrowserPanel
        title="右侧文件源"
        :runtime-cluster-id="right.runtimeClusterId"
        :datasource-id="right.datasourceId"
        :runtime-clusters="runtimeClusters"
        :show-runtime-cluster-selector="false"
        :datasources="right.datasources"
        :path="right.path"
        :entries="right.entries"
        :loading="right.loading"
        :has-more="right.hasMore"
        :page-number="right.cursorHistory.length"
        :selected-count="right.selected.length"
        @update:datasource-id="handleDatasourceChange(right, $event)"
        @browse="browse(right, $event)"
        @parent="browse(right, parentTransferPath(right.path))"
        @refresh="loadBrowser(right)"
        @previous-page="previousPage(right)"
        @next-page="nextPage(right)"
        @selection-change="right.selected = $event"
      />
    </div>

    <section class="transfer-queue">
      <header class="transfer-queue__header">
        <div>
          <strong>传输队列</strong>
          <span>{{ queueRows.length }} 个文件项 · {{ activeRunCount }} 个活动运行</span>
        </div>
        <div>
          <el-button :icon="Refresh" :loading="queueLoading" @click="loadQueue">刷新</el-button>
          <el-button
            v-if="queueTab === 'completed'"
            :icon="Finished"
            :disabled="!hasTerminalRuns"
            @click="clearCompleted"
          >
            清理已完成
          </el-button>
        </div>
      </header>

      <el-tabs v-model="queueTab" class="transfer-queue__tabs">
        <el-tab-pane :label="`传输中 (${activeQueueRows.length})`" name="active" />
        <el-tab-pane :label="`已完成 (${completedQueueRows.length})`" name="completed" />
      </el-tabs>

      <StudioTableShell min-width="1320px">
        <el-table
          v-loading="queueLoading"
          :data="visibleQueueRows"
          row-key="rowKey"
          size="small"
          table-layout="fixed"
          height="310"
          :empty-text="queueTab === 'active' ? '暂无正在传输的文件' : '暂无已完成文件'"
        >
          <el-table-column label="文件" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="queue-file-cell">
                <strong>{{ row.fileName }}</strong>
                <span>{{ row.triggerName }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="从 / 到" min-width="520">
            <template #default="{ row }">
              <div class="queue-route-cell">
                <div class="queue-endpoint-line">
                  <b>从</b>
                  <span class="studio-mono" :title="fileTransferEndpointText(row.sourceRuntimeClusterName, row.sourceDatasourceName, row.sourcePath)">
                    {{ fileTransferEndpointText(row.sourceRuntimeClusterName, row.sourceDatasourceName, row.sourcePath) }}
                  </span>
                </div>
                <div class="queue-endpoint-line">
                  <b>到</b>
                  <span class="studio-mono" :title="fileTransferEndpointText(row.targetRuntimeClusterName, row.targetDatasourceName, row.targetPath)">
                    {{ fileTransferEndpointText(row.targetRuntimeClusterName, row.targetDatasourceName, row.targetPath) }}
                  </span>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="100" align="right">
            <template #default="{ row }">{{ row.fileSize == null ? '--' : formatBytes(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column label="进度" width="180">
            <template #default="{ row }">
              <div class="queue-progress-cell">
                <el-progress
                  :percentage="transferProgress(displayTransferredBytes(row), row.fileSize)"
                  :status="row.status === 'FAILED' || row.status === 'CONFLICT' ? 'exception' : row.status === 'SUCCESS' || row.status === 'SKIPPED' ? 'success' : undefined"
                  :stroke-width="8"
                />
                <span>{{ formatBytes(displayTransferredBytes(row)) }} / {{ formatBytes(row.fileSize) }}</span>
                <span v-if="isChecksumRebuilding(row)">
                  恢复校验 {{ formatBytes(row.resumeCheckedBytes) }} / {{ formatBytes(row.resumeTotalBytes) }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="速度" width="112" align="right">
            <template #default="{ row }">
              <div class="queue-speed-cell">
                <span>{{ formatTransferSpeed(row.currentBytesPerSecond) }}</span>
                <small v-if="isChecksumRebuilding(row)">校验速度</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="142" align="center">
            <template #default="{ row }">
              <StatusPill :label="fileTransferStatusLabel(row.status)" :tone="fileTransferStatusTone(row.status)" />
            </template>
          </el-table-column>
          <el-table-column label="重试" width="70" align="center">
            <template #default="{ row }">{{ row.attempts || 0 }}</template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="210" show-overflow-tooltip>
            <template #default="{ row }">{{ fileTransferFailureMessage(row.errorMessage || row.runMessage) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="208" fixed="right" align="center">
            <template #default="{ row }">
              <div class="queue-actions">
                <el-tooltip content="暂停运行" placement="top">
                  <el-button
                    link
                    :icon="VideoPause"
                    aria-label="暂停运行"
                    :disabled="!canPause(row.runStatus)"
                    @click="pauseRun(row.runId)"
                  />
                </el-tooltip>
                <el-tooltip content="恢复运行" placement="top">
                  <el-button
                    link
                    type="success"
                    :icon="VideoPlay"
                    aria-label="恢复运行"
                    :disabled="!canResume(row.runStatus)"
                    @click="resumeRun(row.runId)"
                  />
                </el-tooltip>
                <el-tooltip content="取消运行" placement="top">
                  <el-button
                    link
                    type="danger"
                    :icon="Close"
                    aria-label="取消运行"
                    :disabled="isTerminal(row.runStatus)"
                    @click="cancelRun(row.runId)"
                  />
                </el-tooltip>
                <el-tooltip content="重试文件" placement="top">
                  <el-button
                    link
                    type="warning"
                    :icon="RefreshRight"
                    aria-label="重试文件"
                    :disabled="row.synthetic || !canRetryItem(row.status)"
                    @click="retryItem(row.runId, row.id)"
                  />
                </el-tooltip>
                <el-tooltip content="移出队列" placement="top">
                  <el-button
                    link
                    type="danger"
                    :icon="Delete"
                    aria-label="移出队列"
                    :disabled="!canRemoveRow(row)"
                    @click="removeQueueRow(row)"
                  />
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </StudioTableShell>
    </section>

    <el-dialog v-model="transferDialogVisible" title="传输策略" width="min(520px, calc(100vw - 28px))" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="内容冲突">
          <el-radio-group v-model="policyForm.conflictPolicy">
            <el-radio-button value="FAIL">失败</el-radio-button>
            <el-radio-button value="OVERWRITE">覆盖</el-radio-button>
            <el-radio-button value="BACKUP_THEN_OVERWRITE">备份后覆盖</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <div class="policy-fixed-rule">
          <span>摘要算法</span>
          <strong>SHA-256</strong>
          <span>摘要一致时自动跳过</span>
        </div>
        <el-form-item label="目标成功后的源端动作">
          <el-select v-model="policyForm.sourceSuccessAction">
            <el-option label="保留源文件" value="KEEP" />
            <el-option label="删除源文件" value="DELETE" />
            <el-option label="备份源文件" value="BACKUP" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="policyForm.sourceSuccessAction === 'BACKUP'" label="源端备份根路径">
          <el-input v-model="policyForm.sourceBackupRootPath" class="studio-mono" placeholder="/archive/${date:yyyyMMdd}" />
        </el-form-item>
        <el-form-item label="并行文件数">
          <el-input-number v-model="policyForm.concurrency" :min="1" :max="32" controls-position="right" />
        </el-form-item>
        <el-alert
          v-if="policyForm.sourceSuccessAction === 'DELETE'"
          type="warning"
          :closable="false"
          title="仅在目标提交和 SHA-256 校验成功后删除源文件"
        />
      </el-form>
      <template #footer>
        <el-button @click="transferDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="queueSubmitting" @click="submitTransfer">加入队列并开始</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import {
  ArrowLeft,
  ArrowRight,
  Close,
  Delete,
  Finished,
  Refresh,
  RefreshRight,
  VideoPause,
  VideoPlay,
} from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type {
  DataSourceOptionView,
  EntityId,
  FileTransferDirection,
  FileTransferFileEntryView,
  FileTransferManualItemRequest,
  FileTransferQueueEventView,
  FileTransferRunItemView,
  FileTransferRunView,
  RuntimeClusterView,
} from "@studio/api-sdk";
import { StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";
import FileTransferBrowserPanel from "./FileTransferBrowserPanel.vue";
import {
  fileTransferEndpointText,
  fileTransferFailureMessage,
  fileTransferRunTriggerName,
  fileTransferStatusLabel,
  fileTransferStatusTone,
  formatBytes,
  formatTransferSpeed,
  isFileTransferDatasourceType,
  joinTransferPath,
  normalizeTransferPath,
  parentTransferPath,
  transferProgress,
} from "@/utils/fileTransfer";
import { subscribeFileTransferEvents } from "@/utils/fileTransferEvents";
import {
  loadBoundedManualRuns,
  mapWithConcurrency,
  selectQueueRuns,
} from "@/utils/fileTransferQueue";

interface BrowserState {
  runtimeClusterId: EntityId | "";
  datasourceId: EntityId | "";
  datasources: DataSourceOptionView[];
  path: string;
  entries: FileTransferFileEntryView[];
  selected: FileTransferFileEntryView[];
  loading: boolean;
  cursorHistory: Array<string | undefined>;
  nextCursor?: string;
  hasMore: boolean;
  initialPathPending: boolean;
}

interface QueueRow extends FileTransferRunItemView {
  rowKey: string;
  runId: EntityId;
  runStatus?: string;
  runMessage?: string;
  fileName: string;
  triggerName: string;
  synthetic?: boolean;
}

type QueueTab = "active" | "completed";

const completedItemStatuses = new Set([
  "SUCCESS",
  "SKIPPED",
  "CONFLICT",
  "POST_ACTION_FAILED",
  "FAILED",
  "CANCELED",
]);

const runtimeClusters = ref<RuntimeClusterView[]>([]);
const selectedRuntimeClusterId = ref<EntityId | "">("");
const left = reactive<BrowserState>(createBrowserState());
const right = reactive<BrowserState>(createBrowserState());
const transferDialogVisible = ref(false);
const queueSubmitting = ref(false);
const queueLoading = ref(false);
const pendingDirection = ref<FileTransferDirection>("LEFT_TO_RIGHT");
const policyForm = reactive({
  conflictPolicy: "FAIL",
  sourceSuccessAction: "KEEP",
  sourceBackupRootPath: "",
  concurrency: 4,
});
const recentRuns = ref<FileTransferRunView[]>([]);
const queueRows = ref<QueueRow[]>([]);
const queueTab = ref<QueueTab>("active");
let queueReloadRequested = false;
let initialQueueLoadPending = true;
let unsubscribeEvents: (() => void) | undefined;

const activeRunCount = computed(() => recentRuns.value.filter((run) => !isTerminal(run.status)).length);
const hasTerminalRuns = computed(() => recentRuns.value.some((run) => isTerminal(run.status)));
const activeQueueRows = computed(() => queueRows.value.filter((row) => !isCompletedQueueRow(row)));
const completedQueueRows = computed(() => queueRows.value.filter(isCompletedQueueRow));
const visibleQueueRows = computed(() => queueTab.value === "completed"
  ? completedQueueRows.value
  : activeQueueRows.value);

function createBrowserState(): BrowserState {
  return {
    runtimeClusterId: "",
    datasourceId: "",
    datasources: [],
    path: "/",
    entries: [],
    selected: [],
    loading: false,
    cursorHistory: [undefined],
    nextCursor: undefined,
    hasMore: false,
    initialPathPending: false,
  };
}

function canQueue(source: BrowserState, target: BrowserState) {
  return source.selected.length > 0
    && source.runtimeClusterId !== ""
    && source.datasourceId !== ""
    && target.runtimeClusterId !== ""
    && target.datasourceId !== "";
}

async function loadRuntimeOptions() {
  runtimeClusters.value = (await studioApi.runtimeClusters.options()).filter((item) => item.enabled !== false);
}

async function loadDatasources(side: BrowserState) {
  if (side.runtimeClusterId === "") {
    side.datasources = [];
    side.datasourceId = "";
    return;
  }
  const options = await studioApi.datasources.optionsByRuntimeCluster(side.runtimeClusterId);
  side.datasources = options.filter((item) => item.enabled !== false
    && item.executable !== false
    && isFileTransferDatasourceType(item.typeCode));
  if (!side.datasources.some((item) => String(item.id) === String(side.datasourceId))) {
    side.datasourceId = "";
  }
}

async function handleSharedClusterChange(value?: EntityId) {
  selectedRuntimeClusterId.value = value ?? "";
  left.runtimeClusterId = selectedRuntimeClusterId.value;
  right.runtimeClusterId = selectedRuntimeClusterId.value;
  left.datasourceId = "";
  right.datasourceId = "";
  left.datasources = [];
  right.datasources = [];
  resetBrowser(left);
  resetBrowser(right);
  if (selectedRuntimeClusterId.value === "") return;
  try {
    await Promise.all([loadDatasources(left), loadDatasources(right)]);
  } catch (error) {
    showError(error, "加载文件数据源失败");
  }
}

async function handleDatasourceChange(side: BrowserState, value?: EntityId) {
  side.datasourceId = value ?? "";
  resetBrowser(side);
  if (side.datasourceId !== "") {
    side.initialPathPending = true;
    await loadBrowser(side);
  }
}

function resetBrowser(side: BrowserState, path = "/") {
  side.initialPathPending = false;
  side.path = normalizeTransferPath(path);
  side.entries = [];
  side.selected = [];
  side.cursorHistory = [undefined];
  side.nextCursor = undefined;
  side.hasMore = false;
}

async function browse(side: BrowserState, path: string) {
  resetBrowser(side, path);
  await loadBrowser(side);
}

async function loadBrowser(side: BrowserState) {
  if (side.runtimeClusterId === "" || side.datasourceId === "") {
    side.entries = [];
    side.hasMore = false;
    return;
  }
  side.loading = true;
  const requestedPath = side.path;
  try {
    const page = await studioApi.fileTransfer.browser.list({
      runtimeClusterId: side.runtimeClusterId,
      datasourceId: side.datasourceId,
      path: side.path,
      cursor: side.cursorHistory[side.cursorHistory.length - 1],
      pageSize: 200,
    }, { studioSkipGlobalLoading: true });
    if (side.initialPathPending && requestedPath === "/" && page.initialPath && normalizeTransferPath(page.initialPath) !== "/") {
      side.initialPathPending = false;
      resetBrowser(side, page.initialPath);
      await loadBrowser(side);
      return;
    }
    side.initialPathPending = false;
    side.path = normalizeTransferPath(page.path || side.path);
    side.entries = page.entries ?? [];
    side.selected = [];
    side.nextCursor = page.nextCursor || undefined;
    side.hasMore = Boolean(page.hasMore && page.nextCursor);
  } catch (error) {
    side.entries = [];
    side.hasMore = false;
    showError(error, "浏览目录失败");
  } finally {
    side.loading = false;
  }
}

async function nextPage(side: BrowserState) {
  if (!side.nextCursor) return;
  side.cursorHistory = [...side.cursorHistory, side.nextCursor];
  await loadBrowser(side);
}

async function previousPage(side: BrowserState) {
  if (side.cursorHistory.length <= 1) return;
  side.cursorHistory = side.cursorHistory.slice(0, -1);
  await loadBrowser(side);
}

function openTransferDialog(direction: FileTransferDirection) {
  pendingDirection.value = direction;
  transferDialogVisible.value = true;
}

async function submitTransfer() {
  const source = pendingDirection.value === "RIGHT_TO_LEFT" ? right : left;
  const target = pendingDirection.value === "RIGHT_TO_LEFT" ? left : right;
  if (!canQueue(source, target)) {
    ElMessage.warning("请选择文件并确认两侧数据源");
    return;
  }
  if (policyForm.sourceSuccessAction === "BACKUP" && !policyForm.sourceBackupRootPath.trim()) {
    ElMessage.warning("请输入源端备份根路径");
    return;
  }
  if (policyForm.sourceSuccessAction === "DELETE") {
    try {
      await ElMessageBox.confirm("目标提交并校验成功后将删除源文件，确认继续？", "确认源端删除", { type: "warning" });
    } catch {
      return;
    }
  }
  const items: FileTransferManualItemRequest[] = source.selected.map((entry) => ({
    runtimeClusterId: selectedRuntimeClusterId.value as EntityId,
    sourceRuntimeClusterId: source.runtimeClusterId as EntityId,
    sourceDatasourceId: source.datasourceId as EntityId,
    sourcePath: entry.path,
    targetRuntimeClusterId: target.runtimeClusterId as EntityId,
    targetDatasourceId: target.datasourceId as EntityId,
    targetPath: joinTransferPath(target.path, entry.name || entry.path.split("/").filter(Boolean).pop() || "item"),
    recursive: Boolean(entry.directory),
  }));
  queueSubmitting.value = true;
  try {
    const run = await studioApi.fileTransfer.runs.createManual({
      runtimeClusterId: selectedRuntimeClusterId.value as EntityId,
      items,
      autoStart: true,
      policy: {
        conflictPolicy: policyForm.conflictPolicy,
        checksumAlgorithm: "SHA-256",
        sourceSuccessAction: policyForm.sourceSuccessAction,
        sourceBackupRootPath: policyForm.sourceSuccessAction === "BACKUP"
          ? policyForm.sourceBackupRootPath.trim()
          : undefined,
      },
      runtime: {
        concurrency: policyForm.concurrency,
        maxRetries: 3,
        retryBackoffMillis: [1000, 5000, 15000],
        chunkSizeBytes: 8 * 1024 * 1024,
        checkpointIntervalMillis: 1000,
        maxBytesPerSecond: 0,
      },
    });
    transferDialogVisible.value = false;
    source.selected = [];
    ElMessage.success(`${items.length} 个选择项已加入传输队列`);
    applyRunChanged(run, []);
  } catch (error) {
    showError(error, "加入传输队列失败");
  } finally {
    queueSubmitting.value = false;
  }
}

async function loadQueue() {
  if (queueLoading.value) {
    queueReloadRequested = true;
    return;
  }
  queueLoading.value = true;
  try {
    const runs = await loadBoundedManualRuns((params) => studioApi.fileTransfer.runs.list(
      params,
      { studioSkipGlobalLoading: true },
    ));
    recentRuns.value = runs;
    const itemPages = await mapWithConcurrency(runs, 4, async (run) => {
      if (!run.id) return { run, items: [] as FileTransferRunItemView[] };
      return { run, items: await loadAllRunItems(run.id) };
    });
    queueRows.value = itemPages.flatMap(({ run, items }) => {
      if (items.length) return items.map((item) => toQueueRow(run, item));
      return manualSnapshotRows(run);
    });
  } catch (error) {
    showError(error, "加载传输队列失败");
  } finally {
    queueLoading.value = false;
    if (queueReloadRequested) {
      queueReloadRequested = false;
      void loadQueue();
    }
  }
}

async function loadAllRunItems(runId: EntityId) {
  const items: FileTransferRunItemView[] = [];
  let pageNo = 1;
  while (true) {
    const page = await studioApi.fileTransfer.runs.items(
      runId,
      { pageNo, pageSize: 1000 },
      { studioSkipGlobalLoading: true },
    );
    items.push(...page.items);
    if (!page.items.length || items.length >= page.total) break;
    pageNo += 1;
  }
  return items;
}

function toQueueRow(run: FileTransferRunView, item: FileTransferRunItemView): QueueRow {
  const sourcePath = item.sourcePath || "--";
  return {
    ...item,
    rowKey: `${run.id}:${item.id ?? item.coreItemId ?? sourcePath}`,
    runId: run.id as EntityId,
    runStatus: run.status,
    runMessage: run.message,
    fileName: sourcePath.split("/").filter(Boolean).pop() || sourcePath,
    triggerName: fileTransferRunTriggerName(run),
    sourceRuntimeClusterName: item.sourceRuntimeClusterName || run.sourceRuntimeClusterName,
    sourceDatasourceName: item.sourceDatasourceName || run.sourceDatasourceName,
    targetRuntimeClusterName: item.targetRuntimeClusterName || run.targetRuntimeClusterName,
    targetDatasourceName: item.targetDatasourceName || run.targetDatasourceName,
  };
}

function displayTransferredBytes(item: FileTransferRunItemView) {
  if (isChecksumRebuilding(item)) return item.transferredBytes;
  return item.live ? item.observedBytes ?? item.transferredBytes : item.transferredBytes;
}

function isChecksumRebuilding(item: FileTransferRunItemView) {
  return String(item.resumePhase ?? "").toUpperCase() === "REBUILDING_CHECKSUM";
}

function manualSnapshotRows(run: FileTransferRunView): QueueRow[] {
  if (Number(run.totalFiles ?? 0) > 0) return [];
  const manualItems = Array.isArray(run.resolvedSpec?.manualItems)
    ? run.resolvedSpec.manualItems as Array<Record<string, unknown>>
    : [];
  return manualItems.map((item, index) => {
    const sourcePath = String(item.sourcePath ?? "--");
    return {
      rowKey: `${run.id}:pending:${index}`,
      runId: run.id as EntityId,
      runStatus: run.status,
      runMessage: run.message,
      fileName: sourcePath.split("/").filter(Boolean).pop() || sourcePath,
      triggerName: fileTransferRunTriggerName(run),
      sourceRuntimeClusterId: item.sourceRuntimeClusterId as EntityId,
      sourceRuntimeClusterName: run.sourceRuntimeClusterName
        || resolveRuntimeClusterName(item.sourceRuntimeClusterId),
      sourceDatasourceId: item.sourceDatasourceId as EntityId,
      sourceDatasourceName: run.sourceDatasourceName
        || resolveDatasourceName(item.sourceDatasourceId),
      sourcePath,
      targetRuntimeClusterId: item.targetRuntimeClusterId as EntityId,
      targetRuntimeClusterName: run.targetRuntimeClusterName
        || resolveRuntimeClusterName(item.targetRuntimeClusterId),
      targetDatasourceId: item.targetDatasourceId as EntityId,
      targetDatasourceName: run.targetDatasourceName
        || resolveDatasourceName(item.targetDatasourceId),
      targetPath: String(item.targetPath ?? "--"),
      status: run.status === "RUNNING" ? "DISCOVERING" : run.status,
      transferredBytes: 0,
      currentBytesPerSecond: run.currentBytesPerSecond,
      synthetic: true,
    };
  });
}

function resolveRuntimeClusterName(id: unknown) {
  return runtimeClusters.value.find((cluster) => String(cluster.id) === String(id))?.name;
}

function resolveDatasourceName(id: unknown) {
  return [...left.datasources, ...right.datasources]
    .find((datasource) => String(datasource.id) === String(id))?.name;
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

function canRemoveRow(row: QueueRow) {
  const runStatus = String(row.runStatus ?? "").toUpperCase();
  if (["RUNNING", "PAUSED"].includes(runStatus)) return false;
  if (runStatus === "CANCELED") return true;
  if (row.synthetic || row.id == null) return true;
  return !["DISCOVERING", "TRANSFERRING", "VERIFYING", "COMMITTING", "POST_ACTION", "PAUSED"]
    .includes(String(row.status ?? "").toUpperCase());
}

function isTerminal(status?: string) {
  return ["SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELED"].includes(String(status ?? "").toUpperCase());
}

function isCompletedQueueRow(row: QueueRow) {
  return completedItemStatuses.has(String(row.status ?? "").toUpperCase()) || isTerminal(row.runStatus);
}

async function pauseRun(runId: EntityId) {
  await runAction(() => studioApi.fileTransfer.runs.pause(runId), "暂停请求已提交");
}

async function resumeRun(runId: EntityId) {
  await runAction(() => studioApi.fileTransfer.runs.resume(runId), "运行已重新入队");
}

async function cancelRun(runId: EntityId) {
  try {
    await ElMessageBox.confirm("取消会停止后续调度并清理临时数据，确认继续？", "取消传输", { type: "warning" });
  } catch {
    return;
  }
  await runAction(() => studioApi.fileTransfer.runs.cancel(runId), "取消请求已提交");
}

async function retryItem(runId: EntityId, itemId?: EntityId) {
  if (itemId == null) return;
  await runAction(() => studioApi.fileTransfer.runs.retryItem(runId, itemId), "文件已重新入队");
}

async function runAction(action: () => Promise<unknown>, successMessage: string) {
  try {
    const result = await action();
    if (result && typeof result === "object") applyRunChanged(result as FileTransferRunView, []);
    ElMessage.success(successMessage);
  } catch (error) {
    showError(error, "操作失败");
  }
}

async function removeQueueRow(row: QueueRow) {
  if (!canRemoveRow(row)) return;
  try {
    await ElMessageBox.confirm(
      row.synthetic || row.id == null ? "确认将该传输运行移出队列？" : "确认将该文件项移出队列？",
      "移出传输队列",
      { type: "warning" },
    );
  } catch {
    return;
  }
  try {
    if (row.synthetic || row.id == null) {
      await studioApi.fileTransfer.runs.remove(row.runId);
      removeRunLocally(row.runId);
    } else {
      await studioApi.fileTransfer.runs.removeItem(row.runId, row.id);
      queueRows.value = queueRows.value.filter((item) => String(item.id) !== String(row.id));
    }
    ElMessage.success("已移出传输队列");
  } catch (error) {
    showError(error, "移出传输队列失败");
  }
}

async function clearCompleted() {
  const completed = recentRuns.value.filter((run) => isTerminal(run.status) && run.id != null);
  if (!completed.length) return;
  try {
    await ElMessageBox.confirm(`确认从队列移除 ${completed.length} 个已结束运行？`, "清理已完成", { type: "warning" });
  } catch {
    return;
  }
  const failed: EntityId[] = [];
  for (const run of completed) {
    try {
      await studioApi.fileTransfer.runs.remove(run.id as EntityId);
      removeRunLocally(run.id as EntityId);
    } catch {
      failed.push(run.id as EntityId);
    }
  }
  if (failed.length) {
    ElMessage.error(`${failed.length} 个运行清理失败`);
  } else {
    ElMessage.success("已清理完成队列");
  }
}

function applyFileTransferEvent(event: FileTransferQueueEventView) {
  const type = String(event.type ?? "").toUpperCase();
  if (type === "SNAPSHOT_REQUIRED") {
    if (initialQueueLoadPending) return;
    void loadQueue();
    return;
  }
  if (queueLoading.value) queueReloadRequested = true;
  if (type === "RUN_REMOVED" && event.runId != null) {
    removeRunLocally(event.runId);
    return;
  }
  if (type === "ITEM_REMOVED" && event.itemId != null) {
    queueRows.value = queueRows.value.filter((item) => String(item.id) !== String(event.itemId));
  }
  if (event.run) applyRunChanged(event.run, event.items ?? []);
}

function applyRunChanged(run: FileTransferRunView, changedItems: FileTransferRunItemView[]) {
  if (!run.id || String(run.triggerType ?? "").toUpperCase() !== "MANUAL") return;
  const runId = String(run.id);
  const nextRuns = recentRuns.value.filter((item) => String(item.id) !== runId);
  nextRuns.unshift(run);
  recentRuns.value = selectQueueRuns(nextRuns);
  const visibleRunIds = new Set(recentRuns.value.map((item) => String(item.id)));
  let rows = queueRows.value.filter((row) => visibleRunIds.has(String(row.runId)));
  const existingRunRows = rows.filter((row) => String(row.runId) === runId);
  if (changedItems.length) {
    const changedKeys = new Set(changedItems.map((item) => String(item.id ?? item.coreItemId)));
    rows = rows.filter((row) => String(row.runId) !== runId
      || (!row.synthetic && !changedKeys.has(String(row.id ?? row.coreItemId))));
    rows.push(...changedItems.map((item) => toQueueRow(run, item)));
  } else if (!existingRunRows.length) {
    rows.push(...manualSnapshotRows(run));
  }
  queueRows.value = rows.map((row) => String(row.runId) === runId
    ? {
        ...row,
        runStatus: run.status,
        runMessage: run.message,
        status: row.synthetic ? (run.status === "RUNNING" ? "DISCOVERING" : run.status) : row.status,
        currentBytesPerSecond: row.synthetic ? run.currentBytesPerSecond : row.currentBytesPerSecond,
      }
    : row);
}

function removeRunLocally(runId: EntityId) {
  recentRuns.value = recentRuns.value.filter((run) => String(run.id) !== String(runId));
  queueRows.value = queueRows.value.filter((row) => String(row.runId) !== String(runId));
}

function showError(error: unknown, fallback: string) {
  ElMessage.error(error instanceof Error && error.message ? error.message : fallback);
}

onMounted(async () => {
  unsubscribeEvents = subscribeFileTransferEvents(applyFileTransferEvent);
  try {
    await Promise.all([loadRuntimeOptions(), loadQueue()]);
  } catch (error) {
    showError(error, "加载文件传输中心失败");
  } finally {
    initialQueueLoadPending = false;
  }
});

onBeforeUnmount(() => unsubscribeEvents?.());
</script>

<style scoped>
.transfer-center {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.transfer-cluster-picker {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.transfer-cluster-picker > span {
  color: var(--studio-text-soft);
  font-size: 13px;
  white-space: nowrap;
}

.transfer-cluster-picker__select {
  width: min(320px, 100%);
}

.transfer-browser-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 56px minmax(0, 1fr);
  gap: 10px;
  align-items: stretch;
  min-width: 0;
}

.transfer-directions {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.transfer-queue {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding-top: 2px;
}

.transfer-queue__header,
.transfer-queue__header > div {
  display: flex;
  align-items: center;
  gap: 10px;
}

.transfer-queue__header {
  justify-content: space-between;
}

.transfer-queue__header > div:first-child {
  min-width: 0;
}

.transfer-queue__header span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.transfer-queue__tabs {
  min-width: 0;
}

.transfer-queue__tabs :deep(.el-tabs__header) {
  margin: 0;
}

.queue-file-cell,
.queue-route-cell,
.queue-progress-cell {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.queue-progress-cell span {
  color: var(--studio-text-soft);
  font-size: 11px;
  text-align: right;
}

.queue-speed-cell {
  display: grid;
  gap: 2px;
  justify-items: end;
}

.queue-speed-cell small {
  color: var(--studio-text-soft);
  font-size: 11px;
}

.queue-file-cell span,
.queue-endpoint-line:last-child {
  color: var(--studio-text-soft);
}

.queue-file-cell strong,
.queue-endpoint-line span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.queue-endpoint-line {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.queue-endpoint-line b {
  color: var(--studio-text-soft);
  font-size: 12px;
  font-weight: 600;
}

.queue-actions {
  display: flex;
  justify-content: center;
  gap: 2px;
}

.policy-fixed-rule {
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  margin: 0 0 18px;
  padding: 10px 12px;
  border: 1px solid var(--studio-border);
  border-radius: 6px;
  color: var(--studio-text-soft);
  font-size: 13px;
}

.policy-fixed-rule strong {
  color: var(--studio-text);
}

@media (max-width: 1100px) {
  .transfer-browser-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .transfer-directions {
    flex-direction: row;
    min-height: 46px;
  }

  .transfer-directions :deep(.el-icon) {
    transform: rotate(90deg);
  }
}

@media (max-width: 600px) {
  .transfer-cluster-picker {
    align-items: stretch;
    flex-direction: column;
  }

  .transfer-cluster-picker__select {
    width: 100%;
  }
}

@media (max-width: 600px) {
  .transfer-queue__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .transfer-queue__header > div:last-child {
    width: 100%;
  }

  .transfer-queue__header > div:last-child .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }

  .policy-fixed-rule {
    grid-template-columns: 1fr auto;
  }

  .policy-fixed-rule span:last-child {
    grid-column: 1 / -1;
  }
}
</style>
