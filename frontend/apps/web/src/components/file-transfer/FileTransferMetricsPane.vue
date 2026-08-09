<template>
  <div class="transfer-metrics">
    <section class="metric-filters">
      <el-select v-model="filters.taskId" clearable filterable placeholder="全部预设任务">
        <el-option v-for="task in taskOptions" :key="String(task.id)" :label="task.name" :value="task.id" />
      </el-select>
      <el-select v-model="filters.runtimeClusterId" clearable filterable placeholder="全部运行集群" @change="loadDatasourceOptions">
        <el-option v-for="cluster in runtimeClusters" :key="String(cluster.id)" :label="cluster.name || cluster.code" :value="cluster.id" />
      </el-select>
      <el-select v-model="filters.datasourceId" clearable filterable placeholder="全部数据源" :disabled="!filters.runtimeClusterId">
        <el-option v-for="datasource in datasourceOptions" :key="String(datasource.id)" :label="datasource.name" :value="datasource.id" />
      </el-select>
      <el-select v-model="filters.channel" clearable placeholder="全部通道">
        <el-option label="同集群 Worker" value="LOCAL_WORKER" />
        <el-option label="Worker 直连" value="WORKER_DIRECT" />
      </el-select>
      <el-select v-model="filters.status" clearable placeholder="全部状态">
        <el-option v-for="status in runStatuses" :key="status" :label="fileTransferStatusLabel(status)" :value="status" />
      </el-select>
      <el-date-picker
        v-model="timeRange"
        type="datetimerange"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        value-format="YYYY-MM-DD HH:mm:ss"
      />
      <el-input-number v-model="filters.topN" :min="3" :max="50" controls-position="right" aria-label="TopN 数量" />
      <div class="metric-filter-actions">
        <el-button type="primary" :loading="loading" @click="loadDashboard">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </section>

    <section class="metric-stat-grid">
      <div>
        <span>运行总数</span>
        <strong>{{ formatTransferNumber(dashboard.runCount) }}</strong>
        <small>成功 {{ formatTransferNumber(dashboard.successRunCount) }} · 失败 {{ formatTransferNumber(dashboard.failedRunCount) }}</small>
      </div>
      <div>
        <span>文件总数</span>
        <strong>{{ formatTransferNumber(dashboard.totalFiles) }}</strong>
        <small>成功 {{ formatTransferNumber(dashboard.successFiles) }} · 跳过 {{ formatTransferNumber(dashboard.skippedFiles) }}</small>
      </div>
      <div>
        <span>已传输字节</span>
        <strong>{{ formatBytes(dashboard.transferredBytes) }}</strong>
        <small>失败 {{ formatBytes(dashboard.failedBytes) }} · 续传 {{ formatBytes(dashboard.resumedBytes) }}</small>
      </div>
      <div>
        <span>当前速度</span>
        <strong>{{ formatTransferSpeed(dashboard.currentBytesPerSecond) }}</strong>
        <small>平均 {{ formatTransferSpeed(dashboard.averageBytesPerSecond) }}</small>
      </div>
      <div>
        <span>峰值速度</span>
        <strong>{{ formatTransferSpeed(dashboard.peakBytesPerSecond) }}</strong>
        <small>总字节 {{ formatBytes(dashboard.totalBytes) }}</small>
      </div>
      <div>
        <span>预计剩余</span>
        <strong>{{ formatDurationSeconds(dashboard.estimatedRemainingSeconds) }}</strong>
        <small>活动文件 {{ formatTransferNumber(dashboard.activeFiles) }}</small>
      </div>
      <div>
        <span>异常文件</span>
        <strong>{{ formatTransferNumber(dashboard.failedFiles) }}</strong>
        <small>冲突 {{ formatTransferNumber(dashboard.conflictFiles) }} · 后处理 {{ formatTransferNumber(dashboard.postActionFailedFiles) }}</small>
      </div>
      <div>
        <span>累计重试</span>
        <strong>{{ formatTransferNumber(dashboard.retryCount) }}</strong>
        <small>续传文件 {{ formatTransferNumber(dashboard.resumedFiles) }}</small>
      </div>
    </section>

    <section class="metric-chart-section">
      <header>
        <strong>传输趋势</strong>
        <span>累计字节、实时速度与文件结果</span>
      </header>
      <EChartPanel v-if="trendHasData" :option="trendOption" height="390px" />
      <div v-else class="metric-empty">当前筛选范围内暂无传输样本</div>
    </section>

    <div class="metric-topn-grid">
      <section>
        <header><strong>来源数据源 TopN</strong><span>按成功文件数</span></header>
        <el-table :data="sourceTopN" size="small" table-layout="fixed" height="280" empty-text="暂无数据">
          <el-table-column type="index" label="#" width="48" align="center" />
          <el-table-column label="数据源" min-width="180" show-overflow-tooltip prop="label" />
          <el-table-column label="文件" width="100" align="right">
            <template #default="{ row }">{{ formatTransferNumber(row.count) }}</template>
          </el-table-column>
        </el-table>
      </section>
      <section>
        <header><strong>目标数据源 TopN</strong><span>按成功文件数</span></header>
        <el-table :data="targetTopN" size="small" table-layout="fixed" height="280" empty-text="暂无数据">
          <el-table-column type="index" label="#" width="48" align="center" />
          <el-table-column label="数据源" min-width="180" show-overflow-tooltip prop="label" />
          <el-table-column label="文件" width="100" align="right">
            <template #default="{ row }">{{ formatTransferNumber(row.count) }}</template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import type { EChartsOption } from "echarts";
