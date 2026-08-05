<template>
  <div class="studio-page dashboard-page">
    <div class="metrics-grid">
      <MetricCard :label="t('web.dashboard.datasources')" :value="datasourceCount" tone="accent" :hint="t('web.dashboard.datasourcesHint')" :description="t('web.dashboard.datasourcesDescription')" />
      <MetricCard :label="t('web.dashboard.publishedWorkflows')" :value="publishedWorkflowCount" tone="success" :hint="t('web.dashboard.publishedWorkflowsHint')" :description="t('web.dashboard.publishedWorkflowsDescription')" />
      <MetricCard :label="t('web.dashboard.onlineTasks')" :value="onlineCollectionTaskCount" tone="warning" :hint="t('web.dashboard.onlineTasksHint')" :description="t('web.dashboard.onlineTasksDescription')" />
      <MetricCard :label="t('web.dashboard.heroScriptsTitle')" :value="scriptTotalCount" tone="primary" :hint="t('routes.web.dataDevelopment.title')" :description="t('web.dashboard.heroScriptsHint')" />
    </div>

    <SectionCard
      class="dashboard-readiness-card"
      :title="t('web.dashboard.executionTitle')"
      :description="t('web.dashboard.executionDescription')"
      :show-description="true"
    >
      <div class="dashboard-readiness">
        <div class="soft-panel">
          <div class="dashboard-readiness__header">
            <strong>{{ t("web.dashboard.readyTypes") }}</strong>
            <span>{{ executableDatasourceTypes.length }}</span>
          </div>
          <div class="tag-row">
            <StatusPill
              v-for="type in executableDatasourceTypes.slice(0, 10)"
              :key="type"
              :label="type"
              tone="primary"
            />
            <span v-if="executableDatasourceTypes.length === 0">{{ t("web.dashboard.noExecutableSourceMapping") }}</span>
          </div>
        </div>

        <div class="dashboard-readiness__summary">
          <div class="dashboard-readiness-stat">
            <span>{{ t("web.dashboard.scheduledWorkflowsLabel") }}</span>
            <strong>{{ scheduledWorkflowCount }}</strong>
          </div>
          <div class="dashboard-readiness-stat">
            <span>{{ t("web.dashboard.onlineTasksLabel") }}</span>
            <strong>{{ onlineCollectionTaskCount }}</strong>
          </div>
          <div class="dashboard-readiness-stat">
            <span>{{ t("web.dashboard.scriptMixLabel") }}</span>
            <strong>{{ scriptTotalCount }}</strong>
          </div>
        </div>

        <div class="dashboard-script-mix">
          <div class="dashboard-script-mix__legend">
            <StatusPill :label="`${t('web.dataDevelopment.scriptTypeSql')} · ${scriptTypeCounts.SQL}`" tone="primary" />
            <StatusPill :label="`${t('web.dataDevelopment.scriptTypeFlinkQuestionSql')} · ${scriptTypeCounts.FLINK_QUESTION_SQL}`" tone="neutral" />
            <StatusPill :label="`${t('web.dataDevelopment.scriptTypeJava')} · ${scriptTypeCounts.JAVA}`" tone="success" />
            <StatusPill :label="`${t('web.dataDevelopment.scriptTypePython')} · ${scriptTypeCounts.PYTHON}`" tone="warning" />
          </div>

          <div v-if="recentScripts.length === 0" class="dashboard-empty">{{ t("web.dashboard.emptyScripts") }}</div>
          <div v-else class="dashboard-script-list">
            <div v-for="script in recentScripts" :key="String(script.id ?? script.fileName)" class="dashboard-script-item">
              <div>
                <strong>{{ script.fileName }}</strong>
                <p>{{ script.description || t("common.none") }}</p>
              </div>
              <StatusPill :label="formatScriptType(t, script.scriptType)" :tone="scriptTone(script.scriptType)" />
            </div>
          </div>
        </div>
      </div>
    </SectionCard>

    <div class="dashboard-detail-grid">
      <SectionCard
        :title="t('web.dashboard.operationalTitle')"
        :description="t('web.dashboard.operationalDescription')"
        :show-description="true"
      >
        <template #actions>
          <el-button type="primary" plain @click="loadDashboard">{{ t("common.refresh") }}</el-button>
        </template>

        <div class="dashboard-pulse">
          <div class="dashboard-pulse__stats">
            <div class="dashboard-pulse-stat">
              <span>{{ t("web.dashboard.queuedNow") }}</span>
              <strong>{{ queuedTaskCount }}</strong>
            </div>
            <div class="dashboard-pulse-stat">
              <span>{{ t("web.dashboard.runningNow") }}</span>
              <strong>{{ runningWorkflowRunCount }}</strong>
            </div>
            <div class="dashboard-pulse-stat">
              <span>{{ t("web.dashboard.failedNow") }}</span>
              <strong>{{ failedWorkflowRunCount }}</strong>
            </div>
          </div>

          <div class="dashboard-list">
            <div v-if="recentWorkflowRuns.length === 0" class="dashboard-empty">{{ t("web.dashboard.emptyRuns") }}</div>
            <button
              v-for="run in recentWorkflowRuns"
              :key="String(run.workflowRunId ?? `${run.workflowName}-${run.startedAt}`)"
              type="button"
              class="dashboard-list-item"
              @click="openWorkflowRun(run.workflowRunId)"
            >
              <div class="dashboard-list-item__main">
                <div class="dashboard-list-item__title-row">
                  <strong>{{ run.workflowName || t("common.none") }}</strong>
                  <StatusPill :label="formatStatusLabel(t, run.status)" :tone="toneFromStatus(run.status)" />
                </div>
                <div class="dashboard-list-item__meta">
                  <span>{{ t("web.dashboard.runStarted") }}: {{ run.startedAt || t("common.none") }}</span>
                  <span>{{ t("web.dashboard.runDuration") }}: {{ formatDuration(run.durationMs) }}</span>
                </div>
                <MessagePreviewText class="dashboard-list-item__message" :text="run.summaryMessage" :empty-text="t('common.none')" />
              </div>
              <span class="dashboard-list-item__action">{{ t("web.dashboard.openRun") }}</span>
            </button>
          </div>
        </div>
      </SectionCard>

      <SectionCard
        :title="t('web.dashboard.recentDefinitionsTitle')"
        :description="t('web.dashboard.recentDefinitionsDescription')"
        :show-description="true"
      >
        <div class="dashboard-list">
          <div v-if="recentWorkflowDefinitions.length === 0" class="dashboard-empty">{{ t("web.dashboard.emptyWorkflows") }}</div>
          <button
            v-for="workflow in recentWorkflowDefinitions"
            :key="String(workflow.id ?? workflow.code)"
            type="button"
            class="dashboard-list-item"
            @click="openWorkflowDefinition(workflow.id)"
          >
            <div class="dashboard-list-item__main">
              <div class="dashboard-list-item__title-row">
                <strong>{{ workflow.name }}</strong>
                <StatusPill
                  :label="workflow.published ? t('common.published') : t('common.draft')"
                  :tone="workflow.published ? 'success' : 'warning'"
                />
              </div>
              <div class="dashboard-list-item__meta">
                <span class="studio-mono">{{ workflow.code }}</span>
                <span>{{ workflow.schedule?.enabled ? workflow.schedule?.cronExpression : t("common.manualTrigger") }}</span>
              </div>
            </div>
            <span class="dashboard-list-item__action">{{ t("web.dashboard.openWorkflow") }}</span>
          </button>
        </div>
      </SectionCard>

    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import type {
  DataDevelopmentScriptListView,
  WorkflowListView,
  WorkflowRunSummary,
} from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import MessagePreviewText from "@/components/MessagePreviewText.vue";
import { useAuthStore } from "@/stores/auth";
import { formatScriptType, formatStatusLabel, toneFromStatus } from "@/utils/studio";

