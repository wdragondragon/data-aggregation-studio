<template>
  <div class="studio-page data-service-metrics-page">
    <div class="studio-toolbar">
      <div>
        <h3>服务监控</h3>
        <p>仅统计开放 API 的真实外部调用，后台调试不计入访问次数和成功率。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="router.push('/data-services')">服务列表</el-button>
        <el-button type="primary" :loading="isLoading" @click="reloadAll">刷新</el-button>
      </div>
    </div>

    <SectionCard title="监控筛选" description="时间范围、服务、状态、订阅方、成功状态和缓存命中会同步影响所有统计。">
      <div class="metrics-filter-grid">
        <div class="time-filter-shell">
          <el-radio-group v-model="timePreset" size="small" @change="changeTimePreset">
            <el-radio-button value="24h">24 小时</el-radio-button>
            <el-radio-button value="7d">7 天</el-radio-button>
            <el-radio-button value="30d">30 天</el-radio-button>
            <el-radio-button value="custom">自定义</el-radio-button>
          </el-radio-group>
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            unlink-panels
            value-format="YYYY-MM-DD HH:mm:ss"
            range-separator="~"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            @change="timePreset = 'custom'"
          />
        </div>
        <el-select v-model="filters.serviceId" clearable filterable placeholder="全部服务">
          <el-option v-for="item in options.services" :key="String(item.id)" :label="item.label || item.name || String(item.id)" :value="item.id" />
        </el-select>
        <el-select v-model="filters.serviceStatus" clearable placeholder="服务状态">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="ONLINE" />
          <el-option label="已下线" value="OFFLINE" />
        </el-select>
        <el-select v-model="filters.subscriptionId" clearable filterable placeholder="全部订阅方">
          <el-option v-for="item in filteredSubscriptions" :key="String(item.id)" :label="item.label || item.name || String(item.id)" :value="item.id" />
        </el-select>
        <el-select v-model="filters.success" clearable placeholder="调用结果">
          <el-option label="成功" value="true" />
          <el-option label="失败" value="false" />
        </el-select>
        <el-select v-model="filters.cacheHit" clearable placeholder="缓存状态">
          <el-option label="命中" value="true" />
          <el-option label="未命中（已开启）" value="false" />
        </el-select>
        <div class="metrics-filter-actions">
          <el-button type="primary" :loading="isLoading" @click="searchMetrics">查询</el-button>
          <el-button plain @click="resetFilters">重置</el-button>
        </div>
      </div>
    </SectionCard>

    <div class="metric-grid metric-grid--monitor">
      <MetricCard label="总访问次数" :value="formatNumber(summary.accessCount)" tone="primary" />
      <MetricCard label="成功次数" :value="formatNumber(summary.successCount)" tone="success" />
      <MetricCard label="失败次数" :value="formatNumber(summary.failureCount)" tone="warning" />
      <MetricCard label="成功率" :value="formatRate(summary.successRate)" tone="success" />
      <MetricCard label="平均响应" :value="formatMs(summary.avgResponseTimeMs)" tone="accent" />
      <MetricCard label="缓存命中率" :value="formatCacheRate(summary)" tone="accent" />
      <MetricCard label="缓存命中次数" :value="formatNumber(summary.cacheHitCount)" tone="success" />
      <MetricCard label="缓存未命中次数" :value="formatNumber(summary.cacheMissCount)" tone="warning" />
    </div>

    <div class="metrics-chart-grid">
      <SectionCard title="访问趋势" description="按时间查看新增调用量，或切换为当前窗口内累计调用量。">
        <template #actions>
          <el-radio-group v-model="accessTrendMode" size="small">
            <el-radio-button value="incremental">增量</el-radio-button>
            <el-radio-button value="cumulative">总量</el-radio-button>
          </el-radio-group>
        </template>
        <EChartPanel :option="trendOption(selectedAccessTrend, '')" height="260px" />
      </SectionCard>
      <SectionCard title="接口输出数据量" description="按接口返回行数统计输出规模，支持新增输出量和累计输出量切换。">
        <template #actions>
          <el-radio-group v-model="outputTrendMode" size="small">
            <el-radio-button value="incremental">增量</el-radio-button>
            <el-radio-button value="cumulative">总量</el-radio-button>
          </el-radio-group>
        </template>
        <EChartPanel :option="trendOption(selectedOutputRowTrend, '行')" height="260px" />
      </SectionCard>
      <SectionCard title="缓存命中次数" description="按请求日志中的缓存开关快照统计命中次数，支持新增和累计切换。">
        <template #actions>
          <el-radio-group v-model="cacheHitTrendMode" size="small">
            <el-radio-button value="incremental">增量</el-radio-button>
            <el-radio-button value="cumulative">总量</el-radio-button>
          </el-radio-group>
        </template>
        <EChartPanel :option="trendOption(selectedCacheHitTrend, '次')" height="260px" />
      </SectionCard>
      <SectionCard title="响应时间趋势" description="展示平均耗时、最大耗时、P95 和 P99。">
        <EChartPanel :option="trendOption(dashboard.responseTimeTrend, 'ms')" height="260px" />
      </SectionCard>
      <SectionCard title="成功率趋势" description="观察开放 API 可用性变化。">
        <EChartPanel :option="trendOption(dashboard.successRateTrend, '%')" height="260px" />
      </SectionCard>
      <SectionCard title="错误分布" description="按 HTTP 状态或业务错误码聚合失败调用。">
        <EChartPanel :option="errorDistributionOption" height="260px" />
      </SectionCard>
    </div>

    <SectionCard title="API 统计" description="按服务聚合访问次数、成功率、响应时间和缓存命中率。">
      <el-table :data="apiStats" border size="small">
        <el-table-column label="服务" min-width="220">
          <template #default="{ row }">
            <div class="table-entity-cell">
              <span class="table-entity-cell__title">{{ row.serviceName || "-" }}</span>
              <span class="table-entity-cell__meta">{{ row.serviceCode || "-" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="serviceStatusLabel(row.status)" :tone="serviceStatusTone(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="访问" width="110" align="right" header-align="right">
          <template #default="{ row }">{{ formatNumber(row.accessCount) }}</template>
        </el-table-column>
        <el-table-column label="成功/失败" width="130" align="center" header-align="center">
          <template #default="{ row }">{{ formatNumber(row.successCount) }} / {{ formatNumber(row.failureCount) }}</template>
        </el-table-column>
        <el-table-column label="成功率" width="110" align="right" header-align="right">
          <template #default="{ row }">{{ formatRate(row.successRate) }}</template>
        </el-table-column>
        <el-table-column label="最快响应" width="110" align="right" header-align="right">
          <template #default="{ row }">{{ formatMs(row.minResponseTimeMs) }}</template>
        </el-table-column>
        <el-table-column label="平均响应" width="110" align="right" header-align="right">
          <template #default="{ row }">{{ formatMs(row.avgResponseTimeMs) }}</template>
        </el-table-column>
        <el-table-column label="最慢响应" width="110" align="right" header-align="right">
          <template #default="{ row }">{{ formatMs(row.maxResponseTimeMs) }}</template>
        </el-table-column>
        <el-table-column label="P95/P99" width="130" align="center" header-align="center">
          <template #default="{ row }">{{ formatMs(row.p95ResponseTimeMs) }} / {{ formatMs(row.p99ResponseTimeMs) }}</template>
        </el-table-column>
        <el-table-column label="缓存命中率" width="120" align="right" header-align="right">
          <template #default="{ row }">{{ formatCacheRate(row) }}</template>
        </el-table-column>
        <el-table-column label="最近访问" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.lastAccessAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right" align="center" header-align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewService(row.serviceId)">查看服务</el-button>
            <el-button link type="primary" @click="viewLogs(row.serviceId)">访问日志</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="apiStatsPagination.page"
          v-model:page-size="apiStatsPagination.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="apiStatsTotal"
          @current-change="loadApiStats"
          @size-change="handleApiStatsPageSizeChange"
        />
      </div>
    </SectionCard>

    <div class="metrics-table-grid">
      <SectionCard title="Top 慢 API" description="按 P95 和最大响应时间排序。">
        <el-table :data="dashboard.topSlowApis || []" border size="small">
          <el-table-column prop="serviceName" label="服务" min-width="180" />
          <el-table-column label="P95" width="90" align="right" header-align="right">
            <template #default="{ row }">{{ formatMs(row.p95ResponseTimeMs) }}</template>
          </el-table-column>
          <el-table-column label="最大" width="90" align="right" header-align="right">
            <template #default="{ row }">{{ formatMs(row.maxResponseTimeMs) }}</template>
          </el-table-column>
        </el-table>
      </SectionCard>
      <SectionCard title="Top 失败 API" description="按失败次数和失败率排序。">
        <el-table :data="dashboard.topFailedApis || []" border size="small">
          <el-table-column prop="serviceName" label="服务" min-width="180" />
          <el-table-column label="失败" width="90" align="right" header-align="right">
            <template #default="{ row }">{{ formatNumber(row.failureCount) }}</template>
          </el-table-column>
          <el-table-column label="成功率" width="100" align="right" header-align="right">
            <template #default="{ row }">{{ formatRate(row.successRate) }}</template>
          </el-table-column>
        </el-table>
      </SectionCard>
      <SectionCard title="订阅方排行" description="按订阅 Token 调用量聚合。">
        <el-table :data="dashboard.subscriptionRank || []" border size="small">
          <el-table-column prop="subscriptionName" label="订阅方" min-width="170" />
          <el-table-column prop="serviceName" label="服务" min-width="150" />
          <el-table-column label="访问" width="90" align="right" header-align="right">
            <template #default="{ row }">{{ formatNumber(row.accessCount) }}</template>
          </el-table-column>
          <el-table-column label="成功率" width="100" align="right" header-align="right">
            <template #default="{ row }">{{ formatRate(row.successRate) }}</template>
          </el-table-column>
        </el-table>
      </SectionCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import type { EChartsOption } from "echarts";
import type {
  DataServiceApiMetricView,
  DataServiceMetricDashboardView,
  DataServiceMetricOptionsView,
  DataServiceMetricQueryRequest,
  EntityId,
  RunMetricTrendView,
} from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill } from "@studio/ui";
import EChartPanel from "@/components/EChartPanel.vue";
import { studioApi } from "@/api/studio";

type TriState = "" | "true" | "false";

const router = useRouter();
const route = useRoute();

const now = new Date();
const timePreset = ref("7d");
const timeRange = ref<[string, string]>([formatDateTimeValue(addDays(now, -7)), formatDateTimeValue(now)]);
const filters = reactive<{
  serviceId: EntityId | "";
  serviceStatus: string;
  subscriptionId: EntityId | "";
  success: TriState;
  cacheHit: TriState;
}>({
  serviceId: route.query.serviceId ? String(route.query.serviceId) : "",
  serviceStatus: "",
  subscriptionId: "",
  success: "",
  cacheHit: "",
});
const options = reactive<DataServiceMetricOptionsView>({
  services: [],
  subscriptions: [],
});
const dashboard = ref<DataServiceMetricDashboardView>({});
const apiStats = ref<DataServiceApiMetricView[]>([]);
const apiStatsTotal = ref(0);
const isLoading = ref(false);
const apiStatsPagination = reactive({ page: 1, pageSize: 10 });
const accessTrendMode = ref<"incremental" | "cumulative">("incremental");
const outputTrendMode = ref<"incremental" | "cumulative">("incremental");
const cacheHitTrendMode = ref<"incremental" | "cumulative">("incremental");

const summary = computed(() => dashboard.value.summary || {});
const selectedAccessTrend = computed(() => (
  accessTrendMode.value === "cumulative"
    ? initializedCumulativeTrend(dashboard.value.cumulativeAccessTrend, dashboard.value.accessTrend)
    : dashboard.value.accessTrend
));
const selectedOutputRowTrend = computed(() => (
  outputTrendMode.value === "cumulative"
    ? initializedCumulativeTrend(dashboard.value.cumulativeOutputRowTrend, dashboard.value.outputRowTrend)
    : dashboard.value.outputRowTrend
));
const selectedCacheHitTrend = computed(() => (
  cacheHitTrendMode.value === "cumulative"
    ? initializedCumulativeTrend(dashboard.value.cumulativeCacheHitTrend, dashboard.value.cacheHitTrend)
    : dashboard.value.cacheHitTrend
));
const filteredSubscriptions = computed(() => {
  if (!filters.serviceId) {
    return options.subscriptions;
  }
  return options.subscriptions.filter((item) => String(item.serviceId) === String(filters.serviceId));
});
const errorDistributionOption = computed<EChartsOption>(() => {
  const items = dashboard.value.errorDistribution || [];
  return {
    tooltip: { trigger: "item" },
    legend: { bottom: 0 },
    series: [
      {
        name: "失败次数",
        type: "pie",
        radius: ["42%", "68%"],
        center: ["50%", "44%"],
        data: items.map((item) => ({ name: item.label || item.key || "UNKNOWN", value: item.count || 0 })),
      },
    ],
  };
});

onMounted(async () => {
  await loadOptions();
  await reloadAll();
});

async function loadOptions() {
  const payload = await studioApi.dataServiceMetrics.options();
  options.services = payload.services || [];
  options.subscriptions = payload.subscriptions || [];
}

async function reloadAll() {
  isLoading.value = true;
  try {
    await Promise.all([loadDashboard(), loadApiStats()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载服务监控失败");
  } finally {
    isLoading.value = false;
  }
}

async function loadDashboard() {
  dashboard.value = await studioApi.dataServiceMetrics.queryDashboard(buildQuery());
}

async function loadApiStats() {
  const page = await studioApi.dataServiceMetrics.queryApiStats(buildQuery({
    pageNo: apiStatsPagination.page,
    pageSize: apiStatsPagination.pageSize,
  }));
  apiStats.value = page.items || [];
  apiStatsTotal.value = page.total || 0;
}

function buildQuery(extra: Partial<DataServiceMetricQueryRequest> = {}): DataServiceMetricQueryRequest {
  return {
    startTime: timeRange.value?.[0],
    endTime: timeRange.value?.[1],
    serviceId: filters.serviceId || undefined,
    serviceStatus: filters.serviceStatus || undefined,
    subscriptionId: filters.subscriptionId || undefined,
    success: parseTriState(filters.success),
    cacheHit: parseTriState(filters.cacheHit),
    topN: 10,
    ...extra,
  };
}

function parseTriState(value: TriState) {
  if (value === "true") {
    return true;
  }
  if (value === "false") {
    return false;
  }
  return undefined;
}

function searchMetrics() {
  apiStatsPagination.page = 1;
  void reloadAll();
}

function resetFilters() {
  filters.serviceId = "";
  filters.serviceStatus = "";
  filters.subscriptionId = "";
  filters.success = "";
  filters.cacheHit = "";
  changeTimePreset("7d");
  searchMetrics();
}

function changeTimePreset(value: string | number | boolean) {
  const preset = String(value);
  timePreset.value = preset;
  const current = new Date();
  if (preset === "24h") {
    timeRange.value = [formatDateTimeValue(addHours(current, -24)), formatDateTimeValue(current)];
  } else if (preset === "30d") {
    timeRange.value = [formatDateTimeValue(addDays(current, -30)), formatDateTimeValue(current)];
  } else if (preset === "7d") {
    timeRange.value = [formatDateTimeValue(addDays(current, -7)), formatDateTimeValue(current)];
  }
}

function handleApiStatsPageSizeChange() {
  apiStatsPagination.page = 1;
  void loadApiStats();
}

function viewService(serviceId?: EntityId) {
  if (!serviceId) {
    return;
  }
  void router.push(`/data-services/${serviceId}/edit`);
}

function viewLogs(serviceId?: EntityId) {
  if (!serviceId) {
    return;
  }
  void router.push(`/data-service-metrics/access-logs?serviceId=${serviceId}`);
}

function initializedCumulativeTrend(cumulative?: RunMetricTrendView, incremental?: RunMetricTrendView): RunMetricTrendView | undefined {
  if (hasTrendData(cumulative)) {
    return cumulative;
  }
  return cumulativeTrendFromIncremental(incremental);
}

function hasTrendData(trend?: RunMetricTrendView) {
  if (!trendXAxis(trend).length) {
    return false;
  }
  return Boolean(trend?.series?.some((item) => item.data?.length));
}

function cumulativeTrendFromIncremental(trend?: RunMetricTrendView): RunMetricTrendView {
  const safeTrend = trend || { xAxis: [], series: [] };
  return {
    xAxis: trendXAxis(safeTrend),
    series: (safeTrend.series || []).map((item) => {
      let total = 0;
      return {
        ...item,
        data: (item.data || []).map((value) => {
          total += Number(value || 0);
          return total;
        }),
      };
    }),
  };
}

function trendXAxis(trend?: RunMetricTrendView): string[] {
  const payload = trend as (RunMetricTrendView & { xaxis?: string[] }) | undefined;
  return [...(payload?.xAxis || payload?.xaxis || [])];
}

function trendOption(trend?: RunMetricTrendView, unit = ""): EChartsOption {
  const safeTrend = trend || { xAxis: [], series: [] };
  return {
    tooltip: { trigger: "axis" },
    legend: { top: 0 },
    grid: { top: 42, left: 36, right: 18, bottom: 36, containLabel: true },
    xAxis: { type: "category", boundaryGap: false, data: trendXAxis(safeTrend) },
    yAxis: {
      type: "value",
      axisLabel: { formatter: unit ? `{value}${unit}` : "{value}" },
    },
    series: (safeTrend.series || []).map((item) => ({
      name: item.name || item.key,
      type: "line",
      smooth: true,
      symbolSize: 6,
      data: item.data || [],
    })),
  };
}

function serviceStatusLabel(status?: string) {
  if (status === "ONLINE") {
    return "已发布";
  }
  if (status === "OFFLINE") {
    return "已下线";
  }
  if (status === "DRAFT") {
    return "草稿";
  }
  return status || "-";
}

function serviceStatusTone(status?: string): "success" | "warning" | "neutral" | "primary" | "danger" {
  if (status === "ONLINE") {
    return "success";
  }
  if (status === "OFFLINE") {
    return "warning";
  }
  if (status === "DRAFT") {
    return "neutral";
  }
  return "neutral";
}

function formatNumber(value?: number) {
  return Number(value || 0).toLocaleString();
}

function formatRate(value?: number) {
  return `${Number(value || 0).toFixed(2)}%`;
}

function formatCacheRate(value?: { cacheEnabledCount?: number; cacheHitRate?: number }) {
  if (!Number(value?.cacheEnabledCount || 0)) {
    return "未开启";
  }
  return formatRate(value?.cacheHitRate);
}

function formatMs(value?: number) {
  return `${Number(value || 0).toLocaleString()}ms`;
}

function formatDateTime(value?: string) {
  return value || "-";
}

function formatDateTimeValue(date: Date) {
  const year = date.getFullYear();
  const month = pad(date.getMonth() + 1);
  const day = pad(date.getDate());
  const hour = pad(date.getHours());
  const minute = pad(date.getMinutes());
  const second = pad(date.getSeconds());
  return `${year}-${month}-${day} ${hour}:${minute}:${second}`;
}

function pad(value: number) {
  return String(value).padStart(2, "0");
}

function addDays(date: Date, days: number) {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

function addHours(date: Date, hours: number) {
  const next = new Date(date);
  next.setHours(next.getHours() + hours);
  return next;
}
</script>

<style scoped>
.data-service-metrics-page {
  gap: 14px;
}

.data-service-metrics-page :deep(.section-card) {
  border-radius: 18px;
}

.data-service-metrics-page :deep(.section-card__header) {
  padding: 12px 14px 0;
}

.data-service-metrics-page :deep(.section-card__title) {
  font-size: 15px;
}

.data-service-metrics-page :deep(.section-card__body) {
  padding: 10px 14px 14px;
}

.data-service-metrics-page :deep(.metric-card) {
  min-height: 104px;
  padding: 14px 16px;
  border-radius: 18px;
}

.data-service-metrics-page :deep(.metric-card__meta) {
  gap: 8px;
}

.data-service-metrics-page :deep(.metric-card__label) {
  font-size: 12px;
  letter-spacing: 0.04em;
}

.data-service-metrics-page :deep(.metric-card__value) {
  margin-top: 10px;
  font-size: 27px;
  line-height: 1.08;
}

.data-service-metrics-page :deep(.metric-card__description) {
  margin-top: 6px;
}

.data-service-metrics-page :deep(.el-table .cell) {
  line-height: 20px;
}

.metrics-filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(160px, 1fr));
  gap: 10px;
  align-items: center;
}

.metrics-filter-grid > * {
  min-width: 0;
}

.metrics-filter-grid :deep(.el-select__wrapper),
.metrics-filter-grid :deep(.el-input__wrapper),
.metrics-filter-grid :deep(.el-date-editor) {
  min-height: 34px;
  height: 34px;
  box-sizing: border-box;
}

.time-filter-shell {
  grid-column: span 2;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.time-filter-shell :deep(.el-radio-group),
.time-filter-shell :deep(.el-date-editor) {
  min-height: 34px;
  height: 34px;
}

.time-filter-shell :deep(.el-radio-group) {
  flex: 0 0 auto;
  white-space: nowrap;
}

.time-filter-shell :deep(.el-date-editor) {
  flex: 1 1 320px;
  min-width: min(100%, 320px);
  width: 100%;
}

.metrics-filter-actions {
  display: inline-flex;
  justify-content: flex-start;
  gap: 8px;
  justify-self: start;
  white-space: nowrap;
}

.metric-grid--monitor {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.metrics-chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.metrics-table-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.table-entity-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.table-entity-cell__title {
  font-weight: 700;
  color: var(--studio-text);
}

.table-entity-cell__meta {
  font-size: 12px;
  color: var(--studio-text-soft);
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

@media (max-width: 1680px) {
  .metric-grid--monitor {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 1280px) {
  .metrics-filter-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .metrics-chart-grid,
  .metrics-table-grid {
    grid-template-columns: 1fr;
  }

  .metrics-filter-actions {
    grid-column: auto;
  }
}

@media (max-width: 768px) {
  .metrics-filter-grid,
  .metric-grid--monitor {
    grid-template-columns: 1fr;
  }

  .time-filter-shell {
    grid-column: span 1;
  }
}
</style>
