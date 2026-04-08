<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.runs.heading") }}</h3>
        <p>{{ t("web.runs.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" plain @click="loadWorkflowRuns">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.runs.queryTitle')" :description="t('web.runs.queryDescription')">
      <div class="runs-panel">
        <div class="runs-query-grid">
          <el-select
            v-model="filters.workflowDefinitionId"
            clearable
            filterable
            :placeholder="t('web.runs.workflowFilterPlaceholder')"
          >
            <el-option
              v-for="item in workflows"
              :key="String(item.id)"
              :label="item.name"
              :value="String(item.id)"
            />
          </el-select>
          <el-select
            v-model="filters.status"
            clearable
            :placeholder="t('web.runs.statusFilterPlaceholder')"
          >
            <el-option :label="t('web.runs.statusFilterAll')" value="" />
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
            :start-placeholder="t('web.runs.timeRangeStart')"
            :end-placeholder="t('web.runs.timeRangeEnd')"
          />
          <div class="runs-query-actions">
            <el-button type="primary" @click="applyFilters">{{ t("common.search") }}</el-button>
            <el-button plain @click="resetFilters">{{ t("common.reset") }}</el-button>
          </div>
        </div>
        <div class="status-strip">
          <div class="status-metric">
            <span>{{ t('web.runs.workflowRuns') }}</span>
            <strong>{{ workflowRunTotal }}</strong>
          </div>
          <div class="status-metric danger">
            <span>{{ t('web.runs.failed') }}</span>
            <strong>{{ failedCount }}</strong>
          </div>
          <div class="status-metric running">
            <span>{{ t('web.runs.runningNodes') }}</span>
            <strong>{{ runningCount }}</strong>
          </div>
          <div class="status-metric success">
            <span>{{ t('web.runs.successNodes') }}</span>
            <strong>{{ successCount }}</strong>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard :title="t('web.runs.runtimeTitle')" :description="t('web.runs.runtimeDescription')">
      <el-table :data="workflowRuns" border size="small" table-layout="auto" class="workflow-run-table">
        <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
          <template #default="{ $index }">
            {{ getPaginatedRowNumber(pagination, $index) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('web.runs.workflow')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button link type="primary" class="run-link" @click="openRunDetail(row)">
              {{ row.workflowName || "--" }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.runs.status')" width="120" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="formatStatusLabel(t, row.status)" :tone="toneFromStatus(row.status)" />
          </template>
        </el-table-column>
        <el-table-column :label="`${t('web.runs.startedAt')} / ${t('web.runs.duration')}`" min-width="220">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ row.startedAt || t("common.none") }}</span>
              <span class="cell-subtle">{{ row.endedAt || t("common.none") }} · {{ formatDurationMs(row.durationMs) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="`${t('web.runs.detailNodeStats')} / ${t('web.runs.summaryMessage')}`" min-width="320">
          <template #default="{ row }">
            <div class="stats-cell">
              <span v-for="item in formatNodeStats(row)" :key="item">{{ item }}</span>
              <span class="cell-subtle">{{ row.summaryMessage || t("common.none") }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.runs.actions')" width="140" align="center" header-align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRunDetail(row)">{{ t("web.runs.viewRunDetail") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="workflowRunTotal"
          @current-change="handleCurrentPageChange"
          @size-change="handlePageSizeChange"
        />
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { WorkflowDefinitionView, WorkflowRunSummary } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { getPaginatedRowNumber } from "@/composables/useClientPagination";
import { formatStatusLabel, toneFromStatus } from "@/utils/studio";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const workflows = ref<WorkflowDefinitionView[]>([]);
const workflowRuns = ref<WorkflowRunSummary[]>([]);
const workflowRunTotal = ref(0);
const pagination = reactive({
  page: 1,
  pageSize: 10,
});
const filters = ref<{
  workflowDefinitionId: string;
  status: string;
  timeRange: [string, string] | [];
}>({
  workflowDefinitionId: "",
  status: "",
  timeRange: [],
});

const failedCount = computed(() =>
  workflowRuns.value.filter((item) => String(item.status ?? "").toUpperCase().includes("FAIL")).length,
);
const runningCount = computed(() =>
  workflowRuns.value.reduce((total, item) => total + Number(item.runningNodes || 0), 0),
);
const successCount = computed(() =>
  workflowRuns.value.reduce((total, item) => total + Number(item.successNodes || 0), 0),
);

function syncFiltersFromRoute() {
  const workflowDefinitionId = route.query.workflowDefinitionId;
  const status = route.query.status;
  const startTime = route.query.startTime;
  const endTime = route.query.endTime;
  filters.value.workflowDefinitionId = Array.isArray(workflowDefinitionId) ? workflowDefinitionId[0] || "" : String(workflowDefinitionId || "");
  filters.value.status = Array.isArray(status) ? status[0] || "" : String(status || "");
  const startValue = Array.isArray(startTime) ? startTime[0] || "" : String(startTime || "");
  const endValue = Array.isArray(endTime) ? endTime[0] || "" : String(endTime || "");
  filters.value.timeRange = startValue && endValue ? [startValue, endValue] : [];
}

async function loadWorkflows() {
  try {
    workflows.value = await studioApi.workflows.list();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  }
}

async function loadWorkflowRuns() {
  try {
    const response = await studioApi.workflowRuns.list({
      workflowDefinitionId: filters.value.workflowDefinitionId || undefined,
      status: filters.value.status || undefined,
      startTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[0] : undefined,
      endTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[1] : undefined,
      pageNo: pagination.page,
      pageSize: pagination.pageSize,
    });
    workflowRuns.value = response.items;
    workflowRunTotal.value = Number(response.total ?? 0);
    const maxPage = Math.max(1, Math.ceil(workflowRunTotal.value / pagination.pageSize));
    if (pagination.page > maxPage) {
      pagination.page = maxPage;
      return void loadWorkflowRuns();
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.runs.loadFailed"));
  }
}

function applyFilters() {
  const query: Record<string, string> = {};
  if (filters.value.workflowDefinitionId) {
    query.workflowDefinitionId = filters.value.workflowDefinitionId;
  }
  if (filters.value.status) {
    query.status = filters.value.status;
  }
  if (filters.value.timeRange.length === 2) {
    query.startTime = filters.value.timeRange[0];
    query.endTime = filters.value.timeRange[1];
  }
  pagination.page = 1;
  router.push({ path: "/runs", query });
}

function resetFilters() {
  filters.value.workflowDefinitionId = "";
  filters.value.status = "";
  filters.value.timeRange = [];
  pagination.page = 1;
  router.push({ path: "/runs" });
}

function handleCurrentPageChange(page: number) {
  pagination.page = page;
  void loadWorkflowRuns();
}

function handlePageSizeChange(pageSize: number) {
  pagination.pageSize = pageSize;
  pagination.page = 1;
  void loadWorkflowRuns();
}

function openRunDetail(item: WorkflowRunSummary) {
  if (!item.workflowRunId) {
    return;
  }
  router.push({
    path: `/runs/${item.workflowRunId}`,
    query: {
      workflowDefinitionId: item.workflowDefinitionId ? String(item.workflowDefinitionId) : undefined,
    },
  });
}

function formatDurationMs(durationMs?: number) {
  if (durationMs == null || Number.isNaN(Number(durationMs))) {
    return t("common.none");
  }
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

function formatNodeStats(item: WorkflowRunSummary) {
  return [
    `${t("web.runs.successNodes")}: ${item.successNodes || 0}`,
    `${t("web.runs.failedNodes")}: ${item.failedNodes || 0}`,
    `${t("web.runs.runningNodes")}: ${item.runningNodes || 0}`,
    `${t("web.runs.notRunNodes")}: ${item.notRunNodes || 0}`,
  ];
}

onMounted(async () => {
  syncFiltersFromRoute();
  await Promise.all([loadWorkflows(), loadWorkflowRuns()]);
});

watch(
  () => route.fullPath,
  () => {
    syncFiltersFromRoute();
    pagination.page = 1;
    void loadWorkflowRuns();
  },
);
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
}

.runs-query-grid {
  display: grid;
  grid-template-columns: 220px 160px minmax(260px, 1fr) auto;
  gap: 8px;
  align-items: center;
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
}

.status-metric {
  padding: 8px 10px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  border-radius: 12px;
  background: rgba(16, 78, 139, 0.05);
  display: flex;
  flex-direction: column;
  gap: 4px;
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

.workflow-run-table :deep(.cell) {
  white-space: normal;
}

.stats-cell,
.stack-cell,
.wrap-cell {
  display: grid;
  gap: 4px;
  line-height: 1.45;
  word-break: break-word;
}

.stack-cell--center {
  justify-items: center;
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