const { t } = useI18n();
const router = useRouter();
const authStore = useAuthStore();

const datasourceCount = ref(0);
const publishedWorkflowCount = ref(0);
const scheduledWorkflowCount = ref(0);
const onlineCollectionTaskCount = ref(0);
const queuedTaskCount = ref(0);
const executableDatasourceTypes = ref<string[]>([]);
const recentWorkflowDefinitions = ref<WorkflowListView[]>([]);
const recentWorkflowRuns = ref<WorkflowRunSummary[]>([]);
const recentScripts = ref<DataDevelopmentScriptListView[]>([]);
let dashboardLoadToken = 0;

const scriptTypeCounts = reactive({
  SQL: 0,
  FLINK_QUESTION_SQL: 0,
  JAVA: 0,
  PYTHON: 0,
});

const runningWorkflowRunCount = computed(() => recentWorkflowRuns.value.filter((item) => String(item.status ?? "").toUpperCase() === "RUNNING").length);
const failedWorkflowRunCount = computed(() => recentWorkflowRuns.value.filter((item) => ["FAILED", "ERROR"].includes(String(item.status ?? "").toUpperCase())).length);
const scriptTotalCount = computed(() =>
  scriptTypeCounts.SQL + scriptTypeCounts.FLINK_QUESTION_SQL + scriptTypeCounts.JAVA + scriptTypeCounts.PYTHON);

async function loadDashboard() {
  const loadToken = ++dashboardLoadToken;
  try {
    const overview = await studioApi.dashboard.overview();
    if (loadToken !== dashboardLoadToken) {
      return;
    }
    datasourceCount.value = toCount(overview.datasourceCount);
    publishedWorkflowCount.value = toCount(overview.publishedWorkflowCount);
    scheduledWorkflowCount.value = toCount(overview.scheduledWorkflowCount);
    onlineCollectionTaskCount.value = toCount(overview.onlineCollectionTaskCount);
    queuedTaskCount.value = toCount(overview.queuedTaskCount);
    scriptTypeCounts.SQL = toCount(overview.scriptTypeCounts?.SQL);
    scriptTypeCounts.FLINK_QUESTION_SQL = toCount(overview.scriptTypeCounts?.FLINK_QUESTION_SQL);
    scriptTypeCounts.JAVA = toCount(overview.scriptTypeCounts?.JAVA);
    scriptTypeCounts.PYTHON = toCount(overview.scriptTypeCounts?.PYTHON);
    executableDatasourceTypes.value = overview.executableDatasourceTypes ?? [];
    recentWorkflowDefinitions.value = overview.recentWorkflowDefinitions ?? [];
    recentWorkflowRuns.value = overview.recentWorkflowRuns ?? [];
    recentScripts.value = overview.recentScripts ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.dashboard.loadFailed"));
  }
}