import type {
  DataSourceOptionView,
  EntityId,
  FileTransferMetricDashboardView,
  FileTransferTaskDefinitionView,
  RunMetricTopNItem,
  RuntimeClusterView,
} from "@studio/api-sdk";
import EChartPanel from "@/components/EChartPanel.vue";
import { studioApi } from "@/api/studio";
import {
  fileTransferStatusLabel,
  formatBytes,
  formatDurationSeconds,
  formatTransferNumber,
  formatTransferSpeed,
  isFileTransferDatasourceType,
  toTransferNumber,
} from "@/utils/fileTransfer";

const loading = ref(false);
const taskOptions = ref<FileTransferTaskDefinitionView[]>([]);
const runtimeClusters = ref<RuntimeClusterView[]>([]);
const datasourceOptions = ref<DataSourceOptionView[]>([]);
const filters = reactive<{
  taskId?: EntityId;
  runtimeClusterId?: EntityId;
  datasourceId?: EntityId;
  channel?: string;
  status?: string;
  topN: number;
}>({ topN: 10 });
const timeRange = ref<[string, string] | []>(defaultTimeRange());
const dashboard = ref<FileTransferMetricDashboardView>(emptyDashboard());
const runStatuses = ["QUEUED", "RUNNING", "PAUSED", "SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELED"];

function emptyDashboard(): FileTransferMetricDashboardView {
  return {
    trend: [],
    sourceDatasourceTopN: [],
    targetDatasourceTopN: [],
  };
}

const trendHasData = computed(() => dashboard.value.trend.some((point) =>
  toTransferNumber(point.transferredBytes) > 0
  || toTransferNumber(point.bytesPerSecond) > 0
  || toTransferNumber(point.completedFiles) > 0
  || toTransferNumber(point.failedFiles) > 0));

const trendOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: "axis" },
  legend: { top: 0, data: ["累计字节", "实时速度", "完成文件", "失败文件"] },
  grid: { left: 16, right: 24, top: 52, bottom: 18, containLabel: true },
  xAxis: {
    type: "category",
    boundaryGap: false,
    data: dashboard.value.trend.map((point) => formatSampleTime(point.sampledAt)),
  },
  yAxis: [
    { type: "value", name: "字节", axisLabel: { formatter: (value: number) => shortBytes(value) } },
    { type: "value", name: "文件", minInterval: 1 },
  ],
  series: [
    {
      name: "累计字节",
      type: "line",
      smooth: true,
      showSymbol: false,
      data: dashboard.value.trend.map((point) => toTransferNumber(point.transferredBytes)),
      lineStyle: { width: 2, color: "#2563eb" },
      itemStyle: { color: "#2563eb" },
    },
    {
      name: "实时速度",
      type: "line",
      smooth: true,
      showSymbol: false,
      data: dashboard.value.trend.map((point) => toTransferNumber(point.bytesPerSecond)),
      lineStyle: { width: 2, color: "#0f766e" },
      itemStyle: { color: "#0f766e" },
    },
    {
      name: "完成文件",
      type: "bar",
      yAxisIndex: 1,
      data: dashboard.value.trend.map((point) => toTransferNumber(point.completedFiles)),
      itemStyle: { color: "#38bdf8" },
    },
    {
      name: "失败文件",
      type: "bar",
      yAxisIndex: 1,
      data: dashboard.value.trend.map((point) => toTransferNumber(point.failedFiles)),
      itemStyle: { color: "#dc2626" },
    },
  ],
}));

