<template>
  <div class="studio-page quality-metrics-page">
    <div class="studio-toolbar">
      <div>
        <h3>质量指标</h3>
        <p>从执行健康、治理风险、资产覆盖和问题处置四个角度，查看当前项目的数据质量状态。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="router.push('/quality-tasks')">质量任务</el-button>
        <el-button plain @click="router.push('/quality-task-runs')">运行日志</el-button>
        <el-button type="primary" :loading="isLoading" @click="loadCurrentTab">刷新</el-button>
      </div>
    </div>

    <QualityMetricsFilterSection
      v-model:time-preset="timePreset"
      v-model:time-range="timeRange"
      :active-tab="activeTab"
      :is-loading="isLoading"
      :filters="filters"
      :options="options"
      :model-options="modelOptions"
      :model-options-loading="modelOptionsLoading"
      :dimension-options="dimensionOptions"
      :severity-options="severityOptions"
      :issue-status-options="issueStatusOptions"
      :assignee-options="assigneeOptions"
      :filter-actions="filterSectionActions"
    />

    <el-tabs v-model="activeTab" class="quality-tabs">
      <el-tab-pane label="总览" name="overview">
        <div v-loading="loading.dashboard" class="quality-tab-body">
          <QualityMetricsOverviewTab
            :dashboard="dashboard"
            :has-score-trend="hasScoreTrend"
            :has-issue-trend="hasIssueTrend"
            :has-dimension-distribution="hasDimensionDistribution"
            :has-coverage-matrix="hasCoverageMatrix"
            :score-trend-option="scoreTrendOption"
            :issue-trend-option="issueTrendOption"
            :dimension-distribution-option="dimensionDistributionOption"
            :coverage-matrix-option="coverageMatrixOption"
            :overview-actions="overviewTabActions"
          />
        </div>
      </el-tab-pane>
      <el-tab-pane label="资产洞察" name="assets">
        <div v-loading="loading.assets" class="quality-tab-body">
          <QualityMetricsAssetsTab
            :asset-filters="assetFilters"
            :paged-assets="pagedAssets"
            :asset-pagination="assetPagination"
            :asset-total="assets.length"
            :asset-actions="assetTabActions"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="问题中心" name="issues">
        <div v-loading="loading.issues" class="quality-tab-body">
          <QualityMetricsIssuesTab
            :paged-issues="pagedIssues"
            :issue-pagination="issuePagination"
            :issue-total="issueTotal"
            :issue-actions="issueTabActions"
            @page-change="handleIssuePageChange"
            @page-size-change="handleIssuePageSizeChange"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <QualityMetricsAssetDrawer
      v-model:visible="assetDrawerVisible"
      :asset-detail-title="assetDetailTitle"
      :asset-detail="assetDetail"
      :asset-score-trend-option="assetScoreTrendOption"
      :asset-actions="assetDrawerActions"
    />

    <el-drawer v-model="issueDrawerVisible" :title="issueDetailTitle" size="58%">
      <template v-if="issueDetail">
        <div class="drawer-section">
          <div class="compact-panel issue-summary-grid">
            <div><strong>问题编号：</strong>{{ issueDetail.issueCode || issueDetail.id || "-" }}</div>
            <div><strong>问题类型：</strong>{{ issueTypeLabel(issueDetail.issueType) }}</div>
            <div><strong>资产：</strong>{{ issueDetail.datasourceName || "-" }} / {{ issueDetail.modelName || "-" }}</div>
            <div><strong>字段：</strong>{{ issueDetail.columnName || "全表" }}</div>
            <div><strong>规则：</strong>{{ issueDetail.ruleName || "-" }}</div>
            <div><strong>任务：</strong>{{ issueDetail.qualityTaskName || "-" }}</div>
          </div>

          <SectionCard title="处置动作" description="负责人、状态、严重级别和评论都会写入问题时间线。">
            <div class="issue-action-grid">
              <el-form-item label="负责人">
                <div class="inline-action">
                  <el-select v-model="assigneeDraft" clearable filterable placeholder="选择负责人">
                    <el-option v-for="item in assigneeOptions" :key="String(item.value)" :label="item.label" :value="item.value" />
                  </el-select>
                  <el-button type="primary" :loading="loading.action" @click="saveAssignee">保存</el-button>
                </div>
              </el-form-item>
              <el-form-item label="状态">
                <div class="inline-action">
                  <el-select v-model="statusDraft" placeholder="选择状态">
                    <el-option v-for="item in issueStatusOptions" :key="item.value" :label="item.label" :value="item.value" />
                  </el-select>
                  <el-button type="primary" :loading="loading.action" @click="saveIssueStatus">更新</el-button>
                </div>
                <el-input v-model="statusComment" type="textarea" :rows="2" placeholder="状态变更说明，可选" />
              </el-form-item>
              <el-form-item label="严重级别">
                <div class="inline-action">
                  <el-select v-model="severityDraft" placeholder="选择级别">
                    <el-option v-for="item in severityOptions" :key="item.value" :label="item.label" :value="item.value" />
                  </el-select>
                  <el-button type="primary" :loading="loading.action" @click="saveIssueSeverity">更新</el-button>
                </div>
                <el-input v-model="severityComment" type="textarea" :rows="2" placeholder="级别变更说明，可选" />
              </el-form-item>
              <el-form-item label="新增评论">
                <el-input v-model="commentDraft" type="textarea" :rows="3" placeholder="记录排查过程、临时修复或根因结论" />
                <div class="quality-form-actions">
                  <el-button type="primary" :loading="loading.action" @click="addIssueComment">添加评论</el-button>
                  <el-button plain :disabled="!issueDetail.lastRunRecordId" @click="openRunLog(issueDetail.lastRunRecordId)">查看关联日志</el-button>
                </div>
              </el-form-item>
            </div>
          </SectionCard>

          <SectionCard title="最近证据" description="仅展示规则、输出字段、运行日志、SQL 片段和时间线证据，不展示原始数据行。">
            <div class="evidence-grid">
              <div><strong>最近消息</strong><p>{{ issueDetail.latestMessage || "-" }}</p></div>
              <div><strong>SLA 截止</strong><p :class="{ danger: issueDetail.overdue }">{{ issueDetail.slaDueAt || "-" }}</p></div>
              <div><strong>出现次数</strong><p>{{ formatNumber(issueDetail.occurrenceCount) }}</p></div>
              <div><strong>连续失败</strong><p>{{ formatNumber(issueDetail.consecutiveFailureCount) }}</p></div>
            </div>
            <pre class="json-preview">{{ formatJson(issueDetail.currentEvidence) }}</pre>
          </SectionCard>

          <SectionCard title="问题时间线" description="创建、再次触发、恢复信号、处置动作和评论都会记录在这里。">
            <el-timeline v-if="issueDetail.timeline?.length">
              <el-timeline-item v-for="item in issueDetail.timeline" :key="String(item.id)" :timestamp="item.createdAt || ''" placement="top">
                <div class="timeline-card">
                  <strong>{{ item.title || item.eventType || "-" }}</strong>
                  <p>{{ item.message || "-" }}</p>
                  <span>{{ item.actorName || "system" }}</span>
                </div>
              </el-timeline-item>
            </el-timeline>
            <div v-else class="soft-panel quality-empty">暂无时间线</div>
          </SectionCard>
        </div>
      </template>
      <div v-else class="soft-panel quality-empty">请选择问题查看详情</div>
    </el-drawer>

    <RunLogDrawer v-model="runLogVisible" :run-record-id="activeRunRecordId" variant="quality-task" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import type { EChartsOption } from "echarts";
