<template>
  <div class="studio-page run-metrics-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.runMetrics.heading") }}</h3>
        <p>{{ t("web.runMetrics.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain :loading="optionsLoading || dashboardLoading" @click="reloadAll">{{ t("common.refresh") }}</el-button>
        <el-button type="primary" :loading="dashboardLoading" @click="loadDashboard">{{ t("common.search") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.runMetrics.filterTitle')" :description="t('web.runMetrics.filterDescription')">
      <div class="studio-form-grid">
        <el-form-item :label="t('web.runMetrics.datasourceLabel')">
          <el-select v-model="filters.datasourceId" clearable filterable :placeholder="t('web.runMetrics.datasourcePlaceholder')">
            <el-option
              v-for="item in options.datasources"
              :key="String(item.id)"
              :label="item.label || item.name || String(item.id)"
              :value="item.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('web.runMetrics.sourceModelLabel')">
          <el-select v-model="filters.sourceModelId" clearable filterable :placeholder="t('web.runMetrics.sourceModelPlaceholder')">
            <el-option
              v-for="item in options.sourceModels"
              :key="String(item.id)"
              :label="item.label || item.name || String(item.id)"
              :value="item.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('web.runMetrics.targetModelLabel')">
          <el-select v-model="filters.targetModelId" clearable filterable :placeholder="t('web.runMetrics.targetModelPlaceholder')">
            <el-option
              v-for="item in options.targetModels"
              :key="String(item.id)"
              :label="item.label || item.name || String(item.id)"
              :value="item.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('web.runMetrics.granularityLabel')">
          <el-select v-model="filters.granularity">
            <el-option :label="t('web.runMetrics.granularityDay')" value="DAY" />
            <el-option :label="t('web.runMetrics.granularityWeek')" value="WEEK" />
            <el-option :label="t('web.runMetrics.granularityMonth')" value="MONTH" />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('web.runMetrics.topNLabel')">
          <el-input-number v-model="filters.topN" :min="1" :max="100" controls-position="right" class="metrics-topn-input" />
        </el-form-item>

        <el-form-item :label="t('web.runMetrics.timeRangeLabel')" class="run-metrics-time-range">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            unlink-panels
            start-placeholder="Start"
            end-placeholder="End"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
        </el-form-item>
      </div>

      <div class="run-metrics-filter-actions">
        <el-button type="primary" :loading="dashboardLoading" @click="loadDashboard">{{ t("common.search") }}</el-button>
        <el-button plain @click="resetFilters">{{ t("common.reset") }}</el-button>
      </div>

      <div v-if="legacyNoticeText" class="soft-panel run-metrics-legacy-note">
        {{ legacyNoticeText }}
      </div>
    </SectionCard>

    <SectionCard :title="t('web.runMetrics.trendTitle')" :description="t('web.runMetrics.trendDescription')">
      <EChartPanel v-if="trendHasData" :option="trendOption" height="420px" />
      <div v-else class="soft-panel run-metrics-empty">{{ t("web.runMetrics.emptyTrend") }}</div>
    </SectionCard>

    <div class="run-metrics-grid">
      <SectionCard :title="t('web.runMetrics.sourceDatasourceTopNTitle')" :description="t('web.runMetrics.sourceDatasourceTopNDescription')">
        <el-table v-if="sourceDatasourceRows.length" :data="sourceDatasourceRows" size="small" border>
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column :label="t('web.runMetrics.datasourceLabel')" min-width="180">
            <template #default="{ row }">{{ row.label }}</template>
          </el-table-column>
          <el-table-column :label="t('web.runMetrics.metricSuccess')" min-width="120" align="right" header-align="right">
            <template #default="{ row }">{{ formatMetricNumber(row.count) }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="soft-panel run-metrics-empty run-metrics-empty--compact">{{ t("web.runMetrics.emptyTopN") }}</div>
      </SectionCard>

      <SectionCard :title="t('web.runMetrics.targetDatasourceTopNTitle')" :description="t('web.runMetrics.targetDatasourceTopNDescription')">
        <el-table v-if="targetDatasourceRows.length" :data="targetDatasourceRows" size="small" border>
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column :label="t('web.runMetrics.datasourceLabel')" min-width="180">
            <template #default="{ row }">{{ row.label }}</template>
          </el-table-column>
          <el-table-column :label="t('web.runMetrics.metricSuccess')" min-width="120" align="right" header-align="right">
            <template #default="{ row }">{{ formatMetricNumber(row.count) }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="soft-panel run-metrics-empty run-metrics-empty--compact">{{ t("web.runMetrics.emptyTopN") }}</div>
      </SectionCard>

      <SectionCard :title="t('web.runMetrics.sourceModelTopNTitle')" :description="t('web.runMetrics.sourceModelTopNDescription')">
        <el-table v-if="sourceModelRows.length" :data="sourceModelRows" size="small" border>
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column :label="t('web.runMetrics.sourceModelLabel')" min-width="180">
            <template #default="{ row }">{{ row.label }}</template>
          </el-table-column>
          <el-table-column :label="t('web.runMetrics.metricSuccess')" min-width="120" align="right" header-align="right">
            <template #default="{ row }">{{ formatMetricNumber(row.count) }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="soft-panel run-metrics-empty run-metrics-empty--compact">{{ t("web.runMetrics.emptyTopN") }}</div>
      </SectionCard>

      <SectionCard :title="t('web.runMetrics.targetModelTopNTitle')" :description="t('web.runMetrics.targetModelTopNDescription')">
        <el-table v-if="targetModelRows.length" :data="targetModelRows" size="small" border>
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column :label="t('web.runMetrics.targetModelLabel')" min-width="180">
            <template #default="{ row }">{{ row.label }}</template>
          </el-table-column>
          <el-table-column :label="t('web.runMetrics.metricSuccess')" min-width="120" align="right" header-align="right">
            <template #default="{ row }">{{ formatMetricNumber(row.count) }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="soft-panel run-metrics-empty run-metrics-empty--compact">{{ t("web.runMetrics.emptyTopN") }}</div>
      </SectionCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import type { EChartsOption } from "echarts";
import { useI18n } from "vue-i18n";
import type { RunMetricDashboardResponse, RunMetricTopNItem, StatisticsChartType } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import EChartPanel from "@/components/EChartPanel.vue";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { formatMetricNumber, metricLabel, type RunMetricLabelKey, toMetricNumber } from "@/utils/runMetrics";

interface FilterState {
  datasourceId?: string | number;
  sourceModelId?: string | number;
  targetModelId?: string | number;
  granularity: "DAY" | "WEEK" | "MONTH";
  topN: number;
}

const { t } = useI18n();
const authStore = useAuthStore();

const optionsLoading = ref(false);
const dashboardLoading = ref(false);
const options = ref({
  datasources: [] as Array<{ id?: string | number; name?: string; label?: string; typeCode?: string }>,
  sourceModels: [] as Array<{ id?: string | number; name?: string; label?: string; typeCode?: string }>,
  targetModels: [] as Array<{ id?: string | number; name?: string; label?: string; typeCode?: string }>,
});
const dashboard = ref<RunMetricDashboardResponse>({
  trend: {
    xAxis: [],
    series: [],
  },
  sourceDatasourceTopN: [],
  targetDatasourceTopN: [],
  sourceModelTopN: [],
  targetModelTopN: [],
  legacyRunCount: 0,
});
const filters = reactive<FilterState>({
  datasourceId: undefined,
  sourceModelId: undefined,
  targetModelId: undefined,
  granularity: "DAY",
  topN: 10,
});
const timeRange = ref<[string, string] | []>(defaultTimeRange());

const trendMetricKeys: RunMetricLabelKey[] = [
  "collectedRecords",
  "successRecords",
  "failedRecords",
  "transformerTotalRecords",
  "transformerSuccessRecords",
  "transformerFailedRecords",
  "transformerFilterRecords",
];

const legacyNoticeText = computed(() => {
  const count = toMetricNumber(dashboard.value.legacyRunCount);
  if (count <= 0) {
    return "";
  }
  return t("web.runMetrics.legacyNotice", { count: formatMetricNumber(count) });
});

const trendHasData = computed(() => {
  const trend = dashboard.value.trend;
  return (trend.series ?? []).some((series) => (series.data ?? []).some((item) => toMetricNumber(item) > 0));
});

const trendOption = computed<EChartsOption>(() => {
  const trend = dashboard.value.trend;
  return {
    tooltip: { trigger: "axis" },
    legend: {
      type: "scroll",
      top: 0,
      data: trendMetricKeys.map((key) => metricLabel(t, key)),
    },
    grid: { left: 18, right: 18, top: 58, bottom: 18, containLabel: true },
    xAxis: {
      type: "category",
      data: trend.xAxis ?? [],
      axisLabel: { rotate: (trend.xAxis ?? []).length > 8 ? 20 : 0 },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
    },
    series: (trend.series ?? []).map((series) => ({
      name: metricLabel(t, normalizeMetricKey(series.key)),
      type: "line",
      smooth: true,
      data: (series.data ?? []).map((item) => toMetricNumber(item)),
    })),
  };
});

const sourceDatasourceRows = computed(() => normalizeTopNRows(dashboard.value.sourceDatasourceTopN));
const targetDatasourceRows = computed(() => normalizeTopNRows(dashboard.value.targetDatasourceTopN));
const sourceModelRows = computed(() => normalizeTopNRows(dashboard.value.sourceModelTopN));
const targetModelRows = computed(() => normalizeTopNRows(dashboard.value.targetModelTopN));

async function loadOptions() {
  optionsLoading.value = true;
  try {
    options.value = await studioApi.runMetrics.options();
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, t("web.runMetrics.loadFailed")));
  } finally {
    optionsLoading.value = false;
  }
}

async function loadDashboard() {
  dashboardLoading.value = true;
  try {
    dashboard.value = await studioApi.runMetrics.query({
      datasourceId: filters.datasourceId,
      sourceModelId: filters.sourceModelId,
      targetModelId: filters.targetModelId,
      startTime: Array.isArray(timeRange.value) && timeRange.value.length === 2 ? timeRange.value[0] : undefined,
      endTime: Array.isArray(timeRange.value) && timeRange.value.length === 2 ? timeRange.value[1] : undefined,
      granularity: filters.granularity,
      topN: filters.topN,
    });
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, t("web.runMetrics.loadFailed")));
  } finally {
    dashboardLoading.value = false;
  }
}

async function reloadAll() {
  await loadOptions();
  await loadDashboard();
}

async function resetFilters() {
  filters.datasourceId = undefined;
  filters.sourceModelId = undefined;
  filters.targetModelId = undefined;
  filters.granularity = "DAY";
  filters.topN = 10;
  timeRange.value = defaultTimeRange();
  await loadDashboard();
}

function normalizeTopNRows(items: RunMetricTopNItem[] | undefined) {
  return (items ?? []).map((item) => ({
    id: item.id,
    label: item.label || item.name || String(item.id ?? ""),
    count: item.count,
  }));
}

function normalizeMetricKey(key?: string): RunMetricLabelKey {
  if (trendMetricKeys.includes(key as RunMetricLabelKey)) {
    return key as RunMetricLabelKey;
  }
  return "collectedRecords";
}

function defaultTimeRange(): [string, string] {
  const end = new Date();
  const start = new Date(end.getTime() - 29 * 24 * 60 * 60 * 1000);
  return [formatDateTime(start), formatDateTime(end)];
}

function formatDateTime(value: Date) {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, "0");
  const day = String(value.getDate()).padStart(2, "0");
  const hours = String(value.getHours()).padStart(2, "0");
  const minutes = String(value.getMinutes()).padStart(2, "0");
  const seconds = String(value.getSeconds()).padStart(2, "0");
  return `${year}-${month}-${day}T${hours}:${minutes}:${seconds}`;
}

function resolveErrorMessage(error: unknown, fallback: string) {
  if (typeof error === "object" && error && "response" in error) {
    const response = (error as { response?: { data?: { message?: string } } }).response;
    if (response?.data?.message) {
      return response.data.message;
    }
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}

onMounted(() => {
  void reloadAll();
});

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], () => {
  if (authStore.isAuthenticated) {
    void reloadAll();
  }
});
</script>

<style scoped>
.run-metrics-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.run-metrics-filter-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.metrics-topn-input {
  width: 132px;
}

.run-metrics-time-range {
  grid-column: 1 / -1;
}

.run-metrics-time-range :deep(.el-date-editor) {
  width: 100%;
}

.run-metrics-legacy-note {
  margin-top: 14px;
  color: var(--el-text-color-secondary);
}

.run-metrics-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.run-metrics-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 140px;
  color: var(--el-text-color-secondary);
}

.run-metrics-empty--compact {
  min-height: 96px;
}

@media (max-width: 960px) {
  .run-metrics-grid {
    grid-template-columns: 1fr;
  }

  .run-metrics-filter-actions {
    justify-content: flex-start;
    flex-wrap: wrap;
  }
}
</style>
