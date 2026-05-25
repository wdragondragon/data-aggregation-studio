<template>
  <SectionCard :title="t('web.statistics.overviewTitle')" :description="t('web.statistics.overviewDescription')" :show-description="true">
    <div v-if="metricCards.length === 0" class="soft-panel statistics-empty">{{ t("web.statistics.chartEmpty") }}</div>
    <div v-else class="statistics-metrics">
      <MetricCard
        v-for="metric in metricCards"
        :key="metric.key"
        :label="metric.label"
        :value="metric.value"
        :tone="metric.tone"
      />
    </div>
  </SectionCard>

  <div class="statistics-chart-grid" :class="chartGridClass">
    <SectionCard
      class="statistics-chart-card statistics-chart-card--trend"
      :title="t('web.statistics.chartTrendTitle')"
      :description="t('web.statistics.chartTrendDescription')"
      :show-description="true"
    >
      <template #actions>
        <div class="statistics-card-control">
          <span>{{ t("web.statistics.daysLabel") }}</span>
          <el-select v-model="trendDaysModel" class="statistics-card-control__select">
            <el-option :label="'7'" :value="7" />
            <el-option :label="'14'" :value="14" />
            <el-option :label="'30'" :value="30" />
            <el-option :label="'90'" :value="90" />
          </el-select>
        </div>
      </template>
      <template v-if="chartActions.canRenderChart(chartResults.TREND)">
        <EChartPanel :option="trendOption" :height="trendChartHeight" />
      </template>
      <div v-else class="soft-panel statistics-empty">{{ chartActions.chartMessage(chartResults.TREND) }}</div>
    </SectionCard>

    <SectionCard
      class="statistics-chart-card statistics-chart-card--bar"
      :title="t('web.statistics.chartBarTitle')"
      :description="t('web.statistics.chartBarDescription')"
      :show-description="true"
    >
      <template v-if="chartActions.canRenderChart(chartResults.BAR)">
        <EChartPanel :option="barOption" :height="barChartHeight" />
      </template>
      <div v-else class="soft-panel statistics-empty">{{ chartActions.chartMessage(chartResults.BAR) }}</div>
    </SectionCard>

    <SectionCard
      class="statistics-chart-card statistics-chart-card--pie"
      :title="t('web.statistics.chartPieTitle')"
      :description="t('web.statistics.chartPieDescription')"
      :show-description="true"
    >
      <template v-if="chartActions.canRenderChart(chartResults.PIE)">
        <EChartPanel :option="pieOption" :height="pieChartHeight" />
      </template>
      <div v-else class="soft-panel statistics-empty">{{ chartActions.chartMessage(chartResults.PIE) }}</div>
    </SectionCard>

    <SectionCard
      class="statistics-chart-card statistics-chart-card--topn"
      :title="t('web.statistics.chartTopNTitle')"
      :description="t('web.statistics.chartTopNDescription')"
      :show-description="true"
    >
      <template #actions>
        <div class="statistics-card-control">
          <span>{{ t("web.models.statisticsTopNLabel") }}</span>
          <el-input-number v-model="topNModel" :min="1" :max="100" controls-position="right" class="statistics-card-control__number" />
        </div>
      </template>
      <div v-if="chartResults.TOPN?.disabledReason" class="soft-panel statistics-empty">{{ chartResults.TOPN.disabledReason }}</div>
      <div v-else-if="topNDisplayRows.length === 0" class="soft-panel statistics-empty">{{ t("web.statistics.chartEmpty") }}</div>
      <div v-else class="ranking-board">
        <div class="ranking-board__header">
          <span>{{ t("common.sequence") }}</span>
          <div class="ranking-board__content-head ranking-board__content-head--header">
            <span>{{ targetFieldName || t("web.statistics.targetFieldLabel") }}</span>
            <span>{{ t("web.statistics.rankingCount") }}</span>
            <span>{{ t("web.statistics.rankingRatio") }}</span>
          </div>
        </div>
        <article v-for="row in topNDisplayRows" :key="row.key || row.displayLabel" class="ranking-board__row">
          <div class="ranking-board__rank">#{{ row.rank }}</div>
          <div class="ranking-board__content">
            <div class="ranking-board__content-head">
              <strong class="ranking-board__label">{{ row.displayLabel }}</strong>
              <span class="ranking-board__count">{{ chartActions.formatInteger(row.countValue) }}</span>
              <span class="ranking-board__ratio">{{ chartActions.formatPercent(row.ratioValue) }}</span>
            </div>
            <div class="ranking-board__track">
              <div class="ranking-board__fill" :style="{ width: `${row.barPercent}%` }" />
            </div>
          </div>
        </article>
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { EChartsOption } from "echarts";
import { useI18n } from "vue-i18n";
import type { DataModelStatisticsChartView, StatisticsChartType } from "@studio/api-sdk";
import { MetricCard, SectionCard } from "@studio/ui";
import EChartPanel from "@/components/EChartPanel.vue";