import type {
  EntityId,
  QualityAssetDetailView,
  QualityAssetRiskView,
  QualityIssueDetailView,
  QualityIssueSeverity,
  QualityIssueStatus,
  QualityIssueView,
  QualityMetricDashboardQueryRequest,
  QualityMetricDashboardView,
  QualityMetricOptionsView,
  RunMetricFilterOption,
  SystemProjectMember,
} from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import QualityMetricsAssetDrawer from "@/components/quality-metrics/QualityMetricsAssetDrawer.vue";
import QualityMetricsAssetsTab from "@/components/quality-metrics/QualityMetricsAssetsTab.vue";
import QualityMetricsFilterSection from "@/components/quality-metrics/QualityMetricsFilterSection.vue";
import QualityMetricsIssuesTab from "@/components/quality-metrics/QualityMetricsIssuesTab.vue";
import QualityMetricsOverviewTab from "@/components/quality-metrics/QualityMetricsOverviewTab.vue";
import {
  assetTitle,
  dimensionLabel,
  dimensionOptions,
  emptyDashboard,
  fieldIssues,
  formatJson,
  formatNumber,
  granularityLabel,
  issueStatusLabel,
  issueStatusOptions,
  issueStatusTone,
  issueTypeLabel,
  presetRange,
  recordId,
  recordNumber,
  recordText,
  runStatusTone,
  scorePercent,
  severityLabel,
  severityOptions,
  severityTone,
  toNumber,
  unique,
} from "@/components/quality-metrics/qualityMetricsViewSupport";
import type { TimePreset } from "@/components/quality-metrics/qualityMetricsViewSupport";
import RunLogDrawer from "@/components/RunLogDrawer.vue";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { useClientPagination } from "@/composables/useClientPagination";

