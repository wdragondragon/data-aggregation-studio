<template>
  <div class="studio-page dashboard-page">
    <section class="dashboard-hero">
      <div class="dashboard-hero__content">
        <span class="dashboard-hero__eyebrow">{{ t("web.dashboard.heroEyebrow") }}</span>
        <h2 class="dashboard-hero__title">
          {{ authStore.username ? t("web.dashboard.heroGreetingUser", { username: authStore.username }) : t("web.dashboard.heroGreeting") }}
        </h2>
        <p class="dashboard-hero__description">{{ t("web.dashboard.heroDescription") }}</p>

        <div class="dashboard-hero__actions">
          <el-button type="primary" @click="router.push('/datasources')">{{ t("web.dashboard.heroActionDatasource") }}</el-button>
          <el-button plain @click="router.push('/collection-tasks/new')">{{ t("web.dashboard.heroActionCollectionTask") }}</el-button>
          <el-button plain @click="router.push('/workflows/new')">{{ t("web.dashboard.heroActionWorkflow") }}</el-button>
          <el-button plain @click="router.push('/data-development')">{{ t("web.dashboard.heroActionScript") }}</el-button>
        </div>
      </div>

      <div class="dashboard-hero__stats">
        <article class="dashboard-highlight">
          <span class="dashboard-highlight__label">{{ t("web.dashboard.heroPublishedTitle") }}</span>
          <strong class="dashboard-highlight__value">{{ publishedWorkflowCount }}</strong>
          <p class="dashboard-highlight__hint">{{ t("web.dashboard.heroPublishedHint") }}</p>
        </article>
        <article class="dashboard-highlight">
          <span class="dashboard-highlight__label">{{ t("web.dashboard.heroOnlineTitle") }}</span>
          <strong class="dashboard-highlight__value">{{ onlineCollectionTaskCount }}</strong>
          <p class="dashboard-highlight__hint">{{ t("web.dashboard.heroOnlineHint") }}</p>
        </article>
        <article class="dashboard-highlight">
          <span class="dashboard-highlight__label">{{ t("web.dashboard.heroScriptsTitle") }}</span>
          <strong class="dashboard-highlight__value">{{ scripts.length }}</strong>
          <p class="dashboard-highlight__hint">{{ t("web.dashboard.heroScriptsHint") }}</p>
        </article>
      </div>
    </section>

    <div class="metrics-grid">
      <MetricCard :label="t('web.dashboard.datasources')" :value="datasources.length" tone="accent" :hint="t('web.dashboard.datasourcesHint')" :description="t('web.dashboard.datasourcesDescription')" />
      <MetricCard :label="t('web.dashboard.publishedWorkflows')" :value="publishedWorkflowCount" tone="success" :hint="t('web.dashboard.publishedWorkflowsHint')" :description="t('web.dashboard.publishedWorkflowsDescription')" />
      <MetricCard :label="t('web.dashboard.onlineTasks')" :value="onlineCollectionTaskCount" tone="warning" :hint="t('web.dashboard.onlineTasksHint')" :description="t('web.dashboard.onlineTasksDescription')" />
    </div>

    <div class="studio-grid columns-2">
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
              <strong>{{ runData.queuedTasks.length }}</strong>
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
                <p class="dashboard-list-item__message">{{ run.summaryMessage || t("common.none") }}</p>
              </div>
              <span class="dashboard-list-item__action">{{ t("web.dashboard.openRun") }}</span>
            </button>
          </div>
        </div>
      </SectionCard>

      <SectionCard
        :title="t('web.dashboard.workspaceTitle')"
        :description="t('web.dashboard.workspaceDescription')"
        :show-description="true"
      >
        <div class="dashboard-links">
          <button
            v-for="link in workspaceLinks"
            :key="link.path"
            type="button"
            class="dashboard-link-card"
            @click="router.push(link.path)"
          >
            <div class="dashboard-link-card__body">
              <strong>{{ link.label }}</strong>
              <p>{{ link.caption }}</p>
            </div>
            <span class="dashboard-link-card__metric">{{ link.metric }}</span>
          </button>
        </div>
      </SectionCard>
    </div>

    <div class="studio-grid columns-2">
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

      <SectionCard
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
              <strong>{{ scripts.length }}</strong>
            </div>
          </div>

          <div class="dashboard-script-mix">
            <div class="dashboard-script-mix__legend">
              <StatusPill :label="`${t('web.dataDevelopment.scriptTypeSql')} · ${scriptTypeCounts.SQL}`" tone="primary" />
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
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import type {
  CapabilityMatrix,
  CollectionTaskDefinitionView,
  DataDevelopmentScript,
  DataSourceDefinition,
  RunListResponse,
  WorkflowDefinitionView,
  WorkflowRunSummary,
} from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { formatScriptType, formatStatusLabel, toneFromStatus } from "@/utils/studio";

