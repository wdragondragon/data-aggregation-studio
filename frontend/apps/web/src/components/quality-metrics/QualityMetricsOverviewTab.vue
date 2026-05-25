<template>
  <div class="score-overview-grid">
    <article class="score-overview-card score-overview-card--health">
      <div class="score-overview-card__metric">
        <span>执行健康分</span>
        <strong>{{ overviewActions.summaryMetric("executionHealthScore") }}</strong>
        <em>0-100，越高越好</em>
      </div>
      <div class="score-overview-card__formula">
        <div class="score-overview-card__formula-head">
          <strong>健康分算法</strong>
          <span>固定权重</span>
        </div>
        <div class="score-formula-equation">
          <span>45% 任务通过率</span>
          <i>+</i>
          <span>30% 告警未触发率</span>
          <i>+</i>
          <span>25% 稳定性分</span>
        </div>
        <p>用于综合展示质量任务执行、告警命中和稳定性表现。</p>
      </div>
    </article>

    <article class="score-overview-card score-overview-card--risk">
      <div class="score-overview-card__metric">
        <span>治理风险指数</span>
        <strong>{{ overviewActions.summaryMetric("governanceRiskScore") }}</strong>
        <em>0-100，越高风险越大</em>
      </div>
      <div class="score-overview-card__formula">
        <div class="score-overview-card__formula-head">
          <strong>风险指数算法</strong>
          <span>固定权重</span>
        </div>
        <div class="score-formula-equation">
          <span>50% 未解决问题加权分</span>
          <i>+</i>
          <span>25% SLA 超时占比</span>
          <i>+</i>
          <span>15% 平均存续时长</span>
          <i>+</i>
          <span>10% Reopen 比例</span>
        </div>
        <p>用于展示未解决问题、SLA 超时和重复出现带来的治理风险。</p>
      </div>
    </article>
  </div>

  <div class="metric-grid metric-grid--secondary">
    <MetricCard label="活跃问题数" :value="overviewActions.summaryMetric('activeIssueCount')" tone="primary" />
    <MetricCard label="SLA 超时问题数" :value="overviewActions.summaryMetric('overdueIssueCount')" tone="warning" />
    <MetricCard label="受影响资产数" :value="overviewActions.summaryMetric('affectedAssetCount')" tone="accent" />
    <MetricCard label="已纳管资产数" :value="overviewActions.summaryMetric('managedAssetCount')" tone="success" />
  </div>

  <div class="quality-chart-grid">
    <SectionCard title="双分趋势图" description="执行健康分越高越好，治理风险指数越低越好。">
      <EChartPanel v-if="hasScoreTrend" :option="scoreTrendOption" height="320px" />
      <div v-else class="soft-panel quality-empty">暂无趋势数据</div>
    </SectionCard>
    <SectionCard title="问题趋势图" description="按严重级别观察新增与解决情况。">
      <EChartPanel v-if="hasIssueTrend" :option="issueTrendOption" height="320px" />
      <div v-else class="soft-panel quality-empty">暂无问题趋势</div>
    </SectionCard>
    <SectionCard title="规则维度分布" description="统计各质量维度在当前窗口内的失败占比。">
      <EChartPanel v-if="hasDimensionDistribution" :option="dimensionDistributionOption" height="320px" />
      <div v-else class="soft-panel quality-empty">暂无维度分布</div>
    </SectionCard>
    <SectionCard title="覆盖矩阵" description="数据源类型 × 规则维度，颜色越深代表覆盖资产越多。">
      <EChartPanel v-if="hasCoverageMatrix" :option="coverageMatrixOption" height="320px" />
      <div v-else class="soft-panel quality-empty">暂无覆盖矩阵</div>
    </SectionCard>
  </div>

  <div class="quality-table-grid">
    <SectionCard title="高风险资产 TopN" description="按治理风险指数排序，支持下钻资产详情。">
      <StudioTableShell min-width="760px">
        <el-table :data="dashboard.riskyAssets" border size="small">
          <el-table-column label="资产" min-width="220">
            <template #default="{ row }">
              <div class="table-entity-cell">
                <el-button link type="primary" class="table-entity-cell__title" @click="overviewActions.openAssetDrawer(row)">
                  {{ overviewActions.assetTitle(row) }}
                </el-button>
                <span class="table-entity-cell__meta">{{ row.datasourceName || "-" }} · {{ row.datasourceTypeCode || "-" }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="健康分" width="90" align="right" header-align="right">
            <template #default="{ row }">{{ overviewActions.formatNumber(row.executionHealthScore) }}</template>
          </el-table-column>
          <el-table-column label="风险指数" width="100" align="right" header-align="right">
            <template #default="{ row }">{{ overviewActions.formatNumber(row.governanceRiskScore) }}</template>
          </el-table-column>
          <el-table-column label="活跃问题" width="96" align="right" header-align="right">
            <template #default="{ row }">{{ overviewActions.formatNumber(row.activeIssueCount) }}</template>
          </el-table-column>
          <el-table-column label="最后执行" min-width="170">
            <template #default="{ row }">{{ row.latestRunAt || "-" }}</template>
          </el-table-column>
        </el-table>
      </StudioTableShell>
    </SectionCard>

    <SectionCard title="高噪声任务/规则 TopN" description="按失败次数和 reopen 次数排序，辅助识别噪声来源。">
      <StudioTableShell min-width="620px">
        <el-table :data="dashboard.noisyTargets" border size="small">
          <el-table-column label="对象" min-width="220">
            <template #default="{ row }">
              <div class="table-entity-cell">
                <el-button link type="primary" class="table-entity-cell__title" @click="overviewActions.openNoisyTarget(row)">
                  {{ overviewActions.recordText(row, "name") || "-" }}
                </el-button>
                <span class="table-entity-cell__meta">
                  {{ overviewActions.recordText(row, "targetType") === "RULE" ? "质量规则" : "质量任务" }}
                  · {{ overviewActions.dimensionLabel(overviewActions.recordText(row, "ruleDimension")) }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="失败次数" width="100" align="right" header-align="right">
            <template #default="{ row }">{{ overviewActions.recordNumber(row, "failureCount") }}</template>
          </el-table-column>
          <el-table-column label="Reopen" width="96" align="right" header-align="right">
            <template #default="{ row }">{{ overviewActions.recordNumber(row, "reopenCount") }}</template>
          </el-table-column>
          <el-table-column label="最近出现" min-width="170">
            <template #default="{ row }">{{ overviewActions.recordText(row, "lastSeenAt") || "-" }}</template>
          </el-table-column>
        </el-table>
      </StudioTableShell>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import type { EChartsOption } from "echarts";
import type {
  QualityAssetRiskView,
  QualityMetricDashboardView,
} from "@studio/api-sdk";
import { MetricCard, SectionCard, StudioTableShell } from "@studio/ui";
import EChartPanel from "@/components/EChartPanel.vue";

interface OverviewActions {
  summaryMetric: (key: string) => string;
  openAssetDrawer: (asset: QualityAssetRiskView) => void | Promise<void>;
  assetTitle: (asset?: Pick<QualityAssetRiskView, "modelName" | "modelPhysicalLocator" | "assetId"> | null) => string;
  formatNumber: (value: unknown) => string;
  recordText: (row: Record<string, unknown>, key: string) => string;
  recordNumber: (row: Record<string, unknown>, key: string) => number;
  dimensionLabel: (value?: string | null) => string;
  openNoisyTarget: (row: Record<string, unknown>) => void;
}

defineProps<{
  dashboard: QualityMetricDashboardView;
  hasScoreTrend: boolean;
  hasIssueTrend: boolean;
  hasDimensionDistribution: boolean;
  hasCoverageMatrix: boolean;
  scoreTrendOption: EChartsOption;
  issueTrendOption: EChartsOption;
  dimensionDistributionOption: EChartsOption;
  coverageMatrixOption: EChartsOption;
  overviewActions: OverviewActions;
}>();
</script>

<style scoped>
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 14px;
}

.metric-grid--secondary {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 14px;
}

.score-overview-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.score-overview-card {
  position: relative;
  overflow: hidden;
  display: grid;
  grid-template-columns: minmax(150px, 0.7fr) minmax(0, 1.3fr);
  gap: 16px;
  min-height: 176px;
  padding: 18px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  border-radius: 22px;
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.08);
}