type ActiveTab = "overview" | "assets" | "issues";

interface AssigneeOption {
  label: string;
  value: EntityId;
}

const router = useRouter();
const authStore = useAuthStore();
const LOCAL_LOADING_REQUEST = { studioSkipGlobalLoading: true } as const;

const activeTab = ref<ActiveTab>("overview");
const timePreset = ref<TimePreset>("7d");
const timeRange = ref<[string, string]>(presetRange("7d"));
const options = ref<QualityMetricOptionsView>({ datasources: [], models: [] });
const modelOptions = ref<RunMetricFilterOption[]>([]);
const modelOptionsLoading = ref(false);
const assigneeOptions = ref<AssigneeOption[]>([]);
const dashboard = ref<QualityMetricDashboardView>(emptyDashboard());
const assets = ref<QualityAssetRiskView[]>([]);
const issues = ref<QualityIssueView[]>([]);
const issueTotal = ref(0);
const assetDetail = ref<QualityAssetDetailView | null>(null);
const issueDetail = ref<QualityIssueDetailView | null>(null);
const assetDrawerVisible = ref(false);
const issueDrawerVisible = ref(false);
const runLogVisible = ref(false);
const activeRunRecordId = ref<EntityId | null | undefined>(undefined);
const assigneeDraft = ref<EntityId | null>(null);
const statusDraft = ref<QualityIssueStatus>("OPEN");
const severityDraft = ref<QualityIssueSeverity>("MEDIUM");
const statusComment = ref("");
const severityComment = ref("");
const commentDraft = ref("");

const filters = reactive<{
  datasourceId?: EntityId;
  modelId?: EntityId;
  ruleDimension?: string;
  granularity?: string;
  taskStatus?: string;
  severity?: QualityIssueSeverity;
  issueStatus?: QualityIssueStatus;
  assigneeUserId?: EntityId;
}>({});

const assetFilters = reactive({
  onlyProblemAssets: false,
  onlyLowCoverageAssets: false,
});

const loading = reactive({
  options: false,
  dashboard: false,
  assets: false,
  issues: false,
  assetDetail: false,
  issueDetail: false,
  action: false,
});

const { pagination: assetPagination, pagedItems: pagedAssets, resetPagination: resetAssetPagination } = useClientPagination(computed(() => assets.value), 10);
const issuePagination = reactive({
  page: 1,
  pageSize: 10,
});
const pagedIssues = computed(() => issues.value);

