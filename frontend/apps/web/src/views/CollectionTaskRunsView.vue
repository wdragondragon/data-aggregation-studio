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
          target-type="COLLECTION_TASK"
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
            <el-option :label="formatStatusLabel(t, 'RUNNING')" value="RUNNING" />
            <el-option :label="formatStatusLabel(t, 'SUCCESS')" value="SUCCESS" />
            <el-option :label="formatStatusLabel(t, 'FAILED')" value="FAILED" />
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
          <div class="status-metric success">
            <span>{{ t('web.collectionTaskRuns.successRuns') }}</span>
            <strong>{{ successCount }}</strong>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard :title="t('web.collectionTaskRuns.runtimeTitle')" :description="t('web.collectionTaskRuns.runtimeDescription')">
      <div class="table-scroll-shell">
        <el-table :data="pagedRunRecords" border size="small" table-layout="auto" class="task-run-table">
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">
              {{ getPaginatedRowNumber(pagination, $index) }}
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.collectionTask')" min-width="180" show-overflow-tooltip>
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
          <el-table-column :label="t('web.runs.worker')" min-width="150">
            <template #default="{ row }">
              <span>{{ row.workerCode || t("common.none") }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="`${t('web.runs.startedAt')} / ${t('web.runs.duration')}`" min-width="190">
            <template #default="{ row }">
              <div class="stack-cell">
                <span>{{ row.startedAt || t("common.none") }}</span>
                <span class="cell-subtle">{{ row.endedAt || t("common.none") }} · {{ formatDuration(row) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runMetrics.summaryTitle')" min-width="260">
            <template #default="{ row }">
              <div class="run-metric-summary">
                <span>{{ metricLabel(t, 'collectedRecords') }}: {{ formatMetricNumber(row.metricSummary?.collectedRecords) }}</span>
                <span>{{ metricLabel(t, 'successRecords') }}: {{ formatMetricNumber(metricSummaryValue(row.metricSummary, 'successRecords')) }}</span>
                <span>{{ metricLabel(t, 'failedRecords') }}: {{ formatMetricNumber(row.metricSummary?.failedRecords) }}</span>
                <span>{{ metricLabel(t, 'transformerFilterRecords') }}: {{ formatMetricNumber(row.metricSummary?.transformerFilterRecords) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.message')" min-width="240">
            <template #default="{ row }">
              <div class="wrap-cell">{{ row.message || t("common.none") }}</div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.actions')" width="120" align="center" header-align="center">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="!row.id" @click="activeRunRecordId = row.id">
                {{ t("web.runs.viewLog") }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="taskRunTotal"
        />
      </div>
    </SectionCard>

    <RunLogDrawer v-model="logDrawerVisible" :run-record-id="activeRunRecordId" variant="collection-task" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { CollectionTaskDefinitionView, RunRecord } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import FollowToggleButton from "@/components/FollowToggleButton.vue";
import RunLogDrawer from "@/components/RunLogDrawer.vue";
import { getPaginatedRowNumber, useClientPagination } from "@/composables/useClientPagination";
import { formatStatusLabel, resolveProjectName, toneFromStatus } from "@/utils/studio";
import { formatMetricNumber, metricLabel, metricSummaryValue } from "@/utils/runMetrics";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const collectionTasks = ref<CollectionTaskDefinitionView[]>([]);
const allRunRecords = ref<RunRecord[]>([]);
const activeRunRecordId = ref<string | number | undefined>(undefined);
const logDrawerVisible = ref(false);

const filters = ref<{
  collectionTaskId: string;
  status: string;
  timeRange: [string, string] | [];
}>({
  collectionTaskId: "",
  status: "",
  timeRange: [],
});

const filteredRunRecords = computed(() => {
  const normalizedStatus = String(filters.value.status || "").trim().toUpperCase();
  const items = allRunRecords.value.filter((item) => {
    if (item.collectionTaskId == null) {
      return false;
    }
    if (!normalizedStatus) {
      return true;
    }
    return String(item.status || "").trim().toUpperCase() === normalizedStatus;
  });
  return [...items].sort((left, right) => {
    const leftTime = left.startedAt || left.createdAt || "";
    const rightTime = right.startedAt || right.createdAt || "";
    return rightTime.localeCompare(leftTime);
  });
});

const selectedCollectionTask = computed(() =>
  collectionTasks.value.find((item) => String(item.id ?? "") === filters.value.collectionTaskId) ?? null,
);

const { pagination, pagedItems: pagedRunRecords, resetPagination } = useClientPagination(filteredRunRecords);

const taskRunTotal = computed(() => filteredRunRecords.value.length);
const failedCount = computed(() =>
  filteredRunRecords.value.filter((item) => String(item.status ?? "").toUpperCase().includes("FAIL")).length,
);
const runningCount = computed(() =>
  filteredRunRecords.value.filter((item) => String(item.status ?? "").toUpperCase().includes("RUN")).length,
);
const successCount = computed(() =>
  filteredRunRecords.value.filter((item) => String(item.status ?? "").toUpperCase() === "SUCCESS").length,
);

function syncFiltersFromRoute() {
  const collectionTaskId = route.query.collectionTaskId;
  const status = route.query.status;
  const startTime = route.query.startTime;
  const endTime = route.query.endTime;
  filters.value.collectionTaskId = Array.isArray(collectionTaskId) ? collectionTaskId[0] || "" : String(collectionTaskId || "");
  filters.value.status = Array.isArray(status) ? status[0] || "" : String(status || "");
  const startValue = Array.isArray(startTime) ? startTime[0] || "" : String(startTime || "");
  const endValue = Array.isArray(endTime) ? endTime[0] || "" : String(endTime || "");
  filters.value.timeRange = startValue && endValue ? [startValue, endValue] : [];
}

async function loadCollectionTasks() {
  try {
    collectionTasks.value = await studioApi.collectionTasks.list();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.loadFailed"));
  }
}

async function loadTaskRuns() {
  try {
    const response = await studioApi.runs.list({
      collectionTaskId: filters.value.collectionTaskId || undefined,
      startTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[0] : undefined,
      endTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[1] : undefined,
    });
    allRunRecords.value = response.runRecords;
    resetPagination();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.runs.loadFailed"));
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
  if (filters.value.timeRange.length === 2) {
    query.startTime = filters.value.timeRange[0];
    query.endTime = filters.value.timeRange[1];
  }
  pagination.page = 1;
  router.push({ path: "/collection-task-runs", query });
}

function resetFilters() {
  filters.value.collectionTaskId = "";
  filters.value.status = "";
  filters.value.timeRange = [];
  pagination.page = 1;
  router.push({ path: "/collection-task-runs" });
}

function resolveProjectLabel(projectId?: string | number | null) {
  return resolveProjectName(authStore.projects, projectId);
}

function openCollectionTask(taskId: string | number) {
  router.push(`/collection-tasks/${taskId}/edit`);
}

function formatDuration(record: RunRecord) {
  const durationMs = Number(record.resultJson?.durationMs ?? record.payloadJson?.durationMs);
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
  }
});

onMounted(async () => {
  syncFiltersFromRoute();
  await Promise.all([loadCollectionTasks(), loadTaskRuns()]);
});

watch(
  () => route.fullPath,
  () => {
    syncFiltersFromRoute();
    pagination.page = 1;
    void loadTaskRuns();
  },
);

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], async () => {
  if (authStore.isAuthenticated) {
    pagination.page = 1;
    await Promise.all([loadCollectionTasks(), loadTaskRuns()]);
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

.run-link {
  padding: 0;
  font-weight: 600;
}

.table-scroll-shell {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
}

.task-run-table {
  width: 100%;
  min-width: 1180px;
}

.task-run-table :deep(.cell) {
  white-space: normal;
}

.stack-cell,
.wrap-cell,
.run-metric-summary {
  display: grid;
  gap: 4px;
  line-height: 1.45;
  word-break: break-word;
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
