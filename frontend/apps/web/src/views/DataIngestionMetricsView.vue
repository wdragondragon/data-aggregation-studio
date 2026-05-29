<template>
  <div class="studio-page data-ingestion-metrics-page">
    <div class="studio-toolbar">
      <div>
        <h3>服务接入监控</h3>
        <p>统计数据接入服务开放 API 的调用量、接收行数、写入结果和耗时。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="router.push('/data-ingestion-services')">接入服务列表</el-button>
        <el-button plain @click="router.push('/data-ingestion-metrics/access-logs')">运行日志</el-button>
        <el-button type="primary" :loading="isLoading" @click="reloadAll">刷新</el-button>
      </div>
    </div>

    <SectionCard title="监控筛选" description="时间范围、服务、状态、订阅方和调用结果会同步影响所有统计。">
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
        <el-select v-model="filters.serviceId" clearable filterable placeholder="全部接入服务">
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
        <div class="metrics-filter-actions">
          <el-button type="primary" :loading="isLoading" @click="searchMetrics">查询</el-button>
          <el-button plain @click="resetFilters">重置</el-button>
        </div>
      </div>
    </SectionCard>

    <div class="metric-grid metric-grid--ingestion">
      <MetricCard label="总调用次数" :value="formatNumber(summary.accessCount)" tone="primary" />
      <MetricCard label="成功调用" :value="formatNumber(summary.successCount)" tone="success" />
      <MetricCard label="失败调用" :value="formatNumber(summary.failureCount)" tone="warning" />
      <MetricCard label="调用成功率" :value="formatRate(summary.successRate)" tone="success" />
      <MetricCard label="接收行数" :value="formatNumber(summary.receivedCount)" tone="primary" />
      <MetricCard label="写入成功行数" :value="formatNumber(summary.writtenCount)" tone="success" />
      <MetricCard label="写入失败行数" :value="formatNumber(summary.failedCount)" tone="warning" />
      <MetricCard label="平均耗时" :value="formatMs(summary.avgResponseTimeMs)" tone="accent" />
    </div>

    <div class="metrics-chart-grid">
      <SectionCard title="调用趋势" description="按时间查看新增调用量，或切换为当前窗口内累计调用量。">
        <template #actions>
          <el-radio-group v-model="accessTrendMode" size="small">
            <el-radio-button value="incremental">增量</el-radio-button>
            <el-radio-button value="cumulative">总量</el-radio-button>
          </el-radio-group>
        </template>
        <EChartPanel :option="trendOption(selectedAccessTrend, '')" height="260px" />
      </SectionCard>
      <SectionCard title="接收数据量" description="按请求解析后的行数统计接收规模。">
        <template #actions>
          <el-radio-group v-model="receivedTrendMode" size="small">
            <el-radio-button value="incremental">增量</el-radio-button>
            <el-radio-button value="cumulative">总量</el-radio-button>
          </el-radio-group>
        </template>
        <EChartPanel :option="trendOption(selectedReceivedTrend, '行')" height="260px" />
      </SectionCard>
      <SectionCard title="写入成功数据量" description="按写入结果中的成功行数统计。">
        <template #actions>
          <el-radio-group v-model="writtenTrendMode" size="small">
            <el-radio-button value="incremental">增量</el-radio-button>
            <el-radio-button value="cumulative">总量</el-radio-button>
          </el-radio-group>
        </template>
        <EChartPanel :option="trendOption(selectedWrittenTrend, '行')" height="260px" />
      </SectionCard>
      <SectionCard title="响应时间趋势" description="展示平均耗时、最大耗时、P95 和 P99。">
        <EChartPanel :option="trendOption(dashboard.responseTimeTrend, 'ms')" height="260px" />
      </SectionCard>
      <SectionCard title="成功率趋势" description="观察接入 API 可用性变化。">
        <EChartPanel :option="trendOption(dashboard.successRateTrend, '%')" height="260px" />
      </SectionCard>
      <SectionCard title="错误分布" description="按 HTTP 状态或业务错误码聚合失败调用。">
        <EChartPanel :option="errorDistributionOption" height="260px" />
      </SectionCard>
    </div>

    <SectionCard title="接入服务统计" description="按服务聚合调用次数、成功率、写入行数和响应时间。">
      <StudioTableShell min-width="1460px">
        <el-table :data="apiStats" border size="small">
          <el-table-column label="接入服务" min-width="220">
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
          <el-table-column label="调用" width="110" align="right" header-align="right">
            <template #default="{ row }">{{ formatNumber(row.accessCount) }}</template>
          </el-table-column>
          <el-table-column label="成功/失败" width="130" align="center" header-align="center">
            <template #default="{ row }">{{ formatNumber(row.successCount) }} / {{ formatNumber(row.failureCount) }}</template>
          </el-table-column>
          <el-table-column label="成功率" width="110" align="right" header-align="right">
            <template #default="{ row }">{{ formatRate(row.successRate) }}</template>
          </el-table-column>
          <el-table-column label="接收/写入" width="150" align="center" header-align="center">
            <template #default="{ row }">{{ formatNumber(row.receivedCount) }} / {{ formatNumber(row.writtenCount) }}</template>
          </el-table-column>
          <el-table-column label="写入失败" width="110" align="right" header-align="right">
            <template #default="{ row }">{{ formatNumber(row.failedCount) }}</template>
          </el-table-column>
          <el-table-column label="平均耗时" width="110" align="right" header-align="right">
            <template #default="{ row }">{{ formatMs(row.avgResponseTimeMs) }}</template>
          </el-table-column>
          <el-table-column label="P95/P99" width="130" align="center" header-align="center">
            <template #default="{ row }">{{ formatMs(row.p95ResponseTimeMs) }} / {{ formatMs(row.p99ResponseTimeMs) }}</template>
          </el-table-column>
          <el-table-column label="最近调用" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.lastAccessAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right" align="center" header-align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="viewService(row.serviceId)">查看服务</el-button>
              <el-button link type="primary" @click="viewLogs(row.serviceId)">运行日志</el-button>
            </template>
          </el-table-column>
        </el-table>
      </StudioTableShell>
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
      <SectionCard title="Top 慢接入服务" description="按 P95 和最大响应时间排序。">
        <StudioTableShell min-width="440px">
          <el-table :data="dashboard.topSlowServices || []" border size="small">
            <el-table-column prop="serviceName" label="服务" min-width="180" />
            <el-table-column label="P95" width="90" align="right" header-align="right">
              <template #default="{ row }">{{ formatMs(row.p95ResponseTimeMs) }}</template>
            </el-table-column>
            <el-table-column label="最大" width="90" align="right" header-align="right">
              <template #default="{ row }">{{ formatMs(row.maxResponseTimeMs) }}</template>
            </el-table-column>
          </el-table>
        </StudioTableShell>
      </SectionCard>
      <SectionCard title="Top 失败接入服务" description="按失败次数和失败率排序。">
        <StudioTableShell min-width="460px">
          <el-table :data="dashboard.topFailedServices || []" border size="small">
            <el-table-column prop="serviceName" label="服务" min-width="180" />
            <el-table-column label="失败" width="90" align="right" header-align="right">
              <template #default="{ row }">{{ formatNumber(row.failureCount) }}</template>
            </el-table-column>
            <el-table-column label="成功率" width="100" align="right" header-align="right">
              <template #default="{ row }">{{ formatRate(row.successRate) }}</template>
            </el-table-column>
          </el-table>
        </StudioTableShell>
      </SectionCard>
      <SectionCard title="订阅方排行" description="按订阅 Token 调用量聚合。">
        <StudioTableShell min-width="620px">
          <el-table :data="dashboard.subscriptionRank || []" border size="small">
            <el-table-column prop="subscriptionName" label="订阅方" min-width="170" />
            <el-table-column prop="serviceName" label="服务" min-width="150" />
            <el-table-column label="调用" width="90" align="right" header-align="right">
              <template #default="{ row }">{{ formatNumber(row.accessCount) }}</template>
            </el-table-column>
            <el-table-column label="成功率" width="100" align="right" header-align="right">
              <template #default="{ row }">{{ formatRate(row.successRate) }}</template>
            </el-table-column>
          </el-table>
        </StudioTableShell>
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
  DataIngestionApiMetricView,
  DataIngestionMetricDashboardView,
  DataIngestionMetricQueryRequest,
  DataServiceMetricOptionsView,
  EntityId,
  RunMetricTrendView,
} from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
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
}>({
  serviceId: route.query.serviceId ? String(route.query.serviceId) : "",
  serviceStatus: "",
  subscriptionId: "",
  success: "",
});
const options = reactive<DataServiceMetricOptionsView>({ services: [], subscriptions: [] });
const dashboard = ref<DataIngestionMetricDashboardView>({});
const apiStats = ref<DataIngestionApiMetricView[]>([]);
const apiStatsTotal = ref(0);
const isLoading = ref(false);
const apiStatsPagination = reactive({ page: 1, pageSize: 10 });
const accessTrendMode = ref<"incremental" | "cumulative">("incremental");
const receivedTrendMode = ref<"incremental" | "cumulative">("incremental");
const writtenTrendMode = ref<"incremental" | "cumulative">("incremental");