const activeTabLoading = computed(() => {
  switch (activeTab.value) {
    case "assets":
      return loading.assets;
    case "issues":
      return loading.issues;
    default:
      return loading.dashboard;
  }
});
const isLoading = computed(() => loading.options || activeTabLoading.value);
const hasScoreTrend = computed(() => (dashboard.value.scoreTrend ?? []).length > 0);
const hasIssueTrend = computed(() => (dashboard.value.issueTrend ?? []).length > 0);
const hasDimensionDistribution = computed(() => (dashboard.value.dimensionDistribution ?? []).some((item) => recordNumber(item, "totalRuns") > 0));
const hasCoverageMatrix = computed(() => (dashboard.value.coverageMatrix ?? []).length > 0);
const assetDetailTitle = computed(() => (assetDetail.value ? `资产洞察：${assetTitle(assetDetail.value)}` : "资产洞察"));
const issueDetailTitle = computed(() => (issueDetail.value ? `问题处置：${issueDetail.value.issueCode || issueDetail.value.title || issueDetail.value.id}` : "问题处置"));
const scoreTrendOption = computed<EChartsOption>(() => buildScoreTrendOption(dashboard.value.scoreTrend ?? []));
const issueTrendOption = computed<EChartsOption>(() => buildIssueTrendOption(dashboard.value.issueTrend ?? []));
const dimensionDistributionOption = computed<EChartsOption>(() => buildDimensionDistributionOption(dashboard.value.dimensionDistribution ?? []));
const coverageMatrixOption = computed<EChartsOption>(() => buildCoverageMatrixOption(dashboard.value.coverageMatrix ?? []));
const assetScoreTrendOption = computed<EChartsOption>(() => buildScoreTrendOption(assetDetail.value?.scoreTrend ?? []));

const filterSectionActions = {
  changeTimePreset,
  changeDatasource: changeDatasourceFilter,
  changeModelDropdownVisible,
  searchModels: searchModelOptions,
  reloadAll: searchCurrentTab,
  resetFilters,
};

const overviewTabActions = {
  summaryMetric,
  openAssetDrawer,
  assetTitle,
  formatNumber,
  recordText,
  recordNumber,
  dimensionLabel,
  openNoisyTarget,
};

const assetTabActions = {
  loadAssets,
  openAssetDrawer,
  assetTitle,
  scorePercent,
  formatNumber,
  dimensionLabel,
  runStatusTone,
};

const issueTabActions = {
  openIssueDrawer,
  openAssetByIssue,
  openRunLog,
  severityLabel,
  severityTone,
  issueStatusLabel,
  issueStatusTone,
  dimensionLabel,
};

const assetDrawerActions = {
  formatNumber,
  dimensionLabel,
  openIssueDrawer,
  severityLabel,
  severityTone,
  issueStatusLabel,
  issueStatusTone,
  recordId,
  recordText,
  recordNumber,
  granularityLabel,
  openTask,
  openRule,
  openRunLog,
  fieldIssues,
};

async function loadOptions() {
  loading.options = true;
  try {
    options.value = await studioApi.qualityMetrics.options(LOCAL_LOADING_REQUEST);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量指标选项失败");
  } finally {
    loading.options = false;
  }
}

function preserveSelectedModelOption(items: RunMetricFilterOption[]) {
  if (filters.modelId == null) {
    return items;
  }
  const selected = modelOptions.value.find((item) => String(item.id) === String(filters.modelId));
  if (!selected || items.some((item) => String(item.id) === String(filters.modelId))) {
    return items;
  }
  return [selected, ...items];
}

async function loadModelOptions(keyword = "") {
  modelOptionsLoading.value = true;
  try {
    const page = await studioApi.qualityMetrics.modelOptions({
      datasourceId: filters.datasourceId,
      keyword,
      pageNo: 1,
      pageSize: 50,
    }, LOCAL_LOADING_REQUEST);
    modelOptions.value = preserveSelectedModelOption(page.items ?? []);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载模型选项失败");
  } finally {
    modelOptionsLoading.value = false;
  }
}

function searchModelOptions(keyword: string) {
  void loadModelOptions(keyword);
}

function changeModelDropdownVisible(visible: boolean) {
  if (visible && modelOptions.value.length === 0) {
    void loadModelOptions("");
  }
}

function changeDatasourceFilter() {
  filters.modelId = undefined;
  modelOptions.value = [];
}

async function loadAssignees() {
  try {
    const members = await studioApi.system.projectMembers.list(authStore.currentProjectId ?? undefined, LOCAL_LOADING_REQUEST);
    assigneeOptions.value = normalizeMemberOptions(members);
    if (assigneeOptions.value.length > 0) {
      return;
    }
  } catch {
    // Project member API may be hidden for non-admin users; user list below keeps the drawer usable.
  }
  try {
    const users = await studioApi.users.list(LOCAL_LOADING_REQUEST);
    assigneeOptions.value = users
      .filter((item) => item.id != null)
      .map((item) => ({ value: item.id as EntityId, label: item.displayName || item.username || String(item.id) }));
  } catch {
    assigneeOptions.value = [];
  }
}

