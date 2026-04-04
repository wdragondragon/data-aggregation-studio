<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ runDetail?.workflowName || t("web.runs.detailTitle") }}</h3>
        <p>{{ t("web.runs.detailDescription") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="goBack">{{ t("common.backToList") }}</el-button>
        <el-button plain @click="loadDetail">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.runs.detailSummaryTitle')" :description="t('web.runs.detailSummaryDescription')">
      <div class="summary-panel">
        <div class="summary-grid">
          <div><strong>{{ t("web.runs.workflow") }}:</strong> {{ runDetail?.workflowName || "--" }}</div>
          <div class="summary-status-row">
            <strong>{{ t("web.runs.status") }}:</strong>
            <StatusPill :label="runDetail?.status || t('common.unknown')" :tone="toneFromStatus(runDetail?.status)" />
          </div>
          <div><strong>{{ t("web.runs.startedAt") }}:</strong> {{ runDetail?.startedAt || t("common.none") }}</div>
          <div><strong>{{ t("web.runs.endedAt") }}:</strong> {{ runDetail?.endedAt || t("common.none") }}</div>
          <div><strong>{{ t("web.runs.duration") }}:</strong> {{ formatDurationMs(runDetail?.durationMs) }}</div>
          <div class="summary-message"><strong>{{ t("web.runs.summaryMessage") }}:</strong> {{ runDetail?.summaryMessage || t("common.none") }}</div>
        </div>
        <div class="status-strip">
          <div class="status-metric">
            <span>{{ t("web.runs.totalNodes") }}</span>
            <strong>{{ runDetail?.totalNodes || 0 }}</strong>
          </div>
          <div class="status-metric success">
            <span>{{ t("web.runs.successNodes") }}</span>
            <strong>{{ runDetail?.successNodes || 0 }}</strong>
          </div>
          <div class="status-metric danger">
            <span>{{ t("web.runs.failedNodes") }}</span>
            <strong>{{ runDetail?.failedNodes || 0 }}</strong>
          </div>
          <div class="status-metric running">
            <span>{{ t("web.runs.runningNodes") }}</span>
            <strong>{{ runDetail?.runningNodes || 0 }}</strong>
          </div>
          <div class="status-metric muted">
            <span>{{ t("web.runs.notRunNodes") }}</span>
            <strong>{{ runDetail?.notRunNodes || 0 }}</strong>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard :title="t('web.runs.detailDagTitle')" :description="t('web.runs.detailDagDescription')">
      <WorkflowCanvas
        :nodes="runDetail?.workflow?.nodes || []"
        :edges="runDetail?.workflow?.edges || []"
        :node-statuses="nodeStatuses"
        :readonly="true"
        :hide-palette="true"
      />
    </SectionCard>

    <SectionCard :title="t('web.runs.detailNodesTitle')" :description="t('web.runs.detailNodesDescription')">
      <el-table :data="runDetail?.nodeRuns || []" border>
        <el-table-column prop="nodeName" :label="t('web.runs.node')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="nodeType" :label="t('web.runs.nodeType')" min-width="140" show-overflow-tooltip />
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
        <el-table-column prop="message" :label="t('web.runs.message')" min-width="320" show-overflow-tooltip />
        <el-table-column :label="t('web.runs.actions')" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!row.runRecordId" @click="activeRunRecordId = row.runRecordId">
              {{ t("web.runs.viewLog") }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>

    <RunLogDrawer v-model="logVisible" :run-record-id="activeRunRecordId" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { WorkflowRunDetail } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { WorkflowCanvas } from "@studio/workflow-designer";
import RunLogDrawer from "../components/RunLogDrawer.vue";
import { studioApi } from "@/api/studio";
import { toneFromStatus } from "@/utils/studio";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const runDetail = ref<WorkflowRunDetail | null>(null);
const activeRunRecordId = ref<string | number | undefined>(undefined);
const logVisible = ref(false);

const nodeStatuses = computed<Record<string, string>>(() => {
  const result: Record<string, string> = {};
  for (const item of runDetail.value?.nodeRuns || []) {
    if (item.nodeCode) {
      result[item.nodeCode] = item.status || "NOT_RUN";
    }
  }
  return result;
});

async function loadDetail() {
  try {
    runDetail.value = await studioApi.workflowRuns.get(String(route.params.workflowRunId));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.runs.loadFailed"));
  }
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

function goBack() {
  const workflowDefinitionId = route.query.workflowDefinitionId;
  if (workflowDefinitionId) {
    router.push({
      path: "/runs",
      query: {
        workflowDefinitionId: String(workflowDefinitionId),
      },
    });
    return;
  }
  router.push("/runs");
}

watch(activeRunRecordId, (value) => {
  logVisible.value = value != null;
});

watch(logVisible, (value) => {
  if (!value) {
    activeRunRecordId.value = undefined;
  }
});

onMounted(loadDetail);
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.summary-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
}

.summary-status-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.summary-message {
  grid-column: 1 / -1;
}

.status-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
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

.status-metric.muted {
  background: rgba(148, 163, 184, 0.12);
}

@media (max-width: 960px) {
  .summary-grid,
  .status-strip {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
