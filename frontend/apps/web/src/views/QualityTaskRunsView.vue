<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>质量任务日志</h3>
        <p>查看质量任务运行历史，再按统一抽屉方式下钻单次运行日志和下载入口。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/quality-metrics')">质量指标</el-button>
        <el-button @click="router.push('/quality-tasks')">返回任务列表</el-button>
        <el-button type="primary" plain @click="loadTaskRuns">刷新</el-button>
      </div>
    </div>

    <SectionCard title="运行筛选" description="支持按质量任务、状态和时间范围筛选运行历史。">
      <div class="runs-panel">
        <div class="runs-query-grid">
          <el-select
            v-model="filters.qualityTaskId"
            clearable
            filterable
            placeholder="选择质量任务"
          >
            <el-option
              v-for="item in qualityTasks"
              :key="String(item.id)"
              :label="`${item.taskName} / ${resolveRuleSummaryByTask(item)}`"
              :value="String(item.id)"
            />
          </el-select>
          <el-select v-model="filters.status" clearable placeholder="选择状态">
            <el-option label="全部状态" value="" />
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
            start-placeholder="开始时间"
            end-placeholder="结束时间"
          />
          <div class="runs-query-actions">
            <el-button type="primary" @click="applyFilters">{{ t("common.search") }}</el-button>
            <el-button plain @click="resetFilters">{{ t("common.reset") }}</el-button>
          </div>
        </div>
        <div class="status-strip">
          <div class="status-metric">
            <span>质量任务运行次数</span>
            <strong>{{ taskRunTotal }}</strong>
          </div>
          <div class="status-metric danger">
            <span>{{ t("web.runs.failed") }}</span>
            <strong>{{ failedCount }}</strong>
          </div>
          <div class="status-metric running">
            <span>运行中</span>
            <strong>{{ runningCount }}</strong>
          </div>
          <div class="status-metric success">
            <span>成功</span>
            <strong>{{ successCount }}</strong>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="运行列表" description="每一行代表一次质量任务运行，点击“查看日志”按统一抽屉方式打开日志详情。">
      <div class="table-scroll-shell">
        <el-table
          :data="pagedRunRecords"
          border
          size="small"
          table-layout="fixed"
          scrollbar-always-on
          max-height="calc(100vh - 360px)"
          class="task-run-table"
        >
          <el-table-column label="序号" width="72" align="center" header-align="center">
            <template #default="{ $index }">
              {{ getPaginatedRowNumber(pagination, $index) }}
            </template>
          </el-table-column>
          <el-table-column label="质量任务" min-width="260">
            <template #default="{ row }">
              <div class="table-entity-cell">
                <el-button
                  v-if="row.qualityTaskId"
                  link
                  type="primary"
                  class="run-link table-entity-cell__title"
                  @click="openQualityTask(row.qualityTaskId)"
                >
                  {{ row.qualityTaskName || "--" }}
                </el-button>
                <span v-else class="table-entity-cell__title">{{ row.qualityTaskName || "--" }}</span>
                <span class="table-entity-cell__meta">{{ resolveRuleSummary(row) }}</span>
                <span class="table-entity-cell__meta">{{ resolveProjectLabel(row.projectId) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="108" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill :label="formatStatusLabel(t, row.status)" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column label="执行节点" min-width="150">
            <template #default="{ row }">
              <span>{{ row.workerCode || t("common.none") }}</span>
            </template>
          </el-table-column>
          <el-table-column label="开始 / 耗时" min-width="190">
            <template #default="{ row }">
              <div class="stack-cell">
                <span>{{ row.startedAt || row.createdAt || t("common.none") }}</span>
                <span class="cell-subtle">{{ row.endedAt || t("common.none") }} · {{ formatDuration(row) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="执行摘要" min-width="260">
            <template #default="{ row }">
              <MessagePreviewText :text="row.message" :empty-text="t('common.none')" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="center" header-align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="!row.id" @click="activeRunRecordId = row.id">
                查看日志
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

    <RunLogDrawer v-model="logDrawerVisible" :run-record-id="activeRunRecordId" variant="quality-task" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { QualityTaskDefinitionView, RunRecord } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import MessagePreviewText from "@/components/MessagePreviewText.vue";
import RunLogDrawer from "@/components/RunLogDrawer.vue";
import { getPaginatedRowNumber, useClientPagination } from "@/composables/useClientPagination";
import { formatStatusLabel, resolveProjectName, toneFromStatus } from "@/utils/studio";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const qualityTasks = ref<QualityTaskDefinitionView[]>([]);
const allRunRecords = ref<RunRecord[]>([]);
const activeRunRecordId = ref<string | number | undefined>(undefined);
const logDrawerVisible = ref(false);

const filters = ref<{
  qualityTaskId: string;
  status: string;
  timeRange: [string, string] | [];
}>({
  qualityTaskId: "",
  status: "",
  timeRange: [],
});

const taskMap = computed(() => {
  const map = new Map<string, QualityTaskDefinitionView>();
  for (const item of qualityTasks.value) {
    if (item.id != null) {
      map.set(String(item.id), item);
    }
  }
  return map;
});

const filteredRunRecords = computed(() => {
  const normalizedStatus = String(filters.value.status || "").trim().toUpperCase();
  const items = allRunRecords.value.filter((item) => {
    if (item.qualityTaskId == null) {
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
  const qualityTaskId = route.query.qualityTaskId;
  const status = route.query.status;
  const startTime = route.query.startTime;
  const endTime = route.query.endTime;
  filters.value.qualityTaskId = Array.isArray(qualityTaskId) ? qualityTaskId[0] || "" : String(qualityTaskId || "");
  filters.value.status = Array.isArray(status) ? status[0] || "" : String(status || "");
  const startValue = Array.isArray(startTime) ? startTime[0] || "" : String(startTime || "");
  const endValue = Array.isArray(endTime) ? endTime[0] || "" : String(endTime || "");
  filters.value.timeRange = startValue && endValue ? [startValue, endValue] : [];
}

async function loadQualityTasks() {
  try {
    qualityTasks.value = await studioApi.qualityTasks.list();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量任务失败");
  }
}

async function loadTaskRuns() {
  try {
    const response = await studioApi.runs.list({
      qualityTaskId: filters.value.qualityTaskId || undefined,
      startTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[0] : undefined,
      endTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[1] : undefined,
    });
    allRunRecords.value = response.runRecords.filter((item) => item.qualityTaskId != null);
    resetPagination();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量任务运行记录失败");
  }
}

function applyFilters() {
  const query: Record<string, string> = {};
  if (filters.value.qualityTaskId) {
    query.qualityTaskId = filters.value.qualityTaskId;
  }
  if (filters.value.status) {
    query.status = filters.value.status;
  }
  if (filters.value.timeRange.length === 2) {
    query.startTime = filters.value.timeRange[0];
    query.endTime = filters.value.timeRange[1];
  }
  pagination.page = 1;
  router.push({ path: "/quality-task-runs", query });
}

function resetFilters() {
  filters.value.qualityTaskId = "";
  filters.value.status = "";
  filters.value.timeRange = [];
  pagination.page = 1;
  activeRunRecordId.value = undefined;
  router.push({ path: "/quality-task-runs" });
}

function openQualityTask(taskId: string | number) {
  router.push(`/quality-tasks/${taskId}/edit`);
}

function resolveProjectLabel(projectId?: string | number | null) {
  return resolveProjectName(authStore.projects, projectId);
}

function resolveRuleSummary(row: RunRecord) {
  if (row.qualityTaskId == null) {
    return "-";
  }
  const task = taskMap.value.get(String(row.qualityTaskId));
  return task ? resolveRuleSummaryByTask(task) : "-";
}

function resolveRuleSummaryByTask(task?: QualityTaskDefinitionView | null) {
  if (!task) {
    return "-";
  }
  const dimensionMap: Record<string, string> = {
    CONSISTENCY: "一致性",
    ACCURACY: "准确性",
    UNIQUENESS: "唯一性",
    TIMELINESS: "及时性",
    COMPLETENESS: "完整性",
    VALIDITY: "有效性",
  };
  const dimension = dimensionMap[String(task.ruleDimension || "")] || task.ruleDimension || "-";
  const granularity = task.granularity === "COLUMN" ? "字段级" : "表级";
  return `${task.ruleName || "-"} / ${dimension} / ${granularity}`;
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
  await Promise.all([loadQualityTasks(), loadTaskRuns()]);
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
    await Promise.all([loadQualityTasks(), loadTaskRuns()]);
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
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr) minmax(0, 2fr) auto;
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

.table-scroll-shell {
  width: 100%;
  min-width: 0;
  overflow: hidden;
}

.task-run-table {
  width: 100%;
  min-width: 0;
}

.task-run-table :deep(.cell) {
  white-space: normal;
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

.stack-cell,
.wrap-cell {
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

  .runs-query-actions,
  .table-pagination {
    justify-content: flex-start;
  }
}
</style>