async function ensureAssigneesLoaded() {
  if (assigneeOptions.value.length > 0) {
    return;
  }
  await loadAssignees();
}

async function loadDashboard() {
  loading.dashboard = true;
  try {
    dashboard.value = await studioApi.qualityMetrics.queryDashboard({ ...baseQuery(), topN: 10 }, LOCAL_LOADING_REQUEST);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量指标总览失败");
  } finally {
    loading.dashboard = false;
  }
}

async function loadAssets() {
  loading.assets = true;
  try {
    assets.value = await studioApi.qualityMetrics.queryAssets({
      ...baseQuery(),
      onlyProblemAssets: assetFilters.onlyProblemAssets,
      onlyLowCoverageAssets: assetFilters.onlyLowCoverageAssets,
    }, LOCAL_LOADING_REQUEST);
    resetAssetPagination();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量资产失败");
  } finally {
    loading.assets = false;
  }
}

async function loadIssues() {
  loading.issues = true;
  try {
    const page = await studioApi.qualityMetrics.queryIssuesPage({
      ...baseQuery(),
      severity: filters.severity,
      status: filters.issueStatus,
      assigneeUserId: filters.assigneeUserId,
      pageNo: issuePagination.page,
      pageSize: issuePagination.pageSize,
    }, LOCAL_LOADING_REQUEST);
    const maxPage = Math.max(1, Math.ceil(page.total / issuePagination.pageSize));
    if (issuePagination.page > maxPage) {
      issuePagination.page = maxPage;
      await loadIssues();
      return;
    }
    issues.value = page.items;
    issueTotal.value = page.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量问题失败");
  } finally {
    loading.issues = false;
  }
}

async function loadCurrentTab() {
  switch (activeTab.value) {
    case "assets":
      await loadAssets();
      break;
    case "issues":
      await ensureAssigneesLoaded();
      await loadIssues();
      break;
    default:
      await loadDashboard();
      break;
  }
}

async function searchCurrentTab() {
  if (activeTab.value === "assets") {
    resetAssetPagination();
  }
  if (activeTab.value === "issues") {
    issuePagination.page = 1;
  }
  await loadCurrentTab();
}

async function resetFilters() {
  filters.datasourceId = undefined;
  filters.modelId = undefined;
  filters.ruleDimension = undefined;
  filters.granularity = undefined;
  filters.taskStatus = undefined;
  filters.severity = undefined;
  filters.issueStatus = undefined;
  filters.assigneeUserId = undefined;
  assetFilters.onlyProblemAssets = false;
  assetFilters.onlyLowCoverageAssets = false;
  modelOptions.value = [];
  timePreset.value = "7d";
  timeRange.value = presetRange("7d");
  resetAssetPagination();
  issuePagination.page = 1;
  await loadCurrentTab();
}

function handleIssuePageChange() {
  void loadIssues();
}

function handleIssuePageSizeChange() {
  issuePagination.page = 1;
  void loadIssues();
}

function baseQuery(): QualityMetricDashboardQueryRequest {
  return {
    datasourceId: filters.datasourceId,
    modelId: filters.modelId,
    ruleDimension: filters.ruleDimension,
    granularity: filters.granularity,
    taskStatus: filters.taskStatus,
    startTime: timeRange.value?.[0],
    endTime: timeRange.value?.[1],
  };
}

function changeTimePreset(value: string | number | boolean) {
  if (value === "custom") {
    return;
  }
  timeRange.value = presetRange(value as TimePreset);
}