const { t } = useI18n();
const router = useRouter();
const authStore = useAuthStore();

const datasources = ref<DataSourceDefinition[]>([]);
const workflows = ref<WorkflowDefinitionView[]>([]);
const collectionTasks = ref<CollectionTaskDefinitionView[]>([]);
const scripts = ref<DataDevelopmentScript[]>([]);
const workflowRuns = ref<WorkflowRunSummary[]>([]);

const capabilityMatrix = reactive<CapabilityMatrix>({
  executableSourceTypes: [],
});

const runData = reactive<RunListResponse>({
  queuedTasks: [],
  runRecords: [],
});

const executableDatasourceTypes = computed(() => capabilityMatrix.executableDatasourceTypes ?? capabilityMatrix.executableSourceTypes ?? []);
const publishedWorkflowCount = computed(() => workflows.value.filter((item) => Boolean(item.published)).length);
const scheduledWorkflowCount = computed(() => workflows.value.filter((item) => Boolean(item.schedule?.enabled)).length);
const onlineCollectionTaskCount = computed(() => collectionTasks.value.filter((item) => item.status === "ONLINE").length);
const runningWorkflowRunCount = computed(() => workflowRuns.value.filter((item) => String(item.status ?? "").toUpperCase() === "RUNNING").length);
const failedWorkflowRunCount = computed(() => workflowRuns.value.filter((item) => ["FAILED", "ERROR"].includes(String(item.status ?? "").toUpperCase())).length);
const scriptTypeCounts = computed(() => ({
  SQL: scripts.value.filter((item) => item.scriptType === "SQL").length,
  JAVA: scripts.value.filter((item) => item.scriptType === "JAVA").length,
  PYTHON: scripts.value.filter((item) => item.scriptType === "PYTHON").length,
}));

const recentWorkflowDefinitions = computed(() => sortByFreshness(workflows.value, ["updatedAt", "createdAt"]).slice(0, 5));
const recentWorkflowRuns = computed(() => sortByFreshness(workflowRuns.value, ["startedAt", "endedAt", "createdAt"]).slice(0, 5));
const recentScripts = computed(() => sortByFreshness(scripts.value, ["updatedAt", "createdAt"]).slice(0, 4));

const workspaceLinks = computed(() => [
  {
    path: "/datasources",
    label: t("routes.web.datasources.title"),
    caption: t("routes.web.datasources.menuCaption"),
    metric: t("web.dashboard.linkMetricCount", { count: datasources.value.length }),
  },
  {
    path: "/collection-tasks",
    label: t("routes.web.collectionTasks.title"),
    caption: t("routes.web.collectionTasks.menuCaption"),
    metric: t("web.dashboard.linkMetricCount", { count: onlineCollectionTaskCount.value }),
  },
  {
    path: "/workflows",
    label: t("routes.web.workflows.title"),
    caption: t("routes.web.workflows.menuCaption"),
    metric: t("web.dashboard.linkMetricCount", { count: publishedWorkflowCount.value }),
  },
  {
    path: "/data-development",
    label: t("routes.web.dataDevelopment.title"),
    caption: t("routes.web.dataDevelopment.menuCaption"),
    metric: t("web.dashboard.linkMetricCount", { count: scripts.value.length }),
  },
]);

async function loadDashboard() {
  try {
    const [datasourceData, workflowData, capabilityData, runsData, collectionTaskData, scriptsData, workflowRunData] = await Promise.all([
      studioApi.datasources.list(),
      studioApi.workflows.list(),
      studioApi.catalog.capabilities(),
      studioApi.runs.list(),
      studioApi.collectionTasks.list(),
      studioApi.dataDevelopment.listScripts(),
      studioApi.workflowRuns.list({ pageNo: 1, pageSize: 6 }),
    ]);
    datasources.value = datasourceData;
    workflows.value = workflowData;
    collectionTasks.value = collectionTaskData;
    scripts.value = scriptsData;
    workflowRuns.value = workflowRunData.items;
    capabilityMatrix.executableSourceTypes = capabilityData.executableSourceTypes;
    capabilityMatrix.executableTargetTypes = capabilityData.executableTargetTypes;
    capabilityMatrix.executableDatasourceTypes = capabilityData.executableDatasourceTypes;
    capabilityMatrix.sourceCapabilities = capabilityData.sourceCapabilities;
    runData.queuedTasks = runsData.queuedTasks;
    runData.runRecords = runsData.runRecords;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.dashboard.loadFailed"));
  }
}