interface MetricCardItem {
  key: string;
  label: string;
  value: string | number;
  tone: "primary" | "accent" | "success" | "warning";
}

interface TopNDisplayRow {
  key?: string;
  displayLabel: string;
  rank?: unknown;
  countValue: unknown;
  ratioValue: unknown;
  barPercent: number;
}

interface ChartActions {
  canRenderChart: (chart?: DataModelStatisticsChartView) => boolean;
  chartMessage: (chart?: DataModelStatisticsChartView) => string;
  formatInteger: (value: unknown) => string;
  formatPercent: (value: unknown) => string;
}

const props = defineProps<{
  metricCards: MetricCardItem[];
  chartGridClass: string;
  chartResults: Record<StatisticsChartType, DataModelStatisticsChartView | undefined>;
  trendOption: EChartsOption;
  barOption: EChartsOption;
  pieOption: EChartsOption;
  trendChartHeight: string;
  barChartHeight: string;
  pieChartHeight: string;
  topNDisplayRows: TopNDisplayRow[];
  targetFieldName: string;
  trendDays: number;
  topN: number;
  chartActions: ChartActions;
}>();

const emit = defineEmits<{
  "update:trendDays": [value: number];
  "update:topN": [value: number];
}>();

const { t } = useI18n();

const trendDaysModel = computed({
  get: () => props.trendDays,
  set: (value: number) => emit("update:trendDays", value),
});

const topNModel = computed({
  get: () => props.topN,
  set: (value: number) => emit("update:topN", value),
});
</script>

<style scoped>
.statistics-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  color: var(--el-text-color-secondary);
}

.statistics-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}

.statistics-chart-grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 20px;
  align-items: stretch;
}

.statistics-chart-card {
  min-width: 0;
}

.statistics-chart-card--trend,
.statistics-chart-card--bar,
.statistics-chart-card--pie {
  grid-column: span 4;
}

.statistics-chart-grid--wide .statistics-chart-card--trend,
.statistics-chart-grid--wide .statistics-chart-card--bar {
  grid-column: span 6;
}

.statistics-chart-grid--wide .statistics-chart-card--pie {
  grid-column: 1 / -1;
}

.statistics-chart-grid--stacked .statistics-chart-card--trend,
.statistics-chart-grid--stacked .statistics-chart-card--bar,
.statistics-chart-grid--stacked .statistics-chart-card--pie {
  grid-column: 1 / -1;
}

.statistics-chart-card--topn {
  grid-column: 1 / -1;
}

.statistics-card-control {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.statistics-card-control__select {
  width: 108px;
}

.statistics-card-control__number {
  width: 132px;
}

.ranking-board {
  display: grid;
  gap: 12px;
}

.ranking-board__header,
.ranking-board__row {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
}

.ranking-board__header {
  padding: 0 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.ranking-board__content-head--header span:last-child,
.ranking-board__content-head--header span:nth-last-child(2) {
  text-align: right;
}

.ranking-board__content-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 108px 88px;
  gap: 12px;
  align-items: center;
}

.ranking-board__row {
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(47, 111, 237, 0.08), rgba(47, 111, 237, 0.02)),
    var(--el-fill-color-blank);
}

.ranking-board__content-head--header {
  gap: 12px;
}

.ranking-board__rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 72px;
  padding: 8px 12px;
  border-radius: 999px;
  color: var(--studio-primary-deep);
  font-weight: 700;
  background: rgba(47, 111, 237, 0.12);
}

.ranking-board__content {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.ranking-board__label {
  min-width: 0;
  font-size: 15px;
  line-height: 1.35;
  word-break: break-word;
}

.ranking-board__count,
.ranking-board__ratio {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  text-align: right;
  white-space: nowrap;
}

.ranking-board__track {
  height: 10px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(47, 111, 237, 0.1);
}

.ranking-board__fill {
  height: 100%;
  min-width: 10px;
  border-radius: inherit;
  background: linear-gradient(90deg, #2f6fed 0%, #38bdf8 100%);
}

@media (max-width: 960px) {
  .statistics-chart-grid {
    grid-template-columns: 1fr;
  }

  .statistics-chart-card--trend,
  .statistics-chart-card--bar,
  .statistics-chart-card--pie,
  .statistics-chart-card--topn {
    grid-column: 1 / -1;
  }

  .ranking-board__header {
    display: none;
  }

  .ranking-board__row,
  .ranking-board__content-head {
    grid-template-columns: minmax(0, 1fr);
  }

  .ranking-board__count,
  .ranking-board__ratio {
    text-align: left;
  }
}
</style>