async function openAssetDrawer(asset: QualityAssetRiskView) {
  if (!asset.assetId) {
    return;
  }
  assetDrawerVisible.value = true;
  loading.assetDetail = true;
  try {
    assetDetail.value = await studioApi.qualityMetrics.getAsset(encodeURIComponent(asset.assetId), {
      startTime: timeRange.value?.[0],
      endTime: timeRange.value?.[1],
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载资产详情失败");
  } finally {
    loading.assetDetail = false;
  }
}

async function openAssetByIssue(issue: QualityIssueView) {
  await openAssetDrawer({
    assetId: issue.assetId,
    datasourceId: issue.datasourceId,
    datasourceName: issue.datasourceName,
    datasourceTypeCode: issue.datasourceTypeCode,
    modelId: issue.modelId,
    modelName: issue.modelName,
    modelPhysicalLocator: issue.modelPhysicalLocator,
    executionHealthScore: 0,
    governanceRiskScore: 0,
    activeIssueCount: 0,
    overdueIssueCount: 0,
    coverageRate: 0,
    coverageDimensions: [],
    riskDimensions: [],
  });
}

async function openIssueDrawer(issue: QualityIssueView) {
  if (!issue.id) {
    return;
  }
  issueDrawerVisible.value = true;
  loading.issueDetail = true;
  try {
    const [detail] = await Promise.all([
      studioApi.qualityMetrics.getIssue(issue.id),
      ensureAssigneesLoaded(),
    ]);
    issueDetail.value = detail;
    syncIssueDrafts();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载问题详情失败");
  } finally {
    loading.issueDetail = false;
  }
}

function syncIssueDrafts() {
  assigneeDraft.value = issueDetail.value?.assigneeUserId ?? null;
  statusDraft.value = issueDetail.value?.status ?? "OPEN";
  severityDraft.value = issueDetail.value?.severity ?? "MEDIUM";
  statusComment.value = "";
  severityComment.value = "";
  commentDraft.value = "";
}

async function saveAssignee() {
  if (!issueDetail.value?.id) {
    return;
  }
  loading.action = true;
  try {
    issueDetail.value = await studioApi.qualityMetrics.assignIssue(issueDetail.value.id, { assigneeUserId: assigneeDraft.value });
    syncIssueDrafts();
    await refreshAfterIssueAction();
    ElMessage.success("负责人已更新");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "更新负责人失败");
  } finally {
    loading.action = false;
  }
}

async function saveIssueStatus() {
  if (!issueDetail.value?.id || !statusDraft.value) {
    return;
  }
  loading.action = true;
  try {
    issueDetail.value = await studioApi.qualityMetrics.updateIssueStatus(issueDetail.value.id, {
      status: statusDraft.value,
      comment: statusComment.value.trim() || undefined,
    });
    syncIssueDrafts();
    await refreshAfterIssueAction();
    ElMessage.success("问题状态已更新");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "更新问题状态失败");
  } finally {
    loading.action = false;
  }
}

async function saveIssueSeverity() {
  if (!issueDetail.value?.id || !severityDraft.value) {
    return;
  }
  loading.action = true;
  try {
    issueDetail.value = await studioApi.qualityMetrics.updateIssueSeverity(issueDetail.value.id, {
      severity: severityDraft.value,
      comment: severityComment.value.trim() || undefined,
    });
    syncIssueDrafts();
    await refreshAfterIssueAction();
    ElMessage.success("严重级别已更新");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "更新严重级别失败");
  } finally {
    loading.action = false;
  }
}

async function addIssueComment() {
  if (!issueDetail.value?.id || !commentDraft.value.trim()) {
    ElMessage.warning("请先填写评论内容");
    return;
  }
  loading.action = true;
  try {
    issueDetail.value = await studioApi.qualityMetrics.addIssueComment(issueDetail.value.id, { content: commentDraft.value.trim() });
    syncIssueDrafts();
    await refreshAfterIssueAction();
    ElMessage.success("评论已添加");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "添加评论失败");
  } finally {
    loading.action = false;
  }
}

async function refreshAfterIssueAction() {
  patchIssueListItem(issueDetail.value);
  if (assetDrawerVisible.value && assetDetail.value?.assetId) {
    await openAssetDrawer({ ...assetDetail.value });
  }
}

function patchIssueListItem(detail?: QualityIssueDetailView | null) {
  if (!detail?.id) {
    return;
  }
  const index = issues.value.findIndex((item) => String(item.id) === String(detail.id));
  if (index < 0) {
    return;
  }
  if (!matchesCurrentIssueFilters(detail)) {
    issues.value.splice(index, 1);
    issueTotal.value = Math.max(0, issueTotal.value - 1);
    return;
  }
  issues.value.splice(index, 1, { ...issues.value[index], ...detail });
}