function sortByFreshness<T extends Record<string, unknown>>(items: T[], fields: string[]) {
  return [...items].sort((left, right) => resolveFreshness(right, fields) - resolveFreshness(left, fields));
}

function resolveFreshness(record: Record<string, unknown>, fields: string[]) {
  for (const field of fields) {
    const value = record[field];
    if (typeof value === "string") {
      const timestamp = Date.parse(value);
      if (Number.isFinite(timestamp)) {
        return timestamp;
      }
    }
  }
  return 0;
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
  return "primary";
}

onMounted(loadDashboard);
</script>

<style scoped>
.dashboard-page {
  gap: 18px;
}

.dashboard-hero {
  position: relative;
  overflow: hidden;
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(300px, 0.9fr);
  gap: 18px;
  padding: 24px;
  border: 1px solid rgba(37, 99, 235, 0.12);
  border-radius: 24px;
  background:
    radial-gradient(circle at top right, rgba(56, 189, 248, 0.24), transparent 28%),
    linear-gradient(135deg, rgba(18, 57, 122, 0.98), rgba(37, 99, 235, 0.92));
  color: #f5f9ff;
  box-shadow: 0 24px 56px rgba(24, 67, 142, 0.22);
}

.dashboard-hero::after {
  content: "";
  position: absolute;
  inset: auto -48px -48px auto;
  width: 220px;
  height: 220px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.09);
}

.dashboard-hero__content,
.dashboard-hero__stats {
  position: relative;
  z-index: 1;
}

.dashboard-hero__content {
  display: grid;
  gap: 12px;
  align-content: start;
}

.dashboard-hero__eyebrow {
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: rgba(234, 244, 255, 0.74);
}

.dashboard-hero__title {
  margin: 0;
  font-size: clamp(28px, 4vw, 38px);
  line-height: 1.06;
}

.dashboard-hero__description {
  margin: 0;
  max-width: 720px;
  color: rgba(234, 244, 255, 0.82);
  font-size: 14px;
  line-height: 1.65;
}

.dashboard-hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 6px;
}

.dashboard-hero__actions :deep(.el-button) {
  --el-button-text-color: #f5f9ff;
  --el-button-border-color: rgba(255, 255, 255, 0.22);
  --el-button-hover-text-color: #f5f9ff;
  --el-button-hover-border-color: rgba(255, 255, 255, 0.34);
  --el-button-hover-bg-color: rgba(255, 255, 255, 0.12);
  --el-button-bg-color: rgba(255, 255, 255, 0.08);
}

.dashboard-hero__actions :deep(.el-button--primary) {
  --el-button-text-color: #12397a;
  --el-button-bg-color: #f5f9ff;
  --el-button-border-color: #f5f9ff;
  --el-button-hover-text-color: #12397a;
  --el-button-hover-bg-color: #eaf2ff;
  --el-button-hover-border-color: #eaf2ff;
}

.dashboard-hero__stats {
  display: grid;
  gap: 12px;
  align-content: center;
}

.dashboard-highlight {
  padding: 16px 18px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(12px);
}

.dashboard-highlight__label {
  display: block;
  font-size: 12px;
  color: rgba(234, 244, 255, 0.72);
}

.dashboard-highlight__value {
  display: block;
  margin-top: 10px;
  font-size: 30px;
  line-height: 1;
}

.dashboard-highlight__hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: rgba(234, 244, 255, 0.76);
}

.metrics-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
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

.dashboard-links,
.dashboard-list,
.dashboard-script-list {
  display: grid;
  gap: 10px;
}

.dashboard-link-card,
.dashboard-list-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 14px 16px;
  border: 1px solid var(--studio-border);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.68);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}

.dashboard-link-card:hover,
.dashboard-list-item:hover {
  transform: translateY(-1px);
  border-color: rgba(37, 99, 235, 0.26);
  box-shadow: 0 14px 28px rgba(37, 99, 235, 0.08);
}

.dashboard-link-card__body,
.dashboard-list-item__main {
  min-width: 0;
  flex: 1;
}

.dashboard-link-card strong,
.dashboard-list-item strong,
.dashboard-script-item strong {
  display: block;
  font-size: 14px;
}

.dashboard-link-card p,
.dashboard-list-item__message,
.dashboard-script-item p {
  margin: 6px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.55;
}

.dashboard-link-card__metric,
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
  .dashboard-hero {
    grid-template-columns: minmax(0, 1fr);
  }

  .metrics-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .dashboard-hero {
    padding: 20px;
  }

  .metrics-grid,
  .dashboard-pulse__stats,
  .dashboard-readiness__summary {
    grid-template-columns: minmax(0, 1fr);
  }

  .dashboard-link-card,
  .dashboard-list-item,
  .dashboard-script-item {
    flex-direction: column;
  }
}
</style>
