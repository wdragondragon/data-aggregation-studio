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
        <el-button type="primary" :loading="isLoading" @click="reloadAll">刷新</el-button>
      </div>
    </div>

    <QualityMetricsFilterSection
      v-model:time-preset="timePreset"
      v-model:time-range="timeRange"
      :active-tab="activeTab"
      :is-loading="isLoading"
      :filters="filters"
      :options="options"
      :dimension-options="dimensionOptions"
      :severity-options="severityOptions"
      :issue-status-options="issueStatusOptions"
      :assignee-options="assigneeOptions"
      :filter-actions="filterSectionActions"
    />

    <el-tabs v-model="activeTab" class="quality-tabs">
      <el-tab-pane label="总览" name="overview">
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
      </el-tab-pane>
      <el-tab-pane label="资产洞察" name="assets">
        <SectionCard title="资产风险列表" description="资产等价于当前项目中的数据源 + 模型，字段级问题会归属到对应模型。">
          <div class="quality-section-actions">
            <el-checkbox v-model="assetFilters.onlyProblemAssets" @change="loadAssets">仅看有问题资产</el-checkbox>
            <el-checkbox v-model="assetFilters.onlyLowCoverageAssets" @change="loadAssets">仅看低覆盖资产</el-checkbox>
          </div>
          <StudioTableShell min-width="1300px">
            <el-table :data="pagedAssets" border size="small">
            <el-table-column label="资产名称" min-width="240">
              <template #default="{ row }">
                <div class="table-entity-cell">
                  <el-button link type="primary" class="table-entity-cell__title" @click="openAssetDrawer(row)">{{ assetTitle(row) }}</el-button>
                  <span v-if="row.modelPhysicalLocator && row.modelPhysicalLocator !== assetTitle(row)" class="table-entity-cell__meta">{{ row.modelPhysicalLocator }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="数据源" min-width="190">
              <template #default="{ row }">{{ row.datasourceName || "-" }} / {{ row.datasourceTypeCode || "-" }}</template>
            </el-table-column>
            <el-table-column label="执行健康分" width="130">
              <template #default="{ row }">
                <el-progress :percentage="scorePercent(row.executionHealthScore)" :stroke-width="8" :show-text="false" />
                <span>{{ formatNumber(row.executionHealthScore) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="治理风险指数" width="130">
              <template #default="{ row }">
                <el-progress :percentage="scorePercent(row.governanceRiskScore)" :stroke-width="8" :show-text="false" status="warning" />
                <span>{{ formatNumber(row.governanceRiskScore) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="活跃/超时" width="110" align="right" header-align="right">
              <template #default="{ row }">{{ formatNumber(row.activeIssueCount) }} / {{ formatNumber(row.overdueIssueCount) }}</template>
            </el-table-column>
            <el-table-column label="覆盖维度" min-width="220">
              <template #default="{ row }">
                <div class="tag-row">
                  <el-tag v-for="item in row.coverageDimensions" :key="item" size="small">{{ dimensionLabel(item) }}</el-tag>
                  <span v-if="!row.coverageDimensions?.length" class="cell-subtle">未覆盖</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="最近执行" min-width="180">
              <template #default="{ row }">
                <div class="stack-cell">
                  <StatusPill :label="row.latestRunStatus || '无运行'" :tone="runStatusTone(row.latestRunStatus)" />
                  <span class="cell-subtle">{{ row.latestRunAt || "-" }}</span>
                </div>
              </template>
            </el-table-column>
            </el-table>
          </StudioTableShell>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="assetPagination.page"
              v-model:page-size="assetPagination.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="[10, 20, 50, 100]"
              :total="assets.length"
            />
          </div>
        </SectionCard>
      </el-tab-pane>

      <el-tab-pane label="问题中心" name="issues">
        <SectionCard title="问题列表" description="同一问题签名会聚合为一条记录，状态处置和评论在右侧抽屉中完成。">
          <StudioTableShell min-width="1720px">
            <el-table :data="pagedIssues" border size="small" table-layout="auto">
            <el-table-column label="问题编号" min-width="140">
              <template #default="{ row }">
                <el-button link type="primary" @click="openIssueDrawer(row)">{{ row.issueCode || row.id || "-" }}</el-button>
              </template>
            </el-table-column>
            <el-table-column label="严重级别" width="112" align="center" header-align="center">
              <template #default="{ row }">
                <StatusPill :label="severityLabel(row.severity)" :tone="severityTone(row.severity)" />
              </template>
            </el-table-column>
            <el-table-column label="状态" width="132" align="center" header-align="center">
              <template #default="{ row }">
                <StatusPill :label="issueStatusLabel(row.status)" :tone="issueStatusTone(row.status)" />
              </template>
            </el-table-column>
            <el-table-column label="资产" min-width="220">
              <template #default="{ row }">
                <div class="table-entity-cell">
                  <el-button v-if="row.assetId" link type="primary" class="table-entity-cell__title" @click="openAssetByIssue(row)">{{ row.modelName || "-" }}</el-button>
                  <span v-else class="table-entity-cell__title">{{ row.modelName || "-" }}</span>
                  <span class="table-entity-cell__meta">{{ row.datasourceName || "-" }} · {{ row.columnName || "全表" }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="规则 / 任务" min-width="240">
              <template #default="{ row }">
                <div class="table-entity-cell">
                  <span class="table-entity-cell__title">{{ row.ruleName || "-" }}</span>
                  <span class="table-entity-cell__meta">{{ row.qualityTaskName || "-" }} · {{ dimensionLabel(row.ruleDimension) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="首次 / 最近" min-width="200">
              <template #default="{ row }">
                <div class="stack-cell">
                  <span>{{ row.firstSeenAt || "-" }}</span>
                  <span class="cell-subtle">{{ row.lastSeenAt || "-" }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="负责人 / SLA" min-width="180">
              <template #default="{ row }">
                <div class="stack-cell">
                  <span>{{ row.assigneeName || "未认领" }}</span>
                  <span class="cell-subtle" :class="{ danger: row.overdue }">{{ row.slaDueAt || "-" }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="最近证据" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">
                <MessagePreviewText :text="row.latestMessage" />
              </template>
            </el-table-column>
              <el-table-column label="操作" width="130" align="center" header-align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openIssueDrawer(row)">处置</el-button>
                <el-button link type="primary" :disabled="!row.lastRunRecordId" @click="openRunLog(row.lastRunRecordId)">日志</el-button>
              </template>
            </el-table-column>
            </el-table>
          </StudioTableShell>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="issuePagination.page"
              v-model:page-size="issuePagination.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="[10, 20, 50, 100]"
              :total="issues.length"
            />
          </div>
        </SectionCard>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="assetDrawerVisible" :title="assetDetailTitle" size="62%">
      <template v-if="assetDetail">
        <div class="drawer-section">
          <div class="metric-grid drawer-metric-grid">
            <MetricCard label="执行健康分" :value="formatNumber(assetDetail.executionHealthScore)" tone="success" />
            <MetricCard label="治理风险指数" :value="formatNumber(assetDetail.governanceRiskScore)" tone="warning" />
            <MetricCard label="活跃问题" :value="formatNumber(assetDetail.activeIssueCount)" tone="primary" />
            <MetricCard label="覆盖率" :value="`${formatNumber(assetDetail.coverageRate)}%`" tone="accent" />
          </div>

          <SectionCard title="资产趋势" description="过去窗口内该资产的健康分与风险指数变化。">
            <EChartPanel v-if="assetDetail.scoreTrend?.length" :option="assetScoreTrendOption" height="260px" />
            <div v-else class="soft-panel quality-empty">暂无资产趋势</div>
          </SectionCard>

          <SectionCard title="维度覆盖与风险" description="覆盖维度代表已绑定任务，风险维度来自当前活跃问题。">
            <div class="tag-row quality-tag-block">
              <span class="tag-row__label">覆盖维度</span>
              <el-tag v-for="item in assetDetail.coverageDimensions" :key="item" size="small">{{ dimensionLabel(item) }}</el-tag>
              <span v-if="!assetDetail.coverageDimensions?.length" class="cell-subtle">未覆盖</span>
            </div>
            <div class="tag-row quality-tag-block">
              <span class="tag-row__label">风险维度</span>
              <el-tag v-for="item in assetDetail.riskDimensions" :key="item" type="warning" size="small">{{ dimensionLabel(item) }}</el-tag>
              <span v-if="!assetDetail.riskDimensions?.length" class="cell-subtle">暂无风险</span>
            </div>
          </SectionCard>

          <SectionCard title="当前活跃问题" description="点击问题进入问题中心抽屉处置。">
            <el-table :data="assetDetail.activeIssues" border size="small">
              <el-table-column label="问题" min-width="220">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openIssueDrawer(row)">{{ row.issueCode || row.title || "-" }}</el-button>
                </template>
              </el-table-column>
              <el-table-column label="级别" width="100" align="center">
                <template #default="{ row }"><StatusPill :label="severityLabel(row.severity)" :tone="severityTone(row.severity)" /></template>
              </el-table-column>
              <el-table-column label="状态" width="120" align="center">
                <template #default="{ row }"><StatusPill :label="issueStatusLabel(row.status)" :tone="issueStatusTone(row.status)" /></template>
              </el-table-column>
              <el-table-column label="最近出现" min-width="160" prop="lastSeenAt" />
            </el-table>
          </SectionCard>

          <SectionCard title="已绑定质量任务" description="仅提供跳转，不在资产抽屉中直接修改任务。">
            <el-table :data="assetDetail.relatedTasks" border size="small">
              <el-table-column label="任务" min-width="220">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openTask(recordId(row, 'taskId'))">{{ recordText(row, "taskName") || "-" }}</el-button>
                </template>
              </el-table-column>
              <el-table-column label="规则" min-width="180">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openRule(recordId(row, 'ruleId'))">{{ recordText(row, "ruleName") || "-" }}</el-button>
                </template>
              </el-table-column>
              <el-table-column label="维度/粒度" min-width="150">
                <template #default="{ row }">{{ dimensionLabel(recordText(row, "ruleDimension")) }} / {{ granularityLabel(recordText(row, "granularity")) }}</template>
              </el-table-column>
              <el-table-column label="最近运行" min-width="170">
                <template #default="{ row }">
                  <el-button link type="primary" :disabled="!recordId(row, 'latestRunRecordId')" @click="openRunLog(recordId(row, 'latestRunRecordId'))">
                    {{ recordText(row, "latestRunStatus") || "无运行" }}
                  </el-button>
                  <span class="cell-subtle">{{ recordText(row, "latestRunAt") || "-" }}</span>
                </template>
              </el-table-column>
            </el-table>
          </SectionCard>

          <SectionCard title="最近失败证据" description="展示最近失败或告警运行，保留日志入口。">
            <el-table :data="assetDetail.latestFailures" border size="small">
              <el-table-column label="任务/规则" min-width="220">
                <template #default="{ row }">
                  <div class="stack-cell">
                    <span>{{ recordText(row, "taskName") || "-" }}</span>
                    <span class="cell-subtle">{{ recordText(row, "ruleName") || "-" }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="摘要" min-width="260" show-overflow-tooltip>
                <template #default="{ row }">
                  <MessagePreviewText :text="recordText(row, 'message')" />
                </template>
              </el-table-column>
              <el-table-column label="结束时间" min-width="160">
                <template #default="{ row }">{{ recordText(row, "endedAt") || "-" }}</template>
              </el-table-column>
            <el-table-column label="操作" width="100" align="center" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" :disabled="!recordId(row, 'runRecordId')" @click="openRunLog(recordId(row, 'runRecordId'))">日志</el-button>
                </template>
              </el-table-column>
            </el-table>
          </SectionCard>

          <SectionCard title="字段级问题分组" description="字段级问题依附在资产下展示。">
            <el-collapse v-if="assetDetail.fieldIssueGroups?.length">
              <el-collapse-item
                v-for="group in assetDetail.fieldIssueGroups"
                :key="recordText(group, 'columnName')"
                :title="`${recordText(group, 'columnName') || '-'} · ${recordNumber(group, 'issueCount')} 个问题`"
              >
                <el-table :data="fieldIssues(group)" border size="small">
                  <el-table-column label="问题" min-width="220">
                    <template #default="{ row }">
                      <el-button link type="primary" @click="openIssueDrawer(row)">{{ row.issueCode || row.title || "-" }}</el-button>
                    </template>
                  </el-table-column>
                  <el-table-column label="级别" width="100" align="center">
                    <template #default="{ row }"><StatusPill :label="severityLabel(row.severity)" :tone="severityTone(row.severity)" /></template>
                  </el-table-column>
                  <el-table-column label="最近出现" min-width="160" prop="lastSeenAt" />
                </el-table>
              </el-collapse-item>
            </el-collapse>
            <div v-else class="soft-panel quality-empty">暂无字段级问题</div>
          </SectionCard>
        </div>
      </template>
      <div v-else class="soft-panel quality-empty">请选择资产查看详情</div>
    </el-drawer>

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
  SystemProjectMember,
} from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import EChartPanel from "@/components/EChartPanel.vue";
import MessagePreviewText from "@/components/MessagePreviewText.vue";
import QualityMetricsFilterSection from "@/components/quality-metrics/QualityMetricsFilterSection.vue";
import QualityMetricsOverviewTab from "@/components/quality-metrics/QualityMetricsOverviewTab.vue";
import RunLogDrawer from "@/components/RunLogDrawer.vue";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { useClientPagination } from "@/composables/useClientPagination";

type ActiveTab = "overview" | "assets" | "issues";
type TimePreset = "24h" | "7d" | "30d" | "custom";
type PillTone = "primary" | "success" | "warning" | "danger" | "neutral";

interface AssigneeOption {
  label: string;
  value: EntityId;
}

const router = useRouter();
const authStore = useAuthStore();

const dimensionOptions = [
  { label: "一致性", value: "CONSISTENCY" },
  { label: "准确性", value: "ACCURACY" },
  { label: "唯一性", value: "UNIQUENESS" },
  { label: "及时性", value: "TIMELINESS" },
  { label: "完整性", value: "COMPLETENESS" },
  { label: "有效性", value: "VALIDITY" },
];

const severityOptions: Array<{ label: string; value: QualityIssueSeverity }> = [
  { label: "低", value: "LOW" },
  { label: "中", value: "MEDIUM" },
  { label: "高", value: "HIGH" },
  { label: "严重", value: "CRITICAL" },
];

const issueStatusOptions: Array<{ label: string; value: QualityIssueStatus }> = [
  { label: "待处理", value: "OPEN" },
  { label: "已认领", value: "ACKNOWLEDGED" },
  { label: "排查中", value: "INVESTIGATING" },
  { label: "已缓解", value: "MITIGATED" },
  { label: "已解决", value: "RESOLVED" },
  { label: "误报", value: "FALSE_POSITIVE" },
];

const activeTab = ref<ActiveTab>("overview");
const timePreset = ref<TimePreset>("7d");
const timeRange = ref<[string, string]>(presetRange("7d"));
const options = ref<QualityMetricOptionsView>({ datasources: [], models: [] });
const assigneeOptions = ref<AssigneeOption[]>([]);
const dashboard = ref<QualityMetricDashboardView>(emptyDashboard());
const assets = ref<QualityAssetRiskView[]>([]);
const issues = ref<QualityIssueView[]>([]);
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
const { pagination: issuePagination, pagedItems: pagedIssues, resetPagination: resetIssuePagination } = useClientPagination(computed(() => issues.value), 10);

const isLoading = computed(() => loading.options || loading.dashboard || loading.assets || loading.issues);
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
  reloadAll,
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

async function loadOptions() {
  loading.options = true;
  try {
    options.value = await studioApi.qualityMetrics.options();
    await loadAssignees();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量指标选项失败");
  } finally {
    loading.options = false;
  }
}

async function loadAssignees() {
  try {
    const members = await studioApi.system.projectMembers.list(authStore.currentProjectId ?? undefined);
    assigneeOptions.value = normalizeMemberOptions(members);
    if (assigneeOptions.value.length > 0) {
      return;
    }
  } catch {
    // Project member API may be hidden for non-admin users; user list below keeps the drawer usable.
  }
  try {
    const users = await studioApi.users.list();
    assigneeOptions.value = users
      .filter((item) => item.id != null)
      .map((item) => ({ value: item.id as EntityId, label: item.displayName || item.username || String(item.id) }));
  } catch {
    assigneeOptions.value = [];
  }
}

async function loadDashboard() {
  loading.dashboard = true;
  try {
    dashboard.value = await studioApi.qualityMetrics.queryDashboard({ ...baseQuery(), topN: 10 });
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
    });
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
    issues.value = await studioApi.qualityMetrics.queryIssues({
      ...baseQuery(),
      severity: filters.severity,
      status: filters.issueStatus,
      assigneeUserId: filters.assigneeUserId,
    });
    resetIssuePagination();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量问题失败");
  } finally {
    loading.issues = false;
  }
}

async function reloadAll() {
  await Promise.all([loadDashboard(), loadAssets(), loadIssues()]);
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
  timePreset.value = "7d";
  timeRange.value = presetRange("7d");
  await reloadAll();
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
    issueDetail.value = await studioApi.qualityMetrics.getIssue(issue.id);
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
  await Promise.all([loadDashboard(), loadAssets(), loadIssues()]);
  if (assetDrawerVisible.value && assetDetail.value?.assetId) {
    await openAssetDrawer({ ...assetDetail.value });
  }
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

function presetRange(preset: TimePreset): [string, string] {
  const end = new Date();
  const start = new Date(end);
  if (preset === "24h") {
    start.setHours(start.getHours() - 24);
  } else if (preset === "30d") {
    start.setDate(start.getDate() - 30);
  } else {
    start.setDate(start.getDate() - 7);
  }
  return [formatDateTime(start), formatDateTime(end)];
}

function formatDateTime(value: Date) {
  const pad = (input: number) => String(input).padStart(2, "0");
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())} ${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`;
}

function emptyDashboard(): QualityMetricDashboardView {
  return {
    summaryMetrics: {},
    scoreTrend: [],
    issueTrend: [],
    dimensionDistribution: [],
    coverageMatrix: [],
    riskyAssets: [],
    noisyTargets: [],
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

function fieldIssues(group: Record<string, unknown>) {
  const items = group.issues;
  return Array.isArray(items) ? items as QualityIssueView[] : [];
}

function assetTitle(asset?: Pick<QualityAssetRiskView, "modelName" | "modelPhysicalLocator" | "assetId"> | null) {
  return asset?.modelName || asset?.modelPhysicalLocator || asset?.assetId || "-";
}

function dimensionLabel(value?: string | null) {
  return dimensionOptions.find((item) => item.value === value)?.label ?? value ?? "-";
}

function granularityLabel(value?: string | null) {
  if (value === "COLUMN") {
    return "字段级";
  }
  if (value === "TABLE") {
    return "表级";
  }
  return value || "-";
}

function severityLabel(value?: string | null) {
  return severityOptions.find((item) => item.value === value)?.label ?? value ?? "-";
}

function issueStatusLabel(value?: string | null) {
  return issueStatusOptions.find((item) => item.value === value)?.label ?? value ?? "-";
}

function issueTypeLabel(value?: string | null) {
  if (value === "EXECUTION_FAILURE") {
    return "执行失败";
  }
  if (value === "ALERT") {
    return "告警命中";
  }
  return value || "-";
}

function severityTone(value?: string | null): PillTone {
  if (value === "CRITICAL") {
    return "danger";
  }
  if (value === "HIGH") {
    return "warning";
  }
  if (value === "MEDIUM") {
    return "primary";
  }
  return "neutral";
}

function issueStatusTone(value?: string | null): PillTone {
  if (value === "RESOLVED") {
    return "success";
  }
  if (value === "FALSE_POSITIVE") {
    return "neutral";
  }
  if (value === "MITIGATED" || value === "ACKNOWLEDGED") {
    return "primary";
  }
  if (value === "INVESTIGATING") {
    return "warning";
  }
  return "danger";
}

function runStatusTone(value?: string | null): PillTone {
  const status = String(value || "").toUpperCase();
  if (status === "SUCCESS" || status === "ONLINE") {
    return "success";
  }
  if (status.includes("FAIL")) {
    return "danger";
  }
  if (status.includes("RUN")) {
    return "primary";
  }
  return "neutral";
}

function scorePercent(value?: number) {
  return Math.max(0, Math.min(100, toNumber(value)));
}

function recordText(row: Record<string, unknown>, key: string) {
  const value = row[key];
  return value == null ? "" : String(value);
}

function recordNumber(row: Record<string, unknown>, key: string) {
  return toNumber(row[key]);
}

function recordId(row: Record<string, unknown>, key: string): EntityId | null {
  const value = row[key];
  return typeof value === "string" || typeof value === "number" ? value : null;
}

function toNumber(value: unknown) {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function formatNumber(value: unknown) {
  return toNumber(value).toLocaleString();
}

function formatJson(value: unknown) {
  if (value == null) {
    return "{}";
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function unique(items: string[]) {
  return [...new Set(items.filter(Boolean))];
}

onMounted(async () => {
  await loadOptions();
  await reloadAll();
});

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], async () => {
  if (authStore.isAuthenticated) {
    await loadOptions();
    await reloadAll();
  }
});
</script>

<style scoped>
.quality-metrics-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.quality-section-actions,
.quality-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

.quality-section-actions {
  justify-content: flex-start;
  margin: 0 0 12px;
}

.quality-tabs {
  min-width: 0;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 14px;
}

.quality-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  color: var(--studio-text-soft);
}

.stack-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.danger {
  color: var(--studio-danger);
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.tag-row__label {
  width: 72px;
  color: var(--studio-text-soft);
}

.quality-tag-block + .quality-tag-block {
  margin-top: 10px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.drawer-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.drawer-metric-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
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

  .drawer-metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .issue-summary-grid,
  .drawer-metric-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .quality-form-actions {
    justify-content: flex-start;
    flex-wrap: wrap;
  }
}
</style>
