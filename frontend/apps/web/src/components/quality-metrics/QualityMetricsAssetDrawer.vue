<template>
  <el-drawer v-model="visibleModel" :title="assetDetailTitle" size="62%">
    <template v-if="assetDetail">
      <div class="drawer-section">
        <div class="metric-grid drawer-metric-grid">
          <MetricCard label="执行健康分" :value="assetActions.formatNumber(assetDetail.executionHealthScore)" tone="success" />
          <MetricCard label="治理风险指数" :value="assetActions.formatNumber(assetDetail.governanceRiskScore)" tone="warning" />
          <MetricCard label="活跃问题" :value="assetActions.formatNumber(assetDetail.activeIssueCount)" tone="primary" />
          <MetricCard label="覆盖率" :value="`${assetActions.formatNumber(assetDetail.coverageRate)}%`" tone="accent" />
        </div>

        <SectionCard title="资产趋势" description="过去窗口内该资产的健康分与风险指数变化。">
          <EChartPanel v-if="assetDetail.scoreTrend?.length" :option="assetScoreTrendOption" height="260px" />
          <div v-else class="soft-panel quality-empty">暂无资产趋势</div>
        </SectionCard>

        <SectionCard title="维度覆盖与风险" description="覆盖维度代表已绑定任务，风险维度来自当前活跃问题。">
          <div class="tag-row quality-tag-block">
            <span class="tag-row__label">覆盖维度</span>
            <el-tag v-for="item in assetDetail.coverageDimensions" :key="item" size="small">{{ assetActions.dimensionLabel(item) }}</el-tag>
            <span v-if="!assetDetail.coverageDimensions?.length" class="cell-subtle">未覆盖</span>
          </div>
          <div class="tag-row quality-tag-block">
            <span class="tag-row__label">风险维度</span>
            <el-tag v-for="item in assetDetail.riskDimensions" :key="item" type="warning" size="small">{{ assetActions.dimensionLabel(item) }}</el-tag>
            <span v-if="!assetDetail.riskDimensions?.length" class="cell-subtle">暂无风险</span>
          </div>
        </SectionCard>

        <SectionCard title="当前活跃问题" description="点击问题进入问题中心抽屉处置。">
          <el-table :data="assetDetail.activeIssues" border size="small">
            <el-table-column label="问题" min-width="220">
              <template #default="{ row }">
                <el-button link type="primary" @click="assetActions.openIssueDrawer(row)">{{ row.issueCode || row.title || "-" }}</el-button>
              </template>
            </el-table-column>
            <el-table-column label="级别" width="100" align="center">
              <template #default="{ row }"><StatusPill :label="assetActions.severityLabel(row.severity)" :tone="assetActions.severityTone(row.severity)" /></template>
            </el-table-column>
            <el-table-column label="状态" width="120" align="center">
              <template #default="{ row }"><StatusPill :label="assetActions.issueStatusLabel(row.status)" :tone="assetActions.issueStatusTone(row.status)" /></template>
            </el-table-column>
            <el-table-column label="最近出现" min-width="160" prop="lastSeenAt" />
          </el-table>
        </SectionCard>

        <SectionCard title="已绑定质量任务" description="仅提供跳转，不在资产抽屉中直接修改任务。">
          <el-table :data="assetDetail.relatedTasks" border size="small">
            <el-table-column label="任务" min-width="220">
              <template #default="{ row }">
                <el-button link type="primary" @click="assetActions.openTask(assetActions.recordId(row, 'taskId'))">{{ assetActions.recordText(row, "taskName") || "-" }}</el-button>
              </template>
            </el-table-column>
            <el-table-column label="规则" min-width="180">
              <template #default="{ row }">
                <el-button link type="primary" @click="assetActions.openRule(assetActions.recordId(row, 'ruleId'))">{{ assetActions.recordText(row, "ruleName") || "-" }}</el-button>
              </template>
            </el-table-column>
            <el-table-column label="维度/粒度" min-width="150">
              <template #default="{ row }">
                {{ assetActions.dimensionLabel(assetActions.recordText(row, "ruleDimension")) }} / {{ assetActions.granularityLabel(assetActions.recordText(row, "granularity")) }}
              </template>
            </el-table-column>
            <el-table-column label="最近运行" min-width="170">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="!assetActions.recordId(row, 'latestRunRecordId')" @click="assetActions.openRunLog(assetActions.recordId(row, 'latestRunRecordId'))">
                  {{ assetActions.recordText(row, "latestRunStatus") || "无运行" }}
                </el-button>
                <span class="cell-subtle">{{ assetActions.recordText(row, "latestRunAt") || "-" }}</span>
              </template>
            </el-table-column>
          </el-table>
        </SectionCard>

        <SectionCard title="最近失败证据" description="展示最近失败或告警运行，保留日志入口。">
          <el-table :data="assetDetail.latestFailures" border size="small">
            <el-table-column label="任务/规则" min-width="220">
              <template #default="{ row }">
                <div class="stack-cell">
                  <span>{{ assetActions.recordText(row, "taskName") || "-" }}</span>
                  <span class="cell-subtle">{{ assetActions.recordText(row, "ruleName") || "-" }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="摘要" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">
                <MessagePreviewText :text="assetActions.recordText(row, 'message')" />
              </template>
            </el-table-column>
            <el-table-column label="结束时间" min-width="160">
              <template #default="{ row }">{{ assetActions.recordText(row, "endedAt") || "-" }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="!assetActions.recordId(row, 'runRecordId')" @click="assetActions.openRunLog(assetActions.recordId(row, 'runRecordId'))">日志</el-button>
              </template>
            </el-table-column>
          </el-table>
        </SectionCard>

        <SectionCard title="字段级问题分组" description="字段级问题依附在资产下展示。">
          <el-collapse v-if="assetDetail.fieldIssueGroups?.length">
            <el-collapse-item
              v-for="group in assetDetail.fieldIssueGroups"
              :key="assetActions.recordText(group, 'columnName')"
              :title="`${assetActions.recordText(group, 'columnName') || '-'} · ${assetActions.recordNumber(group, 'issueCount')} 个问题`"
            >
              <el-table :data="assetActions.fieldIssues(group)" border size="small">
                <el-table-column label="问题" min-width="220">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="assetActions.openIssueDrawer(row)">{{ row.issueCode || row.title || "-" }}</el-button>
                  </template>
                </el-table-column>
                <el-table-column label="级别" width="100" align="center">
                  <template #default="{ row }"><StatusPill :label="assetActions.severityLabel(row.severity)" :tone="assetActions.severityTone(row.severity)" /></template>
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
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { EChartsOption } from "echarts";
import type {
  EntityId,
  QualityAssetDetailView,
  QualityIssueSeverity,
  QualityIssueStatus,
  QualityIssueView,
} from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill } from "@studio/ui";
import EChartPanel from "@/components/EChartPanel.vue";
import MessagePreviewText from "@/components/MessagePreviewText.vue";