const summary = computed(() => dashboard.value.summary || {});
const selectedAccessTrend = computed(() => (
  accessTrendMode.value === "cumulative"
    ? initializedCumulativeTrend(dashboard.value.cumulativeAccessTrend, dashboard.value.accessTrend)
    : dashboard.value.accessTrend
));
const selectedReceivedTrend = computed(() => (
  receivedTrendMode.value === "cumulative"
    ? initializedCumulativeTrend(dashboard.value.cumulativeReceivedTrend, dashboard.value.receivedTrend)
    : dashboard.value.receivedTrend
));
const selectedWrittenTrend = computed(() => (
  writtenTrendMode.value === "cumulative"
    ? initializedCumulativeTrend(dashboard.value.cumulativeWrittenTrend, dashboard.value.writtenTrend)
    : dashboard.value.writtenTrend
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
  const payload = await studioApi.dataIngestionMetrics.options();
  options.services = payload.services || [];
  options.subscriptions = payload.subscriptions || [];
}

async function reloadAll() {
  isLoading.value = true;
  try {
    await Promise.all([loadDashboard(), loadApiStats()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载服务接入监控失败");
  } finally {
    isLoading.value = false;
  }
}

async function loadDashboard() {
  dashboard.value = await studioApi.dataIngestionMetrics.queryDashboard(buildQuery());
}

async function loadApiStats() {
  const page = await studioApi.dataIngestionMetrics.queryApiStats(buildQuery({
    pageNo: apiStatsPagination.page,
    pageSize: apiStatsPagination.pageSize,
  }));
  apiStats.value = page.items || [];
  apiStatsTotal.value = Number(page.total || 0);
}

function buildQuery(extra: Partial<DataIngestionMetricQueryRequest> = {}): DataIngestionMetricQueryRequest {
  return {
    startTime: timeRange.value?.[0],
    endTime: timeRange.value?.[1],
    serviceId: filters.serviceId || undefined,
    serviceStatus: filters.serviceStatus || undefined,
    subscriptionId: filters.subscriptionId || undefined,
    success: parseTriState(filters.success),
    topN: 10,
    ...extra,
  };
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
  changeTimePreset("7d");
  searchMetrics();
}

function handleApiStatsPageSizeChange() {
  apiStatsPagination.page = 1;
  void loadApiStats();
}

function viewService(serviceId?: EntityId) {
  if (!serviceId) {
    return;
  }
  void router.push(`/data-ingestion-services/${serviceId}/edit`);
}

function viewLogs(serviceId?: EntityId) {
  void router.push({
    path: "/data-ingestion-metrics/access-logs",
    query: serviceId ? { serviceId: String(serviceId) } : {},
  });
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

function trendOption(trend?: RunMetricTrendView, unit = ""): EChartsOption {
  const safeTrend = trend || { xAxis: [], series: [] };
  return {
    tooltip: { trigger: "axis" },
    legend: { top: 0 },
    grid: { left: 42, right: 20, top: 42, bottom: 34 },
    xAxis: { type: "category", data: safeTrend.xAxis || [], boundaryGap: false },
    yAxis: { type: "value", axisLabel: { formatter: `{value}${unit}` } },
    series: (safeTrend.series || []).map((item) => ({
      name: item.name,
      type: "line",
      smooth: true,
      showSymbol: false,
      areaStyle: { opacity: 0.08 },
      data: item.data || [],
    })),
  };
}

function initializedCumulativeTrend(cumulative?: RunMetricTrendView, fallback?: RunMetricTrendView) {
  if (cumulative?.series?.length) {
    return cumulative;
  }
  return fallback;
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

function serviceStatusLabel(status?: string) {
  if (status === "ONLINE") {
    return "已发布";
  }
  if (status === "OFFLINE") {
    return "已下线";
  }
  return "草稿";
}

function serviceStatusTone(status?: string): "success" | "warning" | "neutral" {
  if (status === "ONLINE") {
    return "success";
  }
  if (status === "OFFLINE") {
    return "warning";
  }
  return "neutral";
}

function formatNumber(value?: number | string) {
  return Number(value || 0).toLocaleString();
}

function formatRate(value?: number | string) {
  return `${Number(value || 0).toFixed(2)}%`;
}

function formatMs(value?: number | string) {
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
.data-ingestion-metrics-page {
  gap: 14px;
}

.metrics-filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 10px;
  align-items: center;
}

.metrics-filter-grid > * {
  min-width: 0;
}

.time-filter-shell {
  grid-column: span 2;
  display: flex;
  gap: 10px;
  align-items: center;
}

.time-filter-shell :deep(.el-date-editor) {
  flex: 1;
}

.metrics-filter-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.metric-grid--ingestion,
.metrics-chart-grid,
.metrics-table-grid {
  display: grid;
  gap: 14px;
}

.metric-grid--ingestion {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.metrics-chart-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.metrics-table-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}

@media (max-width: 1200px) {
  .metric-grid--ingestion,
  .metrics-chart-grid,
  .metrics-table-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .metrics-filter-grid,
  .metric-grid--ingestion,
  .metrics-chart-grid,
  .metrics-table-grid {
    grid-template-columns: 1fr;
  }

  .time-filter-shell {
    grid-column: span 1;
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
