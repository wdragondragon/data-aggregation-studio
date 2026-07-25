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
              :label="`${item.name} / ${resolveProjectLabel(item.projectId)}`"
              :value="String(item.id)"
            />
          </el-select>
          <el-select
            v-model="filters.status"
            clearable
            :placeholder="t('web.runs.statusFilterPlaceholder')"
          >
            <el-option :label="t('web.runs.statusFilterAll')" value="" />
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
      <StudioTableShell min-width="1460px">
        <el-table
          :data="workflowRuns"
          border
          size="small"
          table-layout="fixed"
          scrollbar-always-on
          max-height="calc(100vh - 360px)"
          class="workflow-run-table"
        >
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">
              {{ getPaginatedRowNumber(pagination, $index) }}
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.workflow')" min-width="260">
            <template #default="{ row }">
              <el-button link type="primary" class="run-link" @click="openRunDetail(row)">
                {{ row.workflowName || "--" }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="所属项目" min-width="170">
            <template #default="{ row }">
              <div class="stack-cell">
                <span>{{ resolveProjectLabel(row.projectId) }}</span>
                <span class="cell-subtle">{{ isSharedRun(row) ? "共享来源" : "当前项目" }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.status')" width="108" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill :label="formatStatusLabel(t, row.status)" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runtimeClusterSelection.runtimePlacement')" min-width="220">
            <template #default="{ row }">
              <div class="stack-cell">
                <span>{{ t("web.runtimeClusterSelection.targetCluster") }}: {{ workflowTargetClusterLabel(row) }}</span>
                <span class="cell-subtle">{{ t("web.runtimeClusterSelection.actualCluster") }}: {{ clusterLabel(row.actualClusterId, row.actualClusterCode) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="`${t('web.runs.startedAt')} / ${t('web.runs.duration')}`" min-width="190">
            <template #default="{ row }">
              <div class="stack-cell">
                <span>{{ row.startedAt || t("common.none") }}</span>
                <span class="cell-subtle">{{ row.endedAt || t("common.none") }} · {{ formatDurationMs(row.durationMs) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="`${t('web.runs.detailNodeStats')} / ${t('web.runs.summaryMessage')}`" min-width="260">
            <template #default="{ row }">
              <div class="stats-cell">
                <span v-for="item in formatNodeStats(row)" :key="item">{{ item }}</span>
                <MessagePreviewText class="cell-subtle" :text="row.summaryMessage" :empty-text="t('common.none')" />
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runs.actions')" width="112" align="center" header-align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openRunDetail(row)">{{ t("web.runs.viewRunDetail") }}</el-button>
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
          :total="workflowRunTotal"
          @current-change="handleCurrentPageChange"
          @size-change="handlePageSizeChange"
        />
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { EntityId, RuntimeClusterView, WorkflowOptionView, WorkflowRunSummary } from "@studio/api-sdk";
import { SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";
import MessagePreviewText from "@/components/MessagePreviewText.vue";
import { useAuthStore } from "@/stores/auth";
import { getPaginatedRowNumber } from "@/composables/useClientPagination";
import { usePageQuery } from "@/composables/usePageQuery";
import { resolveErrorMessage } from "@/composables/useAsyncAction";
import { STUDIO_RUN_STATUS } from "@/constants/studioDomain";
import { formatStatusLabel, isSharedFromAnotherProject, resolveProjectName, toneFromStatus } from "@/utils/studio";
import { formatRuntimeClusterLabel } from "@/utils/runtimeClusters";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const workflows = ref<WorkflowOptionView[]>([]);
const workflowRuns = ref<WorkflowRunSummary[]>([]);
const runtimeClusters = ref<RuntimeClusterView[]>([]);
const workflowRunTotal = ref(0);
const { pagination, resetPage, setPage, setPageSize, ensureValidPage } = usePageQuery(10);
const filters = ref<{
  workflowDefinitionId: string;
  status: string;
  requestedClusterId: string;
  actualClusterId: string;
  timeRange: [string, string] | [];
}>({
  workflowDefinitionId: "",
  status: "",
  requestedClusterId: "",
  actualClusterId: "",
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
  const requestedClusterId = route.query.requestedClusterId;
  const actualClusterId = route.query.actualClusterId;
  const startTime = route.query.startTime;
  const endTime = route.query.endTime;
  filters.value.workflowDefinitionId = Array.isArray(workflowDefinitionId) ? workflowDefinitionId[0] || "" : String(workflowDefinitionId || "");
  filters.value.status = Array.isArray(status) ? status[0] || "" : String(status || "");
  filters.value.requestedClusterId = Array.isArray(requestedClusterId) ? requestedClusterId[0] || "" : String(requestedClusterId || "");
  filters.value.actualClusterId = Array.isArray(actualClusterId) ? actualClusterId[0] || "" : String(actualClusterId || "");
  const startValue = Array.isArray(startTime) ? startTime[0] || "" : String(startTime || "");
  const endValue = Array.isArray(endTime) ? endTime[0] || "" : String(endTime || "");
  filters.value.timeRange = startValue && endValue ? [startValue, endValue] : [];
}

async function loadWorkflows() {
  try {
    workflows.value = await studioApi.workflows.options();
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, t("web.workflows.loadFailed")));
  }
}

async function loadRuntimeClusters() {
  try {
    runtimeClusters.value = await studioApi.runtimeClusters.options();
  } catch {
    runtimeClusters.value = [];
  }
}

async function loadWorkflowRuns() {
  try {
    const response = await studioApi.workflowRuns.list({
      workflowDefinitionId: filters.value.workflowDefinitionId || undefined,
      status: filters.value.status || undefined,
      requestedClusterId: filters.value.requestedClusterId || undefined,
      actualClusterId: filters.value.actualClusterId || undefined,
      startTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[0] : undefined,
      endTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[1] : undefined,
      pageNo: pagination.page,
      pageSize: pagination.pageSize,
    });
    workflowRuns.value = response.items;
    workflowRunTotal.value = Number(response.total ?? 0);
    if (ensureValidPage(workflowRunTotal.value)) {
      return void loadWorkflowRuns();
    }
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, t("web.runs.loadFailed")));
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
  if (filters.value.requestedClusterId) {
    query.requestedClusterId = filters.value.requestedClusterId;
  }
  if (filters.value.actualClusterId) {
    query.actualClusterId = filters.value.actualClusterId;
  }
  if (filters.value.timeRange.length === 2) {
    query.startTime = filters.value.timeRange[0];
    query.endTime = filters.value.timeRange[1];
  }
  resetPage();
  router.push({ path: "/runs", query });
}

function resetFilters() {
  filters.value.workflowDefinitionId = "";
  filters.value.status = "";
  filters.value.requestedClusterId = "";
  filters.value.actualClusterId = "";
  filters.value.timeRange = [];
  resetPage();
  router.push({ path: "/runs" });
}

function handleCurrentPageChange(page: number) {
  setPage(page);
  void loadWorkflowRuns();
}

function handlePageSizeChange(pageSize: number) {
  setPageSize(pageSize);
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

function resolveProjectLabel(projectId?: string | number | null) {
  return resolveProjectName(authStore.projects, projectId);
}

function isSharedRun(item: WorkflowRunSummary) {
  return isSharedFromAnotherProject(authStore.currentProjectId, item.projectId);
}

function clusterLabel(clusterId?: EntityId, clusterCode?: string) {
  return formatRuntimeClusterLabel(runtimeClusters.value, clusterId, clusterCode);
}

function workflowTargetClusterLabel(item: WorkflowRunSummary) {
  if (item.requestedClusterId != null) return clusterLabel(item.requestedClusterId);
  const workflow = workflows.value.find((candidate) => String(candidate.id) === String(item.workflowDefinitionId));
  const configured = clusterLabel(workflow?.runtimeClusterId);
  return workflow?.runtimeClusterId == null
    ? configured
    : `${configured} · ${t("web.runtimeClusterSelection.configuredCluster")}`;
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
  await Promise.all([loadWorkflows(), loadRuntimeClusters(), loadWorkflowRuns()]);
});

watch(
  () => route.fullPath,
  () => {
    syncFiltersFromRoute();
    resetPage();
    void loadWorkflowRuns();
  },
);

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], async () => {
  if (authStore.isAuthenticated) {
    resetPage();
    await Promise.all([loadWorkflows(), loadRuntimeClusters(), loadWorkflowRuns()]);
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

.workflow-run-table {
  width: 100%;
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