const sourceTopN = computed(() => normalizeTopN(dashboard.value.sourceDatasourceTopN));
const targetTopN = computed(() => normalizeTopN(dashboard.value.targetDatasourceTopN));

async function loadOptions() {
  const [tasks, clusters] = await Promise.all([
    studioApi.fileTransfer.tasks.list({ pageNo: 1, pageSize: 200 }),
    studioApi.runtimeClusters.options(),
  ]);
  taskOptions.value = tasks.items;
  runtimeClusters.value = clusters.filter((item) => item.enabled !== false);
}

async function loadDatasourceOptions() {
  filters.datasourceId = undefined;
  if (!filters.runtimeClusterId) {
    datasourceOptions.value = [];
    return;
  }
  datasourceOptions.value = (await studioApi.datasources.optionsByRuntimeCluster(filters.runtimeClusterId))
    .filter((item) => isFileTransferDatasourceType(item.typeCode));
}

async function loadDashboard() {
  loading.value = true;
  try {
    dashboard.value = await studioApi.fileTransfer.metrics.dashboard({
      taskId: filters.taskId,
      runtimeClusterId: filters.runtimeClusterId,
      datasourceId: filters.datasourceId,
      channel: filters.channel,
      status: filters.status,
      startedAt: timeRange.value[0],
      endedAt: timeRange.value[1],
      topN: filters.topN,
    });
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? error.message : "加载文件传输指标失败");
  } finally {
    loading.value = false;
  }
}

async function resetFilters() {
  filters.taskId = undefined;
  filters.runtimeClusterId = undefined;
  filters.datasourceId = undefined;
  filters.channel = undefined;
  filters.status = undefined;
  filters.topN = 10;
  datasourceOptions.value = [];
  timeRange.value = defaultTimeRange();
  await loadDashboard();
}

function normalizeTopN(items?: RunMetricTopNItem[]) {
  return (items ?? []).map((item) => ({
    id: item.id,
    label: item.label || item.name || "未知数据源",
    count: item.count,
  }));
}

function defaultTimeRange(): [string, string] {
  const end = new Date();
  const start = new Date(end.getTime() - 29 * 24 * 60 * 60 * 1000);
  return [formatDateTime(start), formatDateTime(end)];
}

function formatDateTime(value: Date) {
  const pad = (item: number) => String(item).padStart(2, "0");
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())} ${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`;
}

function formatSampleTime(value?: string) {
  if (!value) return "--";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(date);
}

function shortBytes(value: number) {
  const bytes = Math.max(0, value);
  if (bytes >= 1024 ** 4) return `${(bytes / 1024 ** 4).toFixed(1)}T`;
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)}G`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)}M`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)}K`;
  return String(Math.round(bytes));
}

onMounted(async () => {
  try {
    await Promise.all([loadOptions(), loadDashboard()]);
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? error.message : "加载文件传输指标失败");
  }
});
</script>

<style scoped>
.transfer-metrics {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.metric-filters {
  display: grid;
  grid-template-columns: repeat(5, minmax(138px, 1fr));
  gap: 10px;
  align-items: center;
  min-width: 0;
}

.metric-filters :deep(.el-date-editor) {
  grid-column: span 2;
  width: 100%;
}

.metric-filters > .el-input-number {
  width: 100%;
}

.metric-filter-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.metric-stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: var(--studio-border);
}

.metric-stat-grid > div {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 15px;
  background: var(--studio-surface-strong);
}

.metric-stat-grid span,
.metric-stat-grid small,
.metric-chart-section header span,
.metric-topn-grid header span {
  color: var(--studio-text-soft);
}

.metric-stat-grid span {
  font-size: 12px;
}

.metric-stat-grid strong {
  overflow: hidden;
  font-size: 24px;
  letter-spacing: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-stat-grid small {
  overflow: hidden;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-chart-section,
.metric-topn-grid > section {
  min-width: 0;
  padding-top: 4px;
}

.metric-chart-section header,
.metric-topn-grid header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.metric-chart-section header span,
.metric-topn-grid header span {
  font-size: 12px;
}

.metric-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 220px;
  border: 1px dashed var(--studio-border);
  border-radius: 8px;
  color: var(--studio-text-soft);
}

.metric-topn-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

@media (max-width: 1180px) {
  .metric-filters {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .metric-stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .metric-filters,
  .metric-stat-grid,
  .metric-topn-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .metric-filters :deep(.el-date-editor) {
    grid-column: 1;
  }

  .metric-filter-actions {
    justify-content: stretch;
  }

  .metric-filter-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
