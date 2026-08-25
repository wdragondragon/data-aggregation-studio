<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.collectionTaskRuns.heading") }}</h3>
        <p>{{ t("web.collectionTaskRuns.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <FollowToggleButton
          v-if="selectedCollectionTask?.id"
          :target-type="STUDIO_RESOURCE_TYPE.COLLECTION_TASK"
          :target-id="selectedCollectionTask.id"
        />
        <el-button type="primary" plain @click="loadTaskRuns">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.collectionTaskRuns.queryTitle')" :description="t('web.collectionTaskRuns.queryDescription')">
      <div class="runs-panel">
        <div class="runs-query-grid">
          <el-select
            v-model="filters.collectionTaskId"
            clearable
            filterable
            :placeholder="t('web.collectionTaskRuns.taskFilterPlaceholder')"
          >
            <el-option
              v-for="item in collectionTasks"
              :key="String(item.id)"
              :label="`${item.name} / ${resolveProjectLabel(item.projectId)}`"
              :value="String(item.id)"
            />
          </el-select>
          <el-select
            v-model="filters.status"
            clearable
            :placeholder="t('web.collectionTaskRuns.statusFilterPlaceholder')"
          >
            <el-option :label="t('web.collectionTaskRuns.statusFilterAll')" value="" />
            <el-option :label="formatStatusLabel(t, STUDIO_RUN_STATUS.RUNNING)" :value="STUDIO_RUN_STATUS.RUNNING" />
            <el-option :label="formatStatusLabel(t, STUDIO_RUN_STATUS.SUCCESS)" :value="STUDIO_RUN_STATUS.SUCCESS" />
            <el-option :label="formatStatusLabel(t, STUDIO_RUN_STATUS.FAILED)" :value="STUDIO_RUN_STATUS.FAILED" />
          </el-select>
          <el-select v-model="filters.requestedClusterId" clearable filterable :placeholder="t('web.runtimeClusterSelection.targetCluster')">
            <el-option v-for="item in runtimeClusters" :key="`target-${String(item.id)}`" :label="clusterLabel(item.id, item.code)" :value="String(item.id)" />
          </el-select>
          <el-select v-model="filters.actualClusterId" clearable filterable :placeholder="t('web.runtimeClusterSelection.actualCluster')">
            <el-option v-for="item in runtimeClusters" :key="`actual-${String(item.id)}`" :label="clusterLabel(item.id, item.code)" :value="String(item.id)" />
          </el-select>
          <el-date-picker
            v-model="filters.timeRange"
            type="datetimerange"
            unlink-panels
            clearable
            value-format="YYYY-MM-DD HH:mm:ss"
            range-separator="~"
            :start-placeholder="t('web.collectionTaskRuns.timeRangeStart')"
            :end-placeholder="t('web.collectionTaskRuns.timeRangeEnd')"
          />
          <div class="runs-query-actions">
            <el-button type="primary" @click="applyFilters">{{ t("common.search") }}</el-button>
            <el-button plain @click="resetFilters">{{ t("common.reset") }}</el-button>
          </div>
        </div>
        <div class="status-strip">
          <div class="status-metric">
            <span>{{ t('web.collectionTaskRuns.taskRuns') }}</span>
            <strong>{{ taskRunTotal }}</strong>
          </div>
          <div class="status-metric danger">
            <span>{{ t('web.runs.failed') }}</span>
            <strong>{{ failedCount }}</strong>
          </div>
          <div class="status-metric running">
            <span>{{ t('web.collectionTaskRuns.runningRuns') }}</span>
            <strong>{{ runningCount }}</strong>
          </div>
          <div class="status-metric queued">
            <span>{{ t('web.collectionTaskRuns.queuedTasks') }}</span>
            <strong>{{ queuedTasks.length }}</strong>
          </div>
          <div class="status-metric success">
            <span>{{ t('web.collectionTaskRuns.successRuns') }}</span>
            <strong>{{ successCount }}</strong>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard
      v-if="queuedTasks.length > 0"
      :title="t('web.collectionTaskRuns.queuedTitle')"
      :description="t('web.collectionTaskRuns.queuedDescription')"
    >
      <StudioTableShell min-width="1080px">
        <el-table :data="queuedTasks" border size="small" table-layout="fixed" class="task-run-table">
          <el-table-column :label="t('web.runs.collectionTask')" min-width="280">
            <template #default="{ row }">
              <div class="stack-cell">
                <el-button
                  v-if="row.collectionTaskId"
                  link
                  type="primary"
                  class="run-link"
                  @click="openCollectionTask(row.collectionTaskId)"
                >
                  {{ row.collectionTaskName || '--' }}
                </el-button>
                <span v-else>{{ row.collectionTaskName || '--' }}</span>
                <span class="cell-subtle">{{ resolveProjectLabel(row.projectId) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.status')" width="108" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill :label="formatStatusLabel(t, row.status)" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runtimeClusterSelection.targetCluster')" min-width="220">
            <template #default="{ row }">
              {{ clusterLabel(row.targetClusterId) }}
            </template>
          </el-table-column>
          <el-table-column :label="t('web.collectionTaskRuns.queuedAt')" width="190">
            <template #default="{ row }">
              {{ row.createdAt || t('common.none') }}
            </template>
          </el-table-column>
          <el-table-column :label="t('web.collectionTaskRuns.dispatchState')" min-width="240">
            <template #default="{ row }">
              <div class="stack-cell">
                <span>{{ row.workerGroupCode || row.leaseOwner || t('common.none') }}</span>
                <span class="cell-subtle">{{ t('web.collectionTaskRuns.dispatchAttempts', { attempts: row.attempts || 0, maxRetries: row.maxRetries || 0 }) }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </StudioTableShell>
    </SectionCard>

    <SectionCard :title="t('web.collectionTaskRuns.runtimeTitle')" :description="t('web.collectionTaskRuns.runtimeDescription')">
      <StudioTableShell min-width="1480px">
        <el-table
          :data="pagedRunRecords"
          border
          size="small"
          table-layout="fixed"
          scrollbar-always-on
          max-height="calc(100vh - 360px)"
          class="task-run-table"
        >
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">
              {{ getPaginatedRowNumber(pagination, $index) }}
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.collectionTask')" min-width="260">
            <template #default="{ row }">
              <div class="stack-cell">
                <el-button
                  v-if="row.collectionTaskId"
                  link
                  type="primary"
                  class="run-link"
                  @click="openCollectionTask(row.collectionTaskId)"
                >
                  {{ row.collectionTaskName || "--" }}
                </el-button>
                <span v-else>{{ row.collectionTaskName || "--" }}</span>
                <span class="cell-subtle">{{ resolveProjectLabel(row.projectId) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.status')" width="108" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill :label="formatStatusLabel(t, row.status)" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.worker')" width="170" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono-ellipsis">{{ row.workerGroupCode || row.workerCode || t("common.none") }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runtimeClusterSelection.runtimePlacement')" min-width="220">
            <template #default="{ row }">
              <div class="stack-cell">
                <span>{{ t("web.runtimeClusterSelection.targetCluster") }}: {{ clusterLabel(row.requestedClusterId) }}</span>
                <span class="cell-subtle">{{ t("web.runtimeClusterSelection.actualCluster") }}: {{ clusterLabel(row.actualClusterId, row.actualClusterCode) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="`${t('web.runs.startedAt')} / ${t('web.runs.duration')}`" width="200">
            <template #default="{ row }">
              <div class="stack-cell">
                <span>{{ row.startedAt || t("common.none") }}</span>
                <span class="cell-subtle">{{ row.endedAt || t("common.none") }} · {{ formatDuration(row) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runMetrics.summaryTitle')" width="260">
            <template #default="{ row }">
              <div class="run-metric-summary">
                <span class="run-metric-chip">{{ metricLabel(t, 'collectedRecords') }}: {{ formatMetricNumber(row.metricSummary?.collectedRecords) }}</span>
                <span class="run-metric-chip">{{ metricLabel(t, 'successRecords') }}: {{ formatMetricNumber(metricSummaryValue(row.metricSummary, 'successRecords')) }}</span>
                <span class="run-metric-chip">{{ metricLabel(t, 'failedRecords') }}: {{ formatMetricNumber(row.metricSummary?.failedRecords) }}</span>
                <span class="run-metric-chip">{{ metricLabel(t, 'transformerFilterRecords') }}: {{ formatMetricNumber(row.metricSummary?.transformerFilterRecords) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.message')" min-width="300">
            <template #default="{ row }">
              <MessagePreviewText :text="displayRunMessage(row)" :empty-text="t('common.none')" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.actions')" width="220" align="center" header-align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="!row.id" @click="activeRunRecordId = row.id">
                {{ t("web.runs.viewLog") }}
              </el-button>
              <el-button
                v-if="String(row.status || '').toUpperCase() === 'RUNNING'"
                link
                type="danger"
                :loading="isRunTerminating(row.id)"
                :disabled="!row.id"
                @click="terminateRunRecord(row)"
              >
                {{ t("web.collectionTaskRuns.terminateRun") }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </StudioTableShell>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="taskRunTotal"
          @current-change="handleCurrentPageChange"
          @size-change="handlePageSizeChange"
        />
      </div>
    </SectionCard>

    <RunLogDrawer v-model="logDrawerVisible" :run-record-id="activeRunRecordId" variant="collection-task" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { CollectionTaskOptionView, EntityId, QueuedTaskListView, RunRecordListView, RuntimeClusterView } from "@studio/api-sdk";
import { SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import FollowToggleButton from "@/components/FollowToggleButton.vue";
import MessagePreviewText from "@/components/MessagePreviewText.vue";
import RunLogDrawer from "@/components/RunLogDrawer.vue";
import { getPaginatedRowNumber } from "@/composables/useClientPagination";
import { usePageQuery } from "@/composables/usePageQuery";
import { STUDIO_RESOURCE_TYPE, STUDIO_RUN_STATUS } from "@/constants/studioDomain";
import { formatStatusLabel, resolveProjectName, toneFromStatus } from "@/utils/studio";
import { formatMetricNumber, metricLabel, metricSummaryValue } from "@/utils/runMetrics";
import { formatRuntimeClusterLabel } from "@/utils/runtimeClusters";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const collectionTasks = ref<CollectionTaskOptionView[]>([]);
const queuedTasks = ref<QueuedTaskListView[]>([]);
const pagedRunRecords = ref<RunRecordListView[]>([]);
const taskRunTotal = ref(0);
const failedCount = ref(0);
const runningCount = ref(0);
const successCount = ref(0);
const activeRunRecordId = ref<string | number | undefined>(undefined);
const logDrawerVisible = ref(false);
const runtimeClusters = ref<RuntimeClusterView[]>([]);
const terminatingRunIds = ref<Set<string>>(new Set());

const filters = ref<{
  collectionTaskId: string;
  status: string;
  requestedClusterId: string;
  actualClusterId: string;
  timeRange: [string, string] | [];
}>({
  collectionTaskId: "",
  status: "",
  requestedClusterId: "",
  actualClusterId: "",
  timeRange: [],
});

const selectedCollectionTask = computed(() =>
  collectionTasks.value.find((item) => String(item.id ?? "") === filters.value.collectionTaskId) ?? null,
);

const { pagination, resetPage, setPage, setPageSize, ensureValidPage } = usePageQuery(10);

function syncFiltersFromRoute() {
  const collectionTaskId = route.query.collectionTaskId;
  const runRecordId = route.query.runRecordId;
  const status = route.query.status;
  const requestedClusterId = route.query.requestedClusterId;
  const actualClusterId = route.query.actualClusterId;
  const startTime = route.query.startTime;
  const endTime = route.query.endTime;
  filters.value.collectionTaskId = Array.isArray(collectionTaskId) ? collectionTaskId[0] || "" : String(collectionTaskId || "");
  const runRecordValue = Array.isArray(runRecordId) ? runRecordId[0] : runRecordId;
  activeRunRecordId.value = runRecordValue == null || runRecordValue === "" ? undefined : String(runRecordValue);
  filters.value.status = Array.isArray(status) ? status[0] || "" : String(status || "");
  filters.value.requestedClusterId = Array.isArray(requestedClusterId) ? requestedClusterId[0] || "" : String(requestedClusterId || "");
  filters.value.actualClusterId = Array.isArray(actualClusterId) ? actualClusterId[0] || "" : String(actualClusterId || "");
  const startValue = Array.isArray(startTime) ? startTime[0] || "" : String(startTime || "");
  const endValue = Array.isArray(endTime) ? endTime[0] || "" : String(endTime || "");
  filters.value.timeRange = startValue && endValue ? [startValue, endValue] : [];
}

async function loadCollectionTasks() {
  try {
    collectionTasks.value = await studioApi.collectionTasks.options();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.loadFailed"));
  }
}

async function loadRuntimeClusters() {
  try {
    runtimeClusters.value = await studioApi.runtimeClusters.options();
  } catch {
    runtimeClusters.value = [];
  }
}

async function loadTaskRuns() {
  try {
    const query = {
      collectionTaskId: filters.value.collectionTaskId || undefined,
      requestedClusterId: filters.value.requestedClusterId || undefined,
      actualClusterId: filters.value.actualClusterId || undefined,
      startTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[0] : undefined,
      endTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[1] : undefined,
    };
    const [response, activeResponse] = await Promise.all([
      studioApi.runs.listPage({
        ...query,
        collectionTaskOnly: true,
        status: filters.value.status || undefined,
        pageNo: pagination.page,
        pageSize: pagination.pageSize,
      }),
      studioApi.runs.list({
        ...query,
        includeRunRecords: false,
      }),
    ]);
    queuedTasks.value = activeResponse.queuedTasks.filter((item) => String(item.status ?? "").toUpperCase() === STUDIO_RUN_STATUS.QUEUED);
    pagedRunRecords.value = response.items;
    taskRunTotal.value = Number(response.total ?? 0);
    failedCount.value = Number(response.failedCount ?? 0);
    runningCount.value = Number(response.runningCount ?? 0);
    successCount.value = Number(response.successCount ?? 0);
    if (ensureValidPage(taskRunTotal.value)) {
      return void loadTaskRuns();
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.runs.loadFailed"));
  }
}

function isRunTerminating(id?: string | number) {
  return id != null && terminatingRunIds.value.has(String(id));
}

function displayRunMessage(row: RunRecordListView) {
  return row.message === "Manually terminated by user"
    ? t("web.collectionTasks.manualTerminationReason")
    : row.message;
}

function terminationErrorMessage(error: unknown) {
  const message = error instanceof Error ? error.message : "";
  if (message.includes("No queued or running instance exists")) {
    return t("web.collectionTasks.noActiveInstance");
  }
  if (message.includes("Multiple active instances exist")) {
    return t("web.collectionTasks.multipleActiveInstances");
  }
  return message || t("web.collectionTaskRuns.terminateRunFailed");
}

async function terminateRunRecord(row: RunRecordListView) {
  if (!row.id || String(row.status || "").toUpperCase() !== "RUNNING") {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.collectionTaskRuns.terminateRunConfirm"),
      t("common.confirm"),
      { type: "warning" },
    );
    const key = String(row.id);
    terminatingRunIds.value = new Set([...terminatingRunIds.value, key]);
    const result = await studioApi.runs.terminate(row.id);
    pagedRunRecords.value = pagedRunRecords.value.map((item) => String(item.id) === key
      ? { ...item, status: result.status || "FAILED", message: result.message || item.message }
      : item);
    ElMessage.success(t("web.collectionTaskRuns.terminateRunSuccess"));
    await loadTaskRuns();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(terminationErrorMessage(error));
    }
  } finally {
    const key = row.id == null ? null : String(row.id);
    if (key != null) {
      const next = new Set(terminatingRunIds.value);
      next.delete(key);
      terminatingRunIds.value = next;
    }
  }
}

function applyFilters() {
  const query: Record<string, string> = {};
  if (filters.value.collectionTaskId) {
    query.collectionTaskId = filters.value.collectionTaskId;
  }
  if (filters.value.status) {
    query.status = filters.value.status;
  }
  if (filters.value.requestedClusterId) query.requestedClusterId = filters.value.requestedClusterId;
  if (filters.value.actualClusterId) query.actualClusterId = filters.value.actualClusterId;
  if (filters.value.timeRange.length === 2) {
    query.startTime = filters.value.timeRange[0];
    query.endTime = filters.value.timeRange[1];
  }
  resetPage();
  void navigateOrReload(query);
}

function resetFilters() {
  filters.value.collectionTaskId = "";
  filters.value.status = "";
  filters.value.requestedClusterId = "";
  filters.value.actualClusterId = "";
  filters.value.timeRange = [];
  resetPage();
  void navigateOrReload({});
}

async function navigateOrReload(query: Record<string, string>) {
  const target = { path: "/collection-task-runs", query };
  const resolved = router.resolve(target);
  if (resolved.fullPath === route.fullPath) {
    await loadTaskRuns();
    return;
  }
  await router.push(target);
}

function handleCurrentPageChange(page: number) {
  setPage(page);
  void loadTaskRuns();
}

function handlePageSizeChange(pageSize: number) {
  setPageSize(pageSize);
  void loadTaskRuns();
}

function resolveProjectLabel(projectId?: string | number | null) {
  return resolveProjectName(authStore.projects, projectId);
}

function clusterLabel(clusterId?: EntityId, clusterCode?: string) {
  return formatRuntimeClusterLabel(runtimeClusters.value, clusterId, clusterCode);
}

function openCollectionTask(taskId: string | number) {
  router.push(`/collection-tasks/${taskId}/edit`);
}

function formatDuration(record: RunRecordListView) {
  const durationMs = Number(record.durationMs);
  if (Number.isFinite(durationMs) && durationMs >= 0) {
    return humanizeDuration(durationMs);
  }
  if (!record.startedAt || !record.endedAt) {
    return t("common.none");
  }
  const started = Date.parse(record.startedAt.replace(" ", "T"));
  const ended = Date.parse(record.endedAt.replace(" ", "T"));
  if (Number.isNaN(started) || Number.isNaN(ended) || ended < started) {
    return t("common.none");
  }
  return humanizeDuration(ended - started);
}

function humanizeDuration(durationMs: number) {
  if (durationMs < 1000) {
    return `${durationMs} ms`;
  }
  const seconds = durationMs / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(seconds >= 10 ? 1 : 2)} s`;
  }
  const minutes = Math.floor(seconds / 60);
  const remainderSeconds = Math.round(seconds % 60);
  return `${minutes}m ${remainderSeconds}s`;
}

watch(activeRunRecordId, (value) => {
  logDrawerVisible.value = value != null;
});

watch(logDrawerVisible, (value) => {
  if (!value) {
    activeRunRecordId.value = undefined;
    if (route.query.runRecordId != null) {
      const query = { ...route.query };
      delete query.runRecordId;
      void router.replace({ query });
    }
  }
});

onMounted(async () => {
  syncFiltersFromRoute();
  await Promise.all([loadCollectionTasks(), loadRuntimeClusters(), loadTaskRuns()]);
});

watch(
  () => route.fullPath,
  () => {
    syncFiltersFromRoute();
    resetPage();
    void loadTaskRuns();
  },
);

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], async () => {
  if (authStore.isAuthenticated) {
    resetPage();
    await Promise.all([loadCollectionTasks(), loadRuntimeClusters(), loadTaskRuns()]);
  }
});
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.runs-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.runs-query-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr) minmax(0, 2fr) auto;
  gap: 8px;
  align-items: center;
  width: 100%;
  min-width: 0;
}

.runs-query-grid > * {
  min-width: 0;
}

.runs-query-grid :deep(.el-select),
.runs-query-grid :deep(.el-date-editor) {
  width: 100%;
}

.runs-query-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.status-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  width: 100%;
  min-width: 0;
}

.status-metric {
  padding: 8px 10px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  border-radius: 12px;
  background: rgba(16, 78, 139, 0.05);
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.status-metric span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.status-metric strong {
  color: var(--studio-text);
  font-size: 16px;
  line-height: 1;
}

.status-metric.success {
  background: rgba(28, 126, 84, 0.08);
}

.status-metric.danger {
  background: rgba(193, 52, 52, 0.08);
}

.status-metric.running {
  background: rgba(37, 99, 235, 0.08);
}

.status-metric.queued {
  background: rgba(196, 122, 15, 0.1);
}

.run-link {
  align-items: flex-start;
  justify-content: flex-start;
  height: auto;
  max-width: 100%;
  padding: 0;
  font-weight: 600;
  line-height: 1.35;
  text-align: left;
  white-space: normal;
}

.run-link :deep(span) {
  display: inline;
  min-width: 0;
  max-width: 100%;
  overflow-wrap: anywhere;
  white-space: normal;
}

.task-run-table {
  width: 100%;
}

.task-run-table :deep(.cell) {
  white-space: normal;
}

.stack-cell,
.wrap-cell {
  display: grid;
  gap: 4px;
  line-height: 1.45;
  word-break: break-word;
}

.run-metric-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: flex-start;
}

.run-metric-chip {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(16, 78, 139, 0.08);
  color: var(--studio-text);
  font-size: 12px;
  line-height: 1.4;
  white-space: nowrap;
}

.mono-ellipsis {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", Menlo, monospace;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

@media (max-width: 1480px) {
  .runs-query-grid,
  .status-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .runs-query-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 1100px) {
  .runs-query-grid,
  .status-strip {
    grid-template-columns: minmax(0, 1fr);
  }

  .runs-query-actions {
    justify-content: flex-start;
  }

  .table-pagination {
    justify-content: flex-start;
  }
}
</style>
