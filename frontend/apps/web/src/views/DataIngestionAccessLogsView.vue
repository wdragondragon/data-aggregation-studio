<template>
  <div class="studio-page data-ingestion-access-logs-page">
    <div class="studio-toolbar">
      <div>
        <h3>接入运行日志</h3>
        <p>查看数据接入服务开放 API 的异常、慢调用和写入数量摘要。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="router.push('/data-ingestion-metrics')">返回监控</el-button>
        <el-button type="primary" :loading="isLoading" @click="loadLogs">刷新</el-button>
      </div>
    </div>

    <SectionCard title="日志筛选" description="默认查看异常或慢调用；慢调用阈值可按排查需要调整。">
      <div class="logs-filter-grid">
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
        <el-select v-model="filters.subscriptionId" clearable filterable placeholder="全部订阅方">
          <el-option v-for="item in filteredSubscriptions" :key="String(item.id)" :label="item.label || item.name || String(item.id)" :value="item.id" />
        </el-select>
        <el-select v-model="filters.requestedClusterId" clearable filterable :placeholder="t('web.runtimeClusterSelection.targetCluster')">
          <el-option v-for="item in runtimeClusters" :key="`target-${String(item.id)}`" :label="clusterLabel(item.id, item.code)" :value="item.id" />
        </el-select>
        <el-select v-model="filters.actualClusterId" clearable filterable :placeholder="t('web.runtimeClusterSelection.actualCluster')">
          <el-option v-for="item in runtimeClusters" :key="`actual-${String(item.id)}`" :label="clusterLabel(item.id, item.code)" :value="item.id" />
        </el-select>
        <el-select v-model="filters.logFocus" placeholder="日志类型">
          <el-option label="异常或慢调用" value="ERROR_OR_SLOW" />
          <el-option label="仅异常调用" value="ERROR" />
          <el-option label="仅慢调用" value="SLOW" />
          <el-option label="全部调用" value="ALL" />
        </el-select>
        <el-input-number
          v-model="filters.minDurationMs"
          :min="0"
          :step="100"
          controls-position="right"
          placeholder="慢调用阈值"
        />
        <el-select v-model="filters.success" clearable placeholder="调用结果">
          <el-option label="成功" value="true" />
          <el-option label="失败" value="false" />
        </el-select>
        <div class="logs-filter-actions">
          <el-button type="primary" :loading="isLoading" @click="searchLogs">查询</el-button>
          <el-button plain @click="resetFilters">重置</el-button>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="运行日志明细" description="表格重点展示异常摘要、响应耗时、接收行数、写入结果和调用方。">
      <StudioTableShell min-width="1680px">
        <el-table :data="logs" border size="small">
          <el-table-column prop="occurredAt" label="调用时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.occurredAt) }}</template>
          </el-table-column>
          <el-table-column label="类型" width="110" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill :label="logTypeLabel(row)" :tone="logTypeTone(row)" />
            </template>
          </el-table-column>
          <el-table-column label="异常摘要" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">
              <MessagePreviewText :text="errorSummary(row)" />
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="110" align="right" header-align="right">
            <template #default="{ row }">{{ formatMs(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column prop="httpStatus" label="HTTP" width="90" align="center" header-align="center" />
          <el-table-column label="结果" width="100" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill :label="row.success ? '成功' : '失败'" :tone="row.success ? 'success' : 'danger'" />
            </template>
          </el-table-column>
          <el-table-column label="接入服务" min-width="190">
            <template #default="{ row }">
              <div class="table-entity-cell">
                <span class="table-entity-cell__title">{{ row.serviceName || "-" }}</span>
                <span class="table-entity-cell__meta">{{ row.serviceCode || "-" }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runtimeClusterSelection.runtimePlacement')" min-width="220">
            <template #default="{ row }">
              <div class="table-entity-cell">
                <span class="table-entity-cell__title">{{ t("web.runtimeClusterSelection.targetCluster") }}: {{ clusterLabel(row.requestedClusterId) }}</span>
                <span class="table-entity-cell__meta">{{ t("web.runtimeClusterSelection.actualCluster") }}: {{ clusterLabel(row.actualClusterId) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="subscriptionName" label="订阅方" min-width="150" />
          <el-table-column prop="requestMethod" label="方法" width="90" align="center" header-align="center" />
          <el-table-column label="接收" width="100" align="right" header-align="right">
            <template #default="{ row }">{{ formatNumber(row.receivedCount) }}</template>
          </el-table-column>
          <el-table-column label="写入成功" width="110" align="right" header-align="right">
            <template #default="{ row }">{{ formatNumber(row.successCount) }}</template>
          </el-table-column>
          <el-table-column label="写入失败" width="110" align="right" header-align="right">
            <template #default="{ row }">{{ formatNumber(row.failedCount) }}</template>
          </el-table-column>
          <el-table-column prop="clientIp" label="客户端 IP" min-width="140" />
          <el-table-column prop="userAgent" label="User-Agent" min-width="220" show-overflow-tooltip />
          <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openLogDetail(row)">详情</el-button>
              <el-button link type="primary" @click="openInvocationLog(row)">完整日志</el-button>
            </template>
          </el-table-column>
        </el-table>
      </StudioTableShell>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          @current-change="loadLogs"
          @size-change="handlePageSizeChange"
        />
      </div>
    </SectionCard>

    <el-drawer v-model="logDetailVisible" title="接入运行日志详情" size="52%">
      <template v-if="activeLog">
        <div class="log-detail-grid">
          <div class="log-detail-item">
            <span class="log-detail-item__label">请求 ID</span>
            <strong>{{ activeLog.requestId || "-" }}</strong>
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">调用时间</span>
            <strong>{{ formatDateTime(activeLog.occurredAt) }}</strong>
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">日志类型</span>
            <StatusPill :label="logTypeLabel(activeLog)" :tone="logTypeTone(activeLog)" />
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">调用结果</span>
            <StatusPill :label="activeLog.success ? '成功' : '失败'" :tone="activeLog.success ? 'success' : 'danger'" />
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">HTTP 状态</span>
            <strong>{{ activeLog.httpStatus || "-" }}</strong>
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">接入服务</span>
            <strong>{{ activeLog.serviceName || "-" }}</strong>
            <span>{{ activeLog.serviceCode || "-" }}</span>
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">{{ t("web.runtimeClusterSelection.targetCluster") }}</span>
            <strong>{{ clusterLabel(activeLog.requestedClusterId) }}</strong>
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">{{ t("web.runtimeClusterSelection.actualCluster") }}</span>
            <strong>{{ clusterLabel(activeLog.actualClusterId) }}</strong>
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">订阅方</span>
            <strong>{{ activeLog.subscriptionName || "-" }}</strong>
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">请求方法</span>
            <strong>{{ activeLog.requestMethod || "-" }}</strong>
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">耗时</span>
            <strong>{{ formatMs(activeLog.durationMs) }}</strong>
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">接收/成功/失败</span>
            <strong>{{ formatNumber(activeLog.receivedCount) }} / {{ formatNumber(activeLog.successCount) }} / {{ formatNumber(activeLog.failedCount) }}</strong>
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">客户端 IP</span>
            <strong>{{ activeLog.clientIp || "-" }}</strong>
          </div>
          <div class="log-detail-item log-detail-item--wide">
            <span class="log-detail-item__label">User-Agent</span>
            <strong>{{ activeLog.userAgent || "-" }}</strong>
          </div>
        </div>

        <SectionCard title="完整消息" description="列表中已缩略展示，这里保留完整异常摘要或慢调用说明。">
          <pre class="log-detail-message">{{ errorSummary(activeLog) }}</pre>
        </SectionCard>

        <SectionCard title="日志摘要" description="展示列表可用字段生成的调用摘要；完整线程日志通过完整日志按钮按 id 获取。">
          <pre class="log-detail-system-log">{{ summaryLogContent(activeLog) }}</pre>
        </SectionCard>
      </template>
    </el-drawer>
    <InvocationLogDrawer
      v-model="invocationLogVisible"
      domain="data-ingestion-services"
      :access-log-id="activeInvocationLogId"
      title="数据接入服务调用完整日志"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import type {
  DataIngestionAccessLogListView,
  DataIngestionMetricQueryRequest,
  DataServiceMetricOptionsView,
  EntityId,
  RuntimeClusterView,
} from "@studio/api-sdk";
import { SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";
import InvocationLogDrawer from "@/components/InvocationLogDrawer.vue";
import MessagePreviewText from "@/components/MessagePreviewText.vue";
import { formatRuntimeClusterLabel } from "@/utils/runtimeClusters";

type TriState = "" | "true" | "false";
type LogFocus = "ALL" | "ERROR" | "SLOW" | "ERROR_OR_SLOW";

const router = useRouter();
const route = useRoute();
const { t } = useI18n();
const now = new Date();
const timePreset = ref("7d");
const timeRange = ref<[string, string]>([formatDateTimeValue(addDays(now, -7)), formatDateTimeValue(now)]);
const options = reactive<DataServiceMetricOptionsView>({ services: [], subscriptions: [] });
const runtimeClusters = ref<RuntimeClusterView[]>([]);
const filters = reactive<{
  serviceId: EntityId | "";
  subscriptionId: EntityId | "";
  requestedClusterId: EntityId | "";
  actualClusterId: EntityId | "";
  logFocus: LogFocus;
  minDurationMs: number;
  success: TriState;
}>({
  serviceId: route.query.serviceId ? String(route.query.serviceId) : "",
  subscriptionId: "",
  requestedClusterId: "",
  actualClusterId: "",
  logFocus: "ERROR_OR_SLOW",
  minDurationMs: 1000,
  success: "",
});
const pagination = reactive({ page: 1, pageSize: 20 });
const logs = ref<DataIngestionAccessLogListView[]>([]);
const total = ref(0);
const isLoading = ref(false);
const logDetailVisible = ref(false);
const activeLog = ref<DataIngestionAccessLogListView | null>(null);
const invocationLogVisible = ref(false);
const activeInvocationLogId = ref<EntityId | null>(null);

const filteredSubscriptions = computed(() => {
  if (!filters.serviceId) {
    return options.subscriptions;
  }
  return options.subscriptions.filter((item) => String(item.serviceId) === String(filters.serviceId));
});

onMounted(async () => {
  await Promise.all([loadOptions(), loadRuntimeClusters()]);
  await loadLogs();
});

async function loadOptions() {
  const payload = await studioApi.dataIngestionMetrics.options();
  options.services = payload.services || [];
  options.subscriptions = payload.subscriptions || [];
}

async function loadRuntimeClusters() {
  try {
    runtimeClusters.value = await studioApi.runtimeClusters.options();
  } catch {
    runtimeClusters.value = [];
  }
}

async function loadLogs() {
  isLoading.value = true;
  try {
    const page = await studioApi.dataIngestionMetrics.queryAccessLogs(buildQuery({
      pageNo: pagination.page,
      pageSize: pagination.pageSize,
    }));
    logs.value = page.items || [];
    total.value = Number(page.total || 0);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载接入运行日志失败");
  } finally {
    isLoading.value = false;
  }
}

function buildQuery(extra: Partial<DataIngestionMetricQueryRequest> = {}): DataIngestionMetricQueryRequest {
  return {
    startTime: timeRange.value?.[0],
    endTime: timeRange.value?.[1],
    serviceId: filters.serviceId || undefined,
    subscriptionId: filters.subscriptionId || undefined,
    requestedClusterId: filters.requestedClusterId || undefined,
    actualClusterId: filters.actualClusterId || undefined,
    logFocus: filters.logFocus,
    minDurationMs: filters.minDurationMs,
    success: parseTriState(filters.success),
    ...extra,
  };
}

function searchLogs() {
  pagination.page = 1;
  void loadLogs();
}

function resetFilters() {
  filters.serviceId = route.query.serviceId ? String(route.query.serviceId) : "";
  filters.subscriptionId = "";
  filters.requestedClusterId = "";
  filters.actualClusterId = "";
  filters.logFocus = "ERROR_OR_SLOW";
  filters.minDurationMs = 1000;
  filters.success = "";
  changeTimePreset("7d");
  searchLogs();
}

function handlePageSizeChange() {
  pagination.page = 1;
  void loadLogs();
}

function openLogDetail(row: DataIngestionAccessLogListView) {
  activeLog.value = row;
  logDetailVisible.value = true;
}

function openInvocationLog(row: DataIngestionAccessLogListView) {
  activeInvocationLogId.value = row.id || null;
  invocationLogVisible.value = Boolean(activeInvocationLogId.value);
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

function parseTriState(value: TriState) {
  if (value === "true") {
    return true;
  }
  if (value === "false") {
    return false;
  }
  return undefined;
}

function logTypeLabel(row: DataIngestionAccessLogListView) {
  if (!row.success) {
    return "异常";
  }
  if (Number(row.durationMs || 0) >= filters.minDurationMs) {
    return "慢调用";
  }
  return "正常";
}

function logTypeTone(row: DataIngestionAccessLogListView): "success" | "warning" | "neutral" | "primary" | "danger" {
  if (!row.success) {
    return "danger";
  }
  if (Number(row.durationMs || 0) >= filters.minDurationMs) {
    return "warning";
  }
  return "success";
}

function errorSummary(row: DataIngestionAccessLogListView) {
  if (row.errorCode || row.errorMessage) {
    return `${row.errorCode || ""} ${row.errorMessage || ""}`.trim();
  }
  if (Number(row.durationMs || 0) >= filters.minDurationMs) {
    return `慢调用超过 ${filters.minDurationMs}ms`;
  }
  return "-";
}

function summaryLogContent(row: DataIngestionAccessLogListView) {
  return [
    `[requestId] ${row.requestId || "-"}`,
    `[occurredAt] ${formatDateTime(row.occurredAt)}`,
    `[requestMethod] ${row.requestMethod || "-"}`,
    `[serviceCode] ${row.serviceCode || "-"}`,
    `[serviceName] ${row.serviceName || "-"}`,
    `[serviceStatus] ${row.serviceStatus || "-"}`,
    `[subscription] ${row.subscriptionName || "-"}`,
    `[receivedCount] ${formatNumber(row.receivedCount)}`,
    `[successCount] ${formatNumber(row.successCount)}`,
    `[failedCount] ${formatNumber(row.failedCount)}`,
    `[httpStatus] ${row.httpStatus || "-"}`,
    `[result] ${row.success ? "SUCCESS" : "FAILED"}`,
    `[message] ${errorSummary(row)}`,
    `[durationMs] ${Number(row.durationMs || 0)}`,
  ].join("\n");
}

function formatNumber(value?: number | string) {
  return Number(value || 0).toLocaleString();
}

function formatMs(value?: number | string) {
  return `${Number(value || 0).toLocaleString()}ms`;
}

function formatDateTime(value?: string) {
  return value || "-";
}

function clusterLabel(clusterId?: EntityId, clusterCode?: string) {
  return formatRuntimeClusterLabel(runtimeClusters.value, clusterId, clusterCode);
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
.data-ingestion-access-logs-page {
  gap: 14px;
}

.logs-filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 10px;
  align-items: center;
}

.logs-filter-grid > * {
  min-width: 0;
}

.logs-filter-grid :deep(.el-input-number) {
  width: 100%;
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

.logs-filter-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}

.log-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.log-detail-item {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
}

.log-detail-item--wide {
  grid-column: span 2;
}

.log-detail-item__label {
  color: var(--studio-text-muted);
  font-size: 12px;
}

.log-detail-message {
  max-height: 260px;
  margin: 0;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.log-detail-system-log {
  min-height: 220px;
  max-height: 380px;
  margin: 0;
  overflow: auto;
  padding: 12px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: var(--studio-surface-soft, #f7f9fd);
  color: var(--studio-text);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 760px) {
  .logs-filter-grid,
  .log-detail-grid {
    grid-template-columns: 1fr;
  }

  .time-filter-shell,
  .log-detail-item--wide {
    grid-column: span 1;
  }

  .time-filter-shell {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