type PillTone = "primary" | "success" | "warning" | "danger" | "neutral";

interface AssetDrawerActions {
  formatNumber: (value: unknown) => string;
  dimensionLabel: (value?: string | null) => string;
  openIssueDrawer: (issue: QualityIssueView) => void | Promise<void>;
  severityLabel: (severity?: QualityIssueSeverity | null) => string;
  severityTone: (severity?: QualityIssueSeverity | null) => PillTone;
  issueStatusLabel: (status?: QualityIssueStatus | null) => string;
  issueStatusTone: (status?: QualityIssueStatus | null) => PillTone;
  recordId: (row: Record<string, unknown>, key: string) => EntityId | null;
  recordText: (row: Record<string, unknown>, key: string) => string;
  recordNumber: (row: Record<string, unknown>, key: string) => number;
  granularityLabel: (value?: string | null) => string;
  openTask: (id?: EntityId | null) => void;
  openRule: (id?: EntityId | null) => void;
  openRunLog: (runRecordId?: EntityId | null) => void;
  fieldIssues: (group: Record<string, unknown>) => QualityIssueView[];
}

const props = defineProps<{
  visible: boolean;
  assetDetailTitle: string;
  assetDetail: QualityAssetDetailView | null;
  assetScoreTrendOption: EChartsOption;
  assetActions: AssetDrawerActions;
}>();

const emit = defineEmits<{
  "update:visible": [value: boolean];
}>();

const visibleModel = computed({
  get: () => props.visible,
  set: (value: boolean) => emit("update:visible", value),
});
</script>

<style scoped>
.drawer-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 14px;
}

.drawer-metric-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
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

@media (max-width: 1180px) {
  .drawer-metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .drawer-metric-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