function toCount(value?: number | string | null) {
  const numberValue = Number(value ?? 0);
  return Number.isFinite(numberValue) ? numberValue : 0;
}

function formatDuration(durationMs?: number) {
  if (!durationMs || durationMs <= 0) {
    return t("common.none");
  }
  if (durationMs < 1000) {
    return `${durationMs} ms`;
  }
  const totalSeconds = Math.round(durationMs / 1000);
  if (totalSeconds < 60) {
    return `${totalSeconds}s`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return seconds > 0 ? `${minutes}m ${seconds}s` : `${minutes}m`;
}

function openWorkflowRun(workflowRunId?: string | number) {
  if (!workflowRunId) {
    return;
  }
  router.push(`/runs/${workflowRunId}`);
}

function openWorkflowDefinition(workflowId?: string | number) {
  if (!workflowId) {
    return;
  }
  router.push(`/workflows/${workflowId}`);
}

function scriptTone(scriptType?: string) {
  if (scriptType === "JAVA") {
    return "success";
  }
  if (scriptType === "PYTHON") {
    return "warning";
  }
  if (scriptType === "FLINK_QUESTION_SQL") {
    return "neutral";
  }
  return "primary";
}

onMounted(loadDashboard);

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], () => {
  if (authStore.isAuthenticated) {
    loadDashboard();
  }
});
</script>

<style scoped>
.dashboard-page {
  gap: 12px;
}

.metrics-grid {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.dashboard-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  align-items: start;
  min-width: 0;
}

.dashboard-page :deep(.metric-card) {
  padding: 16px 18px;
  border-radius: 18px;
}

.dashboard-page :deep(.metric-card__value) {
  margin-top: 12px;
  font-size: 34px;
}

.dashboard-page :deep(.metric-card__description) {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.45;
}

.dashboard-page :deep(.section-card__header) {
  padding: 14px 16px 0;
}

.dashboard-page :deep(.section-card__body) {
  padding: 12px 16px 16px;
}

.dashboard-pulse,
.dashboard-readiness {
  display: grid;
  gap: 14px;
}

.dashboard-pulse__stats,
.dashboard-readiness__summary {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.dashboard-pulse-stat,
.dashboard-readiness-stat {
  padding: 12px 14px;
  border: 1px solid var(--studio-border);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.74);
}

.dashboard-pulse-stat span,
.dashboard-readiness-stat span {
  display: block;
  font-size: 12px;
  color: var(--studio-text-soft);
}

.dashboard-pulse-stat strong,
.dashboard-readiness-stat strong {
  display: block;
  margin-top: 6px;
  font-size: 24px;
  line-height: 1;
}

.dashboard-list,
.dashboard-script-list {
  display: grid;
  gap: 8px;
}

.dashboard-list-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 11px 13px;
  border: 1px solid var(--studio-border);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.68);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}

.dashboard-list-item:hover {
  transform: translateY(-1px);
  border-color: rgba(37, 99, 235, 0.26);
  box-shadow: 0 14px 28px rgba(37, 99, 235, 0.08);
}

.dashboard-list-item__main {
  min-width: 0;
  flex: 1;
}

.dashboard-list-item strong,
.dashboard-script-item strong {
  display: block;
  font-size: 14px;
}

.dashboard-list-item__message,
.dashboard-script-item p {
  margin: 6px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.55;
}

.dashboard-list-item__action {
  white-space: nowrap;
  font-size: 12px;
  color: var(--studio-primary);
}

.dashboard-list-item__title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.dashboard-list-item__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 14px;
  margin-top: 8px;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.dashboard-readiness__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.dashboard-script-mix {
  display: grid;
  gap: 10px;
}

.dashboard-script-mix__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.dashboard-script-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.62);
  border: 1px solid rgba(64, 113, 187, 0.12);
}

.dashboard-empty {
  padding: 18px;
  border: 1px dashed rgba(64, 113, 187, 0.18);
  border-radius: 14px;
  color: var(--studio-text-soft);
  text-align: center;
  font-size: 13px;
}

@media (max-width: 1260px) {
  .metrics-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .metrics-grid,
  .dashboard-detail-grid,
  .dashboard-pulse__stats,
  .dashboard-readiness__summary {
    grid-template-columns: minmax(0, 1fr);
  }

  .dashboard-list-item,
  .dashboard-script-item {
    flex-direction: column;
  }
}
</style>
