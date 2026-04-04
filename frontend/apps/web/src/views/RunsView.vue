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
            <strong>{{ workflowRuns.length }}</strong>
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
      <el-table :data="workflowRuns" border size="small">
        <el-table-column :label="t('web.runs.workflow')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button link type="primary" class="run-link" @click="openRunDetail(row)">
              {{ row.workflowName || "--" }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.runs.status')" width="120">
          <template #default="{ row }">
            <StatusPill :label="row.status ?? t('common.unknown')" :tone="toneFromStatus(row.status)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.runs.startedAt')" min-width="180">
          <template #default="{ row }">
            <span>{{ row.startedAt || t("common.none") }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.runs.endedAt')" min-width="180">
          <template #default="{ row }">
            <span>{{ row.endedAt || t("common.none") }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.runs.duration')" width="140">
          <template #default="{ row }">
            <span>{{ formatDurationMs(row.durationMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.runs.detailNodeStats')" min-width="220">
          <template #default="{ row }">
            <span>{{ formatNodeStats(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="summaryMessage" :label="t('web.runs.summaryMessage')" min-width="260" show-overflow-tooltip />
        <el-table-column :label="t('web.runs.actions')" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRunDetail(row)">{{ t("web.runs.viewRunDetail") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { WorkflowDefinitionView, WorkflowRunSummary } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { toneFromStatus } from "@/utils/studio";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const workflows = ref<WorkflowDefinitionView[]>([]);
const workflowRuns = ref<WorkflowRunSummary[]>([]);
const filters = ref<{
  workflowDefinitionId: string;
  timeRange: [string, string] | [];
}>({
  workflowDefinitionId: "",
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
  const startTime = route.query.startTime;
  const endTime = route.query.endTime;
  filters.value.workflowDefinitionId = Array.isArray(workflowDefinitionId) ? workflowDefinitionId[0] || "" : String(workflowDefinitionId || "");
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
    workflowRuns.value = await studioApi.workflowRuns.list({
      workflowDefinitionId: filters.value.workflowDefinitionId || undefined,
      startTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[0] : undefined,
      endTime: filters.value.timeRange.length === 2 ? filters.value.timeRange[1] : undefined,
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.runs.loadFailed"));
  }
}

function applyFilters() {
  const query: Record<string, string> = {};
  if (filters.value.workflowDefinitionId) {
    query.workflowDefinitionId = filters.value.workflowDefinitionId;
  }
  if (filters.value.timeRange.length === 2) {
    query.startTime = filters.value.timeRange[0];
    query.endTime = filters.value.timeRange[1];
  }
  router.push({ path: "/runs", query });
}

function resetFilters() {
  filters.value.workflowDefinitionId = "";
  filters.value.timeRange = [];
  router.push({ path: "/runs" });
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
  ].join(" / ");
}

onMounted(async () => {
  syncFiltersFromRoute();
  await Promise.all([loadWorkflows(), loadWorkflowRuns()]);
});

watch(
  () => route.fullPath,
  () => {
    syncFiltersFromRoute();
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
  gap: 12px;
}

.runs-query-grid {
  display: grid;
  grid-template-columns: 220px minmax(260px, 1fr) auto;
  gap: 10px;
  align-items: center;
}

.runs-query-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.status-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.status-metric {
  padding: 10px 12px;
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
  font-size: 18px;
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

@media (max-width: 1100px) {
  .runs-query-grid,
  .status-strip {
    grid-template-columns: minmax(0, 1fr);
  }

  .runs-query-actions {
    justify-content: flex-start;
  }
}
</style>
