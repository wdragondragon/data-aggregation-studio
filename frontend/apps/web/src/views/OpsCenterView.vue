<template>
  <div class="studio-page ops-center-page" v-loading="isLoading">
    <section class="ops-header">
      <div>
        <h3>{{ t("web.opsCenter.heading") }}</h3>
        <p>{{ t("web.opsCenter.description") }}</p>
      </div>
      <div class="ops-header__actions">
        <span v-if="lastUpdatedAt" class="ops-updated">{{ t("web.opsCenter.lastUpdated", { time: lastUpdatedAt }) }}</span>
        <el-switch v-model="autoRefresh" :active-text="t('web.opsCenter.autoRefresh')" />
        <el-button type="primary" :loading="isLoading" @click="loadAll">{{ t("common.refresh") }}</el-button>
      </div>
    </section>

    <SectionCard :title="t('web.opsCenter.filterTitle')" :description="t('web.opsCenter.filterDescription')">
      <div class="ops-filter-grid">
        <el-radio-group class="ops-filter-control ops-filter-preset" v-model="timePreset" size="small" @change="handleTimePresetChange">
          <el-radio-button value="24h">{{ t("web.opsCenter.timePreset24h") }}</el-radio-button>
          <el-radio-button value="7d">{{ t("web.opsCenter.timePreset7d") }}</el-radio-button>
          <el-radio-button value="30d">{{ t("web.opsCenter.timePreset30d") }}</el-radio-button>
          <el-radio-button value="custom">{{ t("web.opsCenter.customTime") }}</el-radio-button>
        </el-radio-group>
        <el-date-picker
          class="ops-filter-control ops-filter-date"
          v-model="timeRange"
          type="datetimerange"
          value-format="YYYY-MM-DD HH:mm:ss"
          :start-placeholder="t('web.runs.timeRangeStart')"
          :end-placeholder="t('web.runs.timeRangeEnd')"
          @change="timePreset = 'custom'"
        />
        <el-select class="ops-filter-control" v-model="filters.executionType" clearable :placeholder="t('web.opsCenter.executionType')">
          <el-option :label="t('web.opsCenter.all')" value="" />
          <el-option v-for="item in options.executionTypes" :key="item" :label="formatExecutionType(item)" :value="item" />
        </el-select>
        <el-select class="ops-filter-control" v-model="filters.status" clearable :placeholder="t('common.status')">
          <el-option :label="t('web.opsCenter.all')" value="" />
          <el-option v-for="item in options.statuses" :key="item" :label="formatStatus(item)" :value="item" />
        </el-select>
        <el-select class="ops-filter-control" v-model="filters.workerGroupCode" clearable filterable :placeholder="t('web.opsCenter.workerGroup')">
          <el-option :label="t('web.opsCenter.all')" value="" />
          <el-option v-for="item in options.workerGroups" :key="item" :label="item" :value="item" />
        </el-select>
        <div class="ops-filter-actions">
          <el-button type="primary" @click="search">{{ t("common.search") }}</el-button>
          <el-button @click="resetFilters">{{ t("common.reset") }}</el-button>
        </div>
      </div>
    </SectionCard>

    <section class="ops-health">
      <div class="ops-health__main" :class="`is-${String(overview?.healthStatus || 'HEALTHY').toLowerCase()}`">
        <span>{{ t("web.opsCenter.healthTitle") }}</span>
        <strong>{{ healthLabel(overview?.healthStatus) }}</strong>
        <p>{{ overview?.healthMessage || t("web.opsCenter.empty") }}</p>
      </div>
      <div class="ops-health__reasons">
        <el-tag v-for="reason in overview?.healthReasons || []" :key="reason" type="warning">{{ reason }}</el-tag>
        <span v-if="!overview?.healthReasons?.length" class="ops-muted">{{ t("web.opsCenter.empty") }}</span>
      </div>
    </section>

    <div class="ops-metric-grid">
      <MetricCard
        v-for="metric in overview?.metrics || []"
        :key="metric.key"
        :label="metricLabel(metric)"
        :value="formatCount(metric.value)"
        :hint="metric.hint"
        :tone="metricTone(metric.status)"
      />
    </div>

    <div class="ops-grid two-columns">
      <SectionCard :title="t('web.opsCenter.queueTitle')" :description="t('web.opsCenter.queueDescription')">
        <template #actions>
          <el-button link type="primary" @click="router.push('/runs')">{{ t("web.opsCenter.openRuns") }}</el-button>
        </template>
        <el-table :data="queueItems" size="small" border empty-text="暂无数据">
          <el-table-column prop="executionType" :label="t('web.opsCenter.executionType')" min-width="140">
            <template #default="{ row }">{{ formatExecutionType(row.executionType) }}</template>
          </el-table-column>
          <el-table-column prop="targetName" :label="t('web.opsCenter.targetName')" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <el-button v-if="isQueueTargetClickable(row)" link type="primary" @click="openQueueTarget(row)">
                {{ queueTargetName(row) }}
              </el-button>
              <span v-else>{{ queueTargetName(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="nodeCode" :label="t('web.opsCenter.nodeCode')" min-width="130" show-overflow-tooltip />
          <el-table-column prop="status" :label="t('common.status')" width="110">
            <template #default="{ row }"><el-tag :type="statusTagType(row.status)">{{ formatStatus(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="workerGroupCode" :label="t('web.opsCenter.workerGroup')" min-width="150" show-overflow-tooltip />
          <el-table-column :label="t('web.opsCenter.queuedDuration')" width="120">
            <template #default="{ row }">{{ formatDuration(row.queuedDurationMs) }}</template>
          </el-table-column>
          <el-table-column :label="t('web.opsCenter.scheduleDelay')" width="120">
            <template #default="{ row }">{{ formatDuration(row.scheduleDelayMs) }}</template>
          </el-table-column>
        </el-table>
      </SectionCard>

      <SectionCard :title="t('web.opsCenter.workersTitle')" :description="t('web.opsCenter.workersDescription')">
        <template #actions>
          <el-button link type="primary" @click="router.push('/system?tab=workers')">{{ t("web.opsCenter.openWorkers") }}</el-button>
        </template>
        <el-alert
          v-if="isWorkerUnassigned"
          class="ops-section-alert"
          type="error"
          show-icon
          :closable="false"
          :title="t('web.opsCenter.workerUnassignedTitle')"
          :description="t('web.opsCenter.workerUnassignedDescription')"
        />
        <el-alert
          v-else-if="isWorkerOffline"
          class="ops-section-alert"
          type="warning"
          show-icon
          :closable="false"
          :title="t('web.opsCenter.workerOfflineTitle')"
          :description="t('web.opsCenter.workerOfflineDescription')"
        />
        <el-table :data="workerGroups" size="small" border empty-text="暂无数据">
          <el-table-column prop="workerGroupCode" :label="t('web.opsCenter.workerGroup')" min-width="150" show-overflow-tooltip />
          <el-table-column prop="boundToProject" :label="t('web.opsCenter.assignmentStatus')" width="130">
            <template #default="{ row }">
              <el-tag :type="workerAssignmentTagType(row)">{{ workerAssignmentLabel(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="displayStatus" :label="t('web.opsCenter.groupStatus')" width="120">
            <template #default="{ row }"><el-tag :type="workerTagType(row)">{{ workerStatusLabel(row) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="在线/近期" width="110">
            <template #default="{ row }">{{ row.onlineInstanceCount || 0 }} / {{ row.recentInstanceCount || 0 }}</template>
          </el-table-column>
          <el-table-column prop="latestPodName" label="Pod" min-width="160" show-overflow-tooltip />
          <el-table-column prop="latestHeartbeatAt" label="心跳" min-width="170" show-overflow-tooltip />
        </el-table>
      </SectionCard>
    </div>

    <SectionCard :title="t('web.opsCenter.runsTitle')" :description="t('web.opsCenter.runsDescription')">
      <el-table :data="runIncidents" size="small" border empty-text="暂无数据">
        <el-table-column prop="executionType" :label="t('web.opsCenter.executionType')" min-width="140">
          <template #default="{ row }">{{ formatExecutionType(row.executionType) }}</template>
        </el-table-column>
        <el-table-column prop="nodeCode" :label="t('web.runs.node')" min-width="160" show-overflow-tooltip />
        <el-table-column prop="status" :label="t('common.status')" width="110">
          <template #default="{ row }"><el-tag :type="statusTagType(row.status)">{{ formatStatus(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="workerGroupCode" :label="t('web.opsCenter.workerGroup')" min-width="140" show-overflow-tooltip />
        <el-table-column :label="t('web.opsCenter.duration')" width="120">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column prop="message" :label="t('web.runs.message')" min-width="220" show-overflow-tooltip />
        <el-table-column :label="t('common.actions')" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRun(row)">{{ t("web.opsCenter.openRunDetail") }}</el-button>
            <el-button link type="primary" @click="openRunLog(row.id)">{{ t("web.opsCenter.viewLog") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>

    <div class="ops-grid two-columns">
      <SectionCard :title="t('web.opsCenter.serviceTitle')" :description="t('web.opsCenter.serviceDescription')">
        <template #actions>
          <el-button link type="primary" @click="router.push('/data-service-metrics/access-logs')">{{ t("web.opsCenter.openServiceLogs") }}</el-button>
        </template>
        <el-table :data="serviceEvents" size="small" border empty-text="暂无数据">
          <el-table-column prop="serviceName" :label="t('web.opsCenter.service')" min-width="180" show-overflow-tooltip />
          <el-table-column prop="subscriptionName" :label="t('web.opsCenter.subscription')" min-width="140" show-overflow-tooltip />
          <el-table-column prop="httpStatus" label="HTTP" width="84" />
          <el-table-column :label="t('web.opsCenter.duration')" width="110">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column prop="occurredAt" :label="t('web.opsCenter.occurredAt')" min-width="170" show-overflow-tooltip />
          <el-table-column :label="t('common.actions')" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openServiceLogs(row.serviceId)">{{ t("web.opsCenter.viewLog") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>

      <SectionCard :title="t('web.opsCenter.ingestionTitle')" :description="t('web.opsCenter.ingestionDescription')">
        <template #actions>
          <el-button link type="primary" @click="router.push('/data-ingestion-metrics/access-logs')">{{ t("web.opsCenter.openIngestionLogs") }}</el-button>
        </template>
        <el-table :data="ingestionEvents" size="small" border empty-text="暂无数据">
          <el-table-column prop="serviceName" :label="t('web.opsCenter.service')" min-width="180" show-overflow-tooltip />
          <el-table-column prop="subscriptionName" :label="t('web.opsCenter.subscription')" min-width="140" show-overflow-tooltip />
          <el-table-column :label="t('web.opsCenter.receivedWritten')" width="130">
            <template #default="{ row }">{{ formatCount(row.receivedCount) }} / {{ formatCount(row.writtenCount) }}</template>
          </el-table-column>
          <el-table-column :label="t('web.opsCenter.duration')" width="110">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column prop="occurredAt" :label="t('web.opsCenter.occurredAt')" min-width="170" show-overflow-tooltip />
          <el-table-column :label="t('common.actions')" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openIngestionLogs(row.serviceId)">{{ t("web.opsCenter.viewLog") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>
    </div>

    <SectionCard :title="t('web.opsCenter.logTitle')" :description="t('web.opsCenter.logDescription')">
      <el-table :data="logEvents" size="small" border empty-text="暂无数据">
        <el-table-column prop="executionType" :label="t('web.opsCenter.executionType')" min-width="140">
          <template #default="{ row }">{{ formatExecutionType(row.executionType) }}</template>
        </el-table-column>
        <el-table-column prop="nodeCode" :label="t('web.runs.node')" min-width="160" show-overflow-tooltip />
        <el-table-column prop="workerGroupCode" :label="t('web.opsCenter.workerGroup')" min-width="140" show-overflow-tooltip />
        <el-table-column prop="logStorageType" label="Storage" min-width="120" show-overflow-tooltip />
        <el-table-column prop="logStatus" :label="t('web.opsCenter.logStatus')" min-width="120" show-overflow-tooltip />
        <el-table-column prop="logErrorSummary" :label="t('web.runs.message')" min-width="260" show-overflow-tooltip />
        <el-table-column :label="t('common.actions')" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRunLog(row.id)">{{ t("web.opsCenter.viewLog") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>

    <RunLogDrawer v-model="runLogDrawerOpen" :run-record-id="activeRunRecordId" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { MetricCard, SectionCard } from "@studio/ui";
import type {
  EntityId,
  OpsCenterLogEventView,
  OpsCenterOptionsView,
  OpsCenterOverviewView,
  OpsCenterQueryRequest,
  OpsCenterQueueItemView,
  OpsCenterRunIncidentView,
  OpsCenterServiceEventView,
  OpsCenterWorkerGroupView,
} from "@studio/api-sdk";
import { studioApi } from "@/api/studio";
import RunLogDrawer from "@/components/RunLogDrawer.vue";
import { useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";

const { t } = useI18n();
const router = useRouter();
const authStore = useAuthStore();
const PAGE_SIZE = 8;
const AUTO_REFRESH_MS = 30_000;

const options = reactive<OpsCenterOptionsView>({
  executionTypes: [],
  statuses: [],
  workerGroups: [],
});
const filters = reactive({
  executionType: "",
  status: "",
  workerGroupCode: "",
});
const timePreset = ref("24h");
const timeRange = ref<[string, string] | []>([]);
const autoRefresh = ref(true);
const isLoading = ref(false);
const overview = ref<OpsCenterOverviewView | null>(null);
const queueItems = ref<OpsCenterQueueItemView[]>([]);
const workerGroups = ref<OpsCenterWorkerGroupView[]>([]);
const runIncidents = ref<OpsCenterRunIncidentView[]>([]);
const serviceEvents = ref<OpsCenterServiceEventView[]>([]);
const ingestionEvents = ref<OpsCenterServiceEventView[]>([]);
const logEvents = ref<OpsCenterLogEventView[]>([]);
const lastUpdatedAt = ref("");
const runLogDrawerOpen = ref(false);
const activeRunRecordId = ref<EntityId | null>(null);
const hasBoundWorkerGroup = computed(() =>
  Number(overview.value?.boundWorkerGroups || 0) > 0 || workerGroups.value.some((item) => item.boundToProject),
);
const isWorkerUnassigned = computed(() => overview.value != null && !hasBoundWorkerGroup.value);
const isWorkerOffline = computed(() =>
  overview.value != null
  && hasBoundWorkerGroup.value
  && Number(overview.value.onlineWorkerInstances || 0) <= 0,
);
let refreshTimer: number | null = null;
let loadRequestId = 0;

onMounted(async () => {
  applyTimePreset("24h");
  await loadOptions();
  await loadAll();
  resetAutoRefresh();
});

onBeforeUnmount(() => {
  stopAutoRefresh();
});

watch(autoRefresh, () => {
  resetAutoRefresh();
});

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], async () => {
  filters.workerGroupCode = "";
  runLogDrawerOpen.value = false;
  activeRunRecordId.value = null;
  await loadOptions();
  await loadAll();
});

async function loadOptions() {
  try {
    const payload = await studioApi.opsCenter.options();
    options.executionTypes = payload.executionTypes || [];
    options.statuses = payload.statuses || [];
    options.workerGroups = payload.workerGroups || [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.opsCenter.loadFailed"));
  }
}

async function loadAll() {
  const requestId = ++loadRequestId;
  if (timePreset.value !== "custom") {
    applyTimePreset(timePreset.value);
  }
  isLoading.value = true;
  try {
    const query = buildQuery();
    const [overviewPayload, queuePage, workerPage, runPage, servicePage, ingestionPage, logPage] = await Promise.all([
      studioApi.opsCenter.queryOverview(query),
      studioApi.opsCenter.queryQueue({ ...query, pageNo: 1, pageSize: PAGE_SIZE }),
      studioApi.opsCenter.queryWorkers({ ...query, pageNo: 1, pageSize: PAGE_SIZE }),
      studioApi.opsCenter.queryRuns({ ...query, pageNo: 1, pageSize: PAGE_SIZE }),
      studioApi.opsCenter.queryServiceEvents({ ...query, pageNo: 1, pageSize: PAGE_SIZE }),
      studioApi.opsCenter.queryIngestionEvents({ ...query, pageNo: 1, pageSize: PAGE_SIZE }),
      studioApi.opsCenter.queryLogEvents({ ...query, pageNo: 1, pageSize: PAGE_SIZE }),
    ]);
    if (requestId !== loadRequestId) {
      return;
    }
    overview.value = overviewPayload;
    queueItems.value = queuePage.items || [];
    workerGroups.value = workerPage.items || [];
    runIncidents.value = runPage.items || [];
    serviceEvents.value = servicePage.items || [];
    ingestionEvents.value = ingestionPage.items || [];
    logEvents.value = logPage.items || [];
    lastUpdatedAt.value = formatDateTime(new Date());
  } catch (error) {
    if (requestId === loadRequestId) {
      ElMessage.error(error instanceof Error ? error.message : t("web.opsCenter.loadFailed"));
    }
  } finally {
    if (requestId === loadRequestId) {
      isLoading.value = false;
    }
  }
}

function buildQuery(): OpsCenterQueryRequest {
  return {
    startTime: timeRange.value[0],
    endTime: timeRange.value[1],
    executionType: filters.executionType || undefined,
    status: filters.status || undefined,
    workerGroupCode: filters.workerGroupCode || undefined,
  };
}

function search() {
  void loadAll();
}

function resetFilters() {
  filters.executionType = "";
  filters.status = "";
  filters.workerGroupCode = "";
  timePreset.value = "24h";
  applyTimePreset("24h");
  void loadAll();
}

function handleTimePresetChange(value: string | number | boolean) {
  const preset = String(value);
  if (preset !== "custom") {
    applyTimePreset(preset);
    void loadAll();
  }
}

function applyTimePreset(preset: string) {
  const end = new Date();
  const start = new Date(end);
  if (preset === "30d") {
    start.setDate(start.getDate() - 30);
  } else if (preset === "7d") {
    start.setDate(start.getDate() - 7);
  } else {
    start.setHours(start.getHours() - 24);
  }
  timeRange.value = [formatDateTime(start), formatDateTime(end)];
}

function resetAutoRefresh() {
  stopAutoRefresh();
  if (autoRefresh.value) {
    refreshTimer = window.setInterval(() => {
      void loadAll();
    }, AUTO_REFRESH_MS);
  }
}

function stopAutoRefresh() {
  if (refreshTimer) {
    window.clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

function openRun(row: OpsCenterRunIncidentView) {
  if (row.workflowRunId) {
    void router.push({ path: `/runs/${row.workflowRunId}` });
    return;
  }
  if (row.collectionTaskId) {
    void router.push({ path: "/collection-task-runs", query: { collectionTaskId: String(row.collectionTaskId) } });
    return;
  }
  if (row.qualityTaskId) {
    void router.push({ path: "/quality-task-runs", query: { qualityTaskId: String(row.qualityTaskId) } });
    return;
  }
  void router.push("/runs");
}

function openQueueTarget(row: OpsCenterQueueItemView) {
  if (row.workflowRunId) {
    void router.push({ path: `/runs/${row.workflowRunId}` });
    return;
  }
  if (row.workflowDefinitionId) {
    void router.push({ path: `/workflows/${row.workflowDefinitionId}` });
    return;
  }
  if (row.collectionTaskId) {
    void router.push({ path: `/collection-tasks/${row.collectionTaskId}/edit` });
    return;
  }
  if (row.qualityTaskId) {
    void router.push({ path: `/quality-tasks/${row.qualityTaskId}/edit` });
  }
}

function isQueueTargetClickable(row: OpsCenterQueueItemView) {
  return Boolean(row.workflowRunId || row.workflowDefinitionId || row.collectionTaskId || row.qualityTaskId);
}

function queueTargetName(row: OpsCenterQueueItemView) {
  if (row.targetName && row.targetName !== "-") {
    return row.targetName;
  }
  if (row.collectionTaskId) {
    return `采集任务 #${row.collectionTaskId}`;
  }
  if (row.qualityTaskId) {
    return `质量任务 #${row.qualityTaskId}`;
  }
  if (row.workflowDefinitionId) {
    return `工作流 #${row.workflowDefinitionId}`;
  }
  return "-";
}

function openRunLog(id?: EntityId) {
  if (!id) {
    return;
  }
  activeRunRecordId.value = id;
  runLogDrawerOpen.value = true;
}

function openServiceLogs(serviceId?: EntityId) {
  void router.push({ path: "/data-service-metrics/access-logs", query: serviceId ? { serviceId: String(serviceId) } : undefined });
}

function openIngestionLogs(serviceId?: EntityId) {
  void router.push({ path: "/data-ingestion-metrics/access-logs", query: serviceId ? { serviceId: String(serviceId) } : undefined });
}

function healthLabel(value?: string) {
  if (value === "CRITICAL") {
    return t("web.opsCenter.healthCritical");
  }
  if (value === "WARNING") {
    return t("web.opsCenter.healthWarning");
  }
  return t("web.opsCenter.healthHealthy");
}

function metricLabel(metric: { key?: string; label?: string }) {
  return metric.label || metric.key || "-";
}

function metricTone(value?: string) {
  if (value === "CRITICAL") {
    return "warning";
  }
  if (value === "WARNING") {
    return "accent";
  }
  return "success";
}

function statusTagType(value?: string) {
  const status = String(value || "").toUpperCase();
  if (status === "SUCCESS" || status === "ONLINE" || status === "AVAILABLE") {
    return "success";
  }
  if (status === "FAILED" || status === "ERROR") {
    return "danger";
  }
  if (status === "RUNNING" || status === "QUEUED") {
    return "warning";
  }
  return "info";
}

function workerTagType(row: OpsCenterWorkerGroupView) {
  if (row.displayStatus === "ONLINE") {
    return "success";
  }
  if (row.displayStatus === "NO_INSTANCE") {
    return "warning";
  }
  return "info";
}

function workerStatusLabel(row: OpsCenterWorkerGroupView) {
  if (row.displayStatus === "NO_INSTANCE") {
    return "无实例";
  }
  return row.displayStatus || "-";
}

function workerAssignmentTagType(row: OpsCenterWorkerGroupView) {
  if (!row.boundToProject) {
    return "warning";
  }
  return row.enabled === false ? "info" : "success";
}

function workerAssignmentLabel(row: OpsCenterWorkerGroupView) {
  if (!row.boundToProject) {
    return t("web.opsCenter.workerUnassigned");
  }
  if (row.enabled === false) {
    return t("web.opsCenter.workerAssignedDisabled");
  }
  return t("web.opsCenter.workerAssigned");
}

function formatExecutionType(value?: string) {
  const text = String(value || "");
  if (text === "WORKFLOW_NODE") {
    return "工作流节点";
  }
  if (text === "COLLECTION_TASK") {
    return "采集任务";
  }
  if (text === "QUALITY_TASK") {
    return "质量任务";
  }
  return text || "-";
}

function formatStatus(value?: string) {
  const text = String(value || "");
  const labels: Record<string, string> = {
    QUEUED: "排队中",
    RUNNING: "运行中",
    SUCCESS: "成功",
    FAILED: "失败",
    STOPPED: "已停止",
  };
  return labels[text.toUpperCase()] || text || "-";
}

function formatDuration(value?: number | string | null) {
  const ms = Number(value || 0);
  if (!Number.isFinite(ms) || ms <= 0) {
    return "0s";
  }
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) {
    return `${seconds}s`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m ${seconds % 60}s`;
  }
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m`;
}

function formatCount(value?: number | string | null) {
  const numeric = Number(value || 0);
  if (!Number.isFinite(numeric)) {
    return "0";
  }
  return String(numeric);
}

function formatDateTime(value: Date) {
  const pad = (input: number) => String(input).padStart(2, "0");
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())} ${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`;
}
</script>

<style scoped>
.ops-center-page {
  gap: 16px;
}

.ops-header,
.ops-health {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.ops-header h3 {
  margin: 0;
  font-size: 22px;
}

.ops-header p {
  margin: 6px 0 0;
  color: var(--studio-text-soft);
}

.ops-header__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}

.ops-updated,
.ops-muted {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.ops-filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  align-items: start;
}

.ops-filter-control {
  width: 100%;
  min-width: 0;
}

.ops-filter-date {
  min-width: 320px;
}

.ops-filter-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  grid-column: 1 / -1;
}

.ops-filter-grid :deep(.el-radio-group),
.ops-filter-grid :deep(.el-select),
.ops-filter-grid :deep(.el-date-editor) {
  width: 100%;
  min-width: 0;
  max-width: 100%;
}

.ops-filter-grid :deep(.el-date-editor.el-input__wrapper) {
  box-sizing: border-box;
}

.ops-filter-grid :deep(.el-range-input) {
  min-width: 0;
}

.ops-filter-preset {
  align-content: flex-start;
  display: flex;
  flex-wrap: wrap;
}

.ops-health {
  padding: 16px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  border-radius: 8px;
  background: rgba(16, 78, 139, 0.04);
}

.ops-health__main {
  min-width: 260px;
}

.ops-health__main span {
  display: block;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.ops-health__main strong {
  display: block;
  margin-top: 4px;
  font-size: 26px;
}

.ops-health__main p {
  margin: 6px 0 0;
  color: var(--studio-text-soft);
}

.ops-health__main.is-healthy strong {
  color: var(--el-color-success);
}

.ops-health__main.is-warning strong {
  color: var(--el-color-warning);
}

.ops-health__main.is-critical strong {
  color: var(--el-color-danger);
}

.ops-health__reasons {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.ops-metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 14px;
}

.ops-section-alert {
  margin-bottom: 12px;
}

.ops-grid {
  display: grid;
  gap: 16px;
}

.two-columns {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

@media (max-width: 1280px) {
  .two-columns {
    grid-template-columns: minmax(0, 1fr);
  }

  .ops-filter-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 860px) {
  .ops-header,
  .ops-health {
    flex-direction: column;
  }

  .ops-header__actions,
  .ops-health__reasons {
    justify-content: flex-start;
  }
}

@media (max-width: 640px) {
  .ops-filter-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .ops-filter-date {
    min-width: 0;
  }

  .ops-filter-actions {
    justify-content: flex-start;
  }
}
</style>