function matchesCurrentIssueFilters(issue: QualityIssueView) {
  if (filters.datasourceId != null && String(issue.datasourceId) !== String(filters.datasourceId)) {
    return false;
  }
  if (filters.modelId != null && String(issue.modelId) !== String(filters.modelId)) {
    return false;
  }
  if (filters.ruleDimension && issue.ruleDimension !== filters.ruleDimension) {
    return false;
  }
  if (filters.granularity && issue.granularity !== filters.granularity) {
    return false;
  }
  if (filters.severity && issue.severity !== filters.severity) {
    return false;
  }
  if (filters.issueStatus && issue.status !== filters.issueStatus) {
    return false;
  }
  if (filters.assigneeUserId != null && String(issue.assigneeUserId) !== String(filters.assigneeUserId)) {
    return false;
  }
  return true;
}

function openNoisyTarget(row: Record<string, unknown>) {
  const id = recordId(row, "id");
  if (!id) {
    return;
  }
  if (recordText(row, "targetType") === "RULE") {
    openRule(id);
    return;
  }
  openTask(id);
}

function openTask(id?: EntityId | null) {
  if (id == null) {
    return;
  }
  router.push(`/quality-tasks/${id}/edit`);
}

function openRule(id?: EntityId | null) {
  if (id == null) {
    return;
  }
  router.push(`/quality-rules/${id}/edit`);
}

function openRunLog(runRecordId?: EntityId | null) {
  if (runRecordId == null) {
    return;
  }
  activeRunRecordId.value = runRecordId;
  runLogVisible.value = true;
}

function summaryMetric(key: string) {
  return formatNumber(dashboard.value.summaryMetrics?.[key]);
}

function buildScoreTrendOption(rows: Array<{ dateLabel?: string; executionHealthScore?: number; governanceRiskScore?: number }>): EChartsOption {
  return {
    tooltip: { trigger: "axis" },
    legend: { top: 0, data: ["执行健康分", "治理风险指数"] },
    grid: { left: 18, right: 18, top: 48, bottom: 18, containLabel: true },
    xAxis: { type: "category", data: rows.map((item) => item.dateLabel || "-") },
    yAxis: { type: "value", min: 0, max: 100 },
    series: [
      { name: "执行健康分", type: "line", smooth: true, data: rows.map((item) => toNumber(item.executionHealthScore)) },
      { name: "治理风险指数", type: "line", smooth: true, data: rows.map((item) => toNumber(item.governanceRiskScore)) },
    ],
  };
}

function buildIssueTrendOption(rows: Array<Record<string, unknown>>): EChartsOption {
  const keys = [
    ["newCritical", "新增严重"],
    ["newHigh", "新增高"],
    ["newMedium", "新增中"],
    ["newLow", "新增低"],
    ["resolvedCritical", "解决严重"],
    ["resolvedHigh", "解决高"],
    ["resolvedMedium", "解决中"],
    ["resolvedLow", "解决低"],
  ];
  return {
    tooltip: { trigger: "axis" },
    legend: { type: "scroll", top: 0, data: keys.map((item) => item[1]) },
    grid: { left: 18, right: 18, top: 58, bottom: 18, containLabel: true },
    xAxis: { type: "category", data: rows.map((item) => recordText(item, "dateLabel") || "-") },
    yAxis: { type: "value", minInterval: 1 },
    series: keys.map(([key, name]) => ({
      name,
      type: "line",
      smooth: true,
      stack: name.startsWith("新增") ? "new" : "resolved",
      areaStyle: {},
      data: rows.map((item) => recordNumber(item, key)),
    })),
  };
}

function buildDimensionDistributionOption(rows: Array<Record<string, unknown>>): EChartsOption {
  return {
    tooltip: { trigger: "axis" },
    grid: { left: 18, right: 18, top: 30, bottom: 18, containLabel: true },
    xAxis: { type: "category", data: rows.map((item) => dimensionLabel(recordText(item, "ruleDimension"))) },
    yAxis: { type: "value", axisLabel: { formatter: "{value}%" } },
    series: [{ name: "失败占比", type: "bar", barMaxWidth: 36, data: rows.map((item) => recordNumber(item, "failureRatio")) }],
  };
}