.score-overview-card::after {
  position: absolute;
  top: -42px;
  right: -36px;
  width: 108px;
  height: 108px;
  border-radius: 50%;
  content: "";
  opacity: 0.42;
}

.score-overview-card--health {
  background:
    radial-gradient(circle at 12% 12%, rgba(22, 163, 74, 0.14), transparent 34%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.96), rgba(236, 253, 245, 0.76));
}

.score-overview-card--health::after {
  background: rgba(22, 163, 74, 0.22);
}

.score-overview-card--risk {
  background:
    radial-gradient(circle at 12% 12%, rgba(245, 158, 11, 0.18), transparent 34%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.96), rgba(255, 247, 237, 0.78));
}

.score-overview-card--risk::after {
  background: rgba(245, 158, 11, 0.26);
}

.score-overview-card__metric {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  padding: 14px;
  border: 1px solid rgba(16, 78, 139, 0.1);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.58);
}

.score-overview-card__metric span {
  color: var(--studio-text-soft);
  font-size: 13px;
}

.score-overview-card__metric strong {
  margin-top: 8px;
  color: var(--studio-text);
  font-size: clamp(42px, 6vw, 64px);
  line-height: 0.95;
}

.score-overview-card__metric em {
  margin-top: 10px;
  color: var(--studio-text-soft);
  font-size: 12px;
  font-style: normal;
}

.score-overview-card__formula {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.score-overview-card__formula-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.score-overview-card__formula-head strong {
  color: var(--studio-text);
  font-size: 15px;
}

.score-overview-card__formula-head span {
  padding: 3px 9px;
  border-radius: 999px;
  background: rgba(16, 78, 139, 0.08);
  color: var(--studio-text-soft);
  font-size: 12px;
}

.score-formula-equation {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
}

.score-formula-equation span {
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(16, 78, 139, 0.08);
  color: var(--studio-text);
  font-size: 12px;
  font-weight: 650;
}

.score-formula-equation i {
  color: var(--studio-text-soft);
  font-style: normal;
  font-weight: 700;
}

.score-overview-card__formula p {
  margin: 12px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.7;
}

.quality-chart-grid,
.quality-table-grid {
  display: grid;
  gap: 18px;
  margin-top: 18px;
}

.quality-chart-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.quality-table-grid {
  grid-template-columns: minmax(0, 1fr);
}

.quality-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  color: var(--studio-text-soft);
}

.table-entity-cell {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.table-entity-cell__title {
  min-width: 0;
  justify-content: flex-start;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-entity-cell__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

@media (max-width: 1180px) {
  .quality-table-grid,
  .score-overview-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .score-overview-card {
    grid-template-columns: minmax(0, 1fr);
  }

  .metric-grid--secondary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .metric-grid--secondary {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
