<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ workflow?.name || t("web.workflows.detailTitle") }}</h3>
        <p>{{ t("web.workflows.detailDescription") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/workflows')">{{ t("common.backToList") }}</el-button>
        <el-button plain @click="loadWorkflow">{{ t("common.refresh") }}</el-button>
        <el-button type="primary" plain :disabled="!workflow?.id" @click="openLogs">{{ t("web.workflows.logsEntry") }}</el-button>
        <el-button type="primary" :disabled="!workflow?.id" @click="openEditor">{{ t("common.edit") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.workflows.detailBasicsTitle')" :description="t('web.workflows.detailBasicsDescription')">
      <div class="workflow-summary">
        <div class="detail-grid">
          <div class="detail-item">
            <span class="detail-label">{{ t("web.workflows.code") }}</span>
            <strong>{{ workflow?.code || t("common.none") }}</strong>
          </div>
          <div class="detail-item">
            <span class="detail-label">{{ t("web.workflows.name") }}</span>
            <strong>{{ workflow?.name || t("common.none") }}</strong>
          </div>
          <div class="detail-item">
            <span class="detail-label">{{ t("web.workflows.cronExpression") }}</span>
            <strong>{{ workflow?.schedule?.cronExpression || t("common.none") }}</strong>
          </div>
          <div class="detail-item">
            <span class="detail-label">{{ t("web.workflows.timezone") }}</span>
            <strong>{{ workflow?.schedule?.timezone || t("common.none") }}</strong>
          </div>
        </div>
        <div class="workflow-stats">
          <div class="workflow-stat">
            <span class="workflow-stat-label">{{ t("web.workflows.detailNodes") }}</span>
            <strong>{{ workflow?.nodes.length || 0 }}</strong>
          </div>
          <div class="workflow-stat">
            <span class="workflow-stat-label">{{ t("web.workflows.detailEdges") }}</span>
            <strong>{{ workflow?.edges.length || 0 }}</strong>
          </div>
          <div class="workflow-stat">
            <span class="workflow-stat-label">{{ t("web.workflows.detailPublished") }}</span>
            <strong>{{ workflow?.published ? t("common.yes") : t("common.no") }}</strong>
          </div>
          <div class="workflow-stat">
            <span class="workflow-stat-label">{{ t("web.workflows.detailSchedule") }}</span>
            <strong>{{ workflow?.schedule?.enabled ? t("common.on") : t("common.off") }}</strong>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard :title="t('web.workflows.canvasTitle')" :description="t('web.workflows.detailCanvasDescription')">
      <WorkflowCanvas
        :nodes="workflow?.nodes || []"
        :edges="workflow?.edges || []"
        :readonly="true"
        :hide-palette="true"
      />
    </SectionCard>

    <SectionCard :title="t('web.workflows.detailLogsTitle')" :description="t('web.workflows.detailLogsDescription')">
      <el-table :data="workflowRuns" border>
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
        <el-table-column :label="t('web.runs.actions')" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRunDetail(row)">
              {{ t("web.runs.viewRunDetail") }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { WorkflowDefinitionView, WorkflowRunSummary } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { WorkflowCanvas } from "@studio/workflow-designer";
import { studioApi } from "@/api/studio";
import { toneFromStatus } from "@/utils/studio";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const workflow = ref<WorkflowDefinitionView | null>(null);
const workflowRuns = ref<WorkflowRunSummary[]>([]);

async function loadWorkflow() {
  try {
    workflow.value = await studioApi.workflows.get(String(route.params.workflowId));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  }
}

async function loadWorkflowRuns() {
  try {
    workflowRuns.value = await studioApi.workflowRuns.list({
      workflowDefinitionId: String(route.params.workflowId),
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.runs.loadFailed"));
  }
}

function openEditor() {
  if (!workflow.value?.id) {
    return;
  }
  router.push(`/workflows/${workflow.value.id}/edit`);
}

function openLogs() {
  if (!workflow.value?.id) {
    return;
  }
  router.push({
    path: "/runs",
    query: {
      workflowDefinitionId: String(workflow.value.id),
    },
  });
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
  await Promise.all([loadWorkflow(), loadWorkflowRuns()]);
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

.workflow-summary {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(280px, 1fr);
  gap: 14px;
  align-items: start;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 14px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(16, 78, 139, 0.05);
  border: 1px solid rgba(16, 78, 139, 0.1);
}

.detail-label,
.workflow-stat-label {
  font-size: 12px;
  color: var(--studio-text-soft);
}

.workflow-stats {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.workflow-stat {
  padding: 12px 14px;
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(16, 78, 139, 0.1), rgba(255, 255, 255, 0.92));
  border: 1px solid rgba(16, 78, 139, 0.12);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.workflow-stat strong,
.detail-item strong {
  color: var(--studio-text);
}

@media (max-width: 960px) {
  .workflow-summary,
  .detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .workflow-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