function buildCoverageMatrixOption(rows: Array<Record<string, unknown>>): EChartsOption {
  const datasourceTypes = unique(rows.map((item) => recordText(item, "datasourceTypeCode") || "UNKNOWN"));
  const dimensions = unique(rows.map((item) => recordText(item, "ruleDimension") || "UNKNOWN"));
  const data = rows.map((item) => [
    dimensions.indexOf(recordText(item, "ruleDimension") || "UNKNOWN"),
    datasourceTypes.indexOf(recordText(item, "datasourceTypeCode") || "UNKNOWN"),
    recordNumber(item, "value"),
  ]);
  const max = Math.max(1, ...data.map((item) => Number(item[2] ?? 0)));
  return {
    tooltip: {
      position: "top",
      formatter(params: unknown) {
        const candidate = Array.isArray(params) ? params[0] : params;
        const data = candidate && typeof candidate === "object" && "data" in candidate
          ? (candidate as { data?: unknown }).data
          : undefined;
        const value = Array.isArray(data) ? data : [];
        return `${dimensionLabel(dimensions[Number(value[0])] || "")}<br/>${datasourceTypes[Number(value[1])] || "-"}：${value[2] ?? 0}`;
      },
    },
    grid: { left: 88, right: 24, top: 30, bottom: 46 },
    xAxis: { type: "category", data: dimensions.map(dimensionLabel), splitArea: { show: true } },
    yAxis: { type: "category", data: datasourceTypes, splitArea: { show: true } },
    visualMap: { min: 0, max, calculable: true, orient: "horizontal", left: "center", bottom: 0 },
    series: [{ name: "覆盖资产数", type: "heatmap", data, label: { show: true } }],
  };
}

function normalizeMemberOptions(members: SystemProjectMember[]) {
  const map = new Map<string, AssigneeOption>();
  for (const member of members) {
    if (member.userId == null) {
      continue;
    }
    const key = String(member.userId);
    if (!map.has(key)) {
      map.set(key, { value: member.userId, label: member.displayName || member.username || String(member.userId) });
    }
  }
  return [...map.values()];
}

onMounted(async () => {
  await loadOptions();
  await loadCurrentTab();
});

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], async () => {
  if (authStore.isAuthenticated) {
    modelOptions.value = [];
    assigneeOptions.value = [];
    await loadOptions();
    await loadCurrentTab();
  }
});

watch(activeTab, async () => {
  if (authStore.isAuthenticated) {
    await loadCurrentTab();
  }
});
</script>

<style scoped>
.quality-metrics-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.quality-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

.quality-tabs {
  min-width: 0;
}

.quality-tab-body {
  min-height: 160px;
}

.quality-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  color: var(--studio-text-soft);
}

.danger {
  color: var(--studio-danger);
}

.drawer-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.compact-panel {
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  background: rgba(16, 78, 139, 0.05);
}

.issue-summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 14px;
}

.issue-action-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
}

.inline-action {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  width: 100%;
}

.evidence-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.evidence-grid strong {
  display: block;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.evidence-grid p {
  margin: 6px 0 0;
}

.json-preview {
  margin: 14px 0 0;
  max-height: 260px;
  overflow: auto;
  padding: 12px;
  border-radius: 12px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  background: rgba(16, 78, 139, 0.06);
  color: var(--studio-text);
  font-family: Consolas, "JetBrains Mono", "Courier New", monospace;
  font-size: 12px;
  line-height: 1.6;
}

.timeline-card {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(16, 78, 139, 0.06);
}

.timeline-card p {
  margin: 6px 0;
  color: var(--studio-text);
}

.timeline-card span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

@media (max-width: 1180px) {
  .issue-action-grid,
  .evidence-grid {
    grid-template-columns: minmax(0, 1fr);
  }

}

@media (max-width: 760px) {
  .issue-summary-grid,
  .issue-action-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .quality-form-actions {
    justify-content: flex-start;
    flex-wrap: wrap;
  }
}
</style>
