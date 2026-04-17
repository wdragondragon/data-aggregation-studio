<template>
  <div class="studio-page data-service-access-logs-page">
    <div class="studio-toolbar">
      <div>
        <h3>访问日志</h3>
        <p>优先排查异常查询和慢查询，后台调试调用不会进入这里。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="router.push('/data-service-metrics')">返回监控</el-button>
        <el-button type="primary" :loading="isLoading" @click="loadLogs">刷新</el-button>
      </div>
    </div>

    <SectionCard title="日志筛选" description="默认查看异常或慢查询；慢查询阈值可按排查需要调整。">
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
        <el-select v-model="filters.serviceId" clearable filterable placeholder="全部服务">
          <el-option v-for="item in options.services" :key="String(item.id)" :label="item.label || item.name || String(item.id)" :value="item.id" />
        </el-select>
        <el-select v-model="filters.subscriptionId" clearable filterable placeholder="全部订阅方">
          <el-option v-for="item in filteredSubscriptions" :key="String(item.id)" :label="item.label || item.name || String(item.id)" :value="item.id" />
        </el-select>
        <el-select v-model="filters.logFocus" placeholder="日志类型">
          <el-option label="异常或慢查询" value="ERROR_OR_SLOW" />
          <el-option label="仅异常查询" value="ERROR" />
          <el-option label="仅慢查询" value="SLOW" />
          <el-option label="全部访问" value="ALL" />
        </el-select>
        <el-input-number
          v-model="filters.minDurationMs"
          :min="0"
          :step="100"
          controls-position="right"
          placeholder="慢查询阈值"
        />
        <el-select v-model="filters.success" clearable placeholder="调用结果">
          <el-option label="成功" value="true" />
          <el-option label="失败" value="false" />
        </el-select>
        <el-select v-model="filters.cacheHit" clearable placeholder="缓存状态">
          <el-option label="命中" value="true" />
          <el-option label="未命中（已开启）" value="false" />
        </el-select>
        <div class="logs-filter-actions">
          <el-button type="primary" :loading="isLoading" @click="searchLogs">查询</el-button>
          <el-button plain @click="resetFilters">重置</el-button>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="访问日志明细" description="表格重点展示异常摘要、响应耗时、调用方和服务上下文。">
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
        <el-table-column label="服务" min-width="190">
          <template #default="{ row }">
            <div class="table-entity-cell">
              <span class="table-entity-cell__title">{{ row.serviceName || "-" }}</span>
              <span class="table-entity-cell__meta">{{ row.serviceCode || "-" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="subscriptionName" label="订阅方" min-width="150" />
        <el-table-column prop="requestMethod" label="方法" width="90" align="center" header-align="center" />
        <el-table-column label="缓存" width="90" align="center" header-align="center">
          <template #default="{ row }">{{ cacheLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="返回行数" width="110" align="right" header-align="right">
          <template #default="{ row }">{{ formatNumber(row.rowCount) }}</template>
        </el-table-column>
        <el-table-column prop="clientIp" label="客户端 IP" min-width="140" />
        <el-table-column prop="userAgent" label="User-Agent" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="100" align="center" header-align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openLogDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
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

    <el-drawer v-model="logDetailVisible" title="访问日志详情" size="52%">
      <template v-if="activeLog">
        <div class="log-detail-grid">
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
            <span class="log-detail-item__label">服务</span>
            <strong>{{ activeLog.serviceName || "-" }}</strong>
            <span>{{ activeLog.serviceCode || "-" }}</span>
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
            <span class="log-detail-item__label">缓存状态</span>
            <strong>{{ cacheLabel(activeLog) }}</strong>
          </div>
          <div class="log-detail-item">
            <span class="log-detail-item__label">返回行数</span>
            <strong>{{ formatNumber(activeLog.rowCount) }}</strong>
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

        <SectionCard title="完整消息" description="列表中已缩略展示，这里保留完整异常摘要或慢查询说明。">
          <pre class="log-detail-message">{{ errorSummary(activeLog) }}</pre>
        </SectionCard>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import type {
  DataServiceAccessLogView,
  DataServiceMetricOptionsView,
  DataServiceMetricQueryRequest,
  EntityId,
} from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import MessagePreviewText from "@/components/MessagePreviewText.vue";

type TriState = "" | "true" | "false";
type LogFocus = "ALL" | "ERROR" | "SLOW" | "ERROR_OR_SLOW";

const router = useRouter();
const route = useRoute();
const now = new Date();
const timePreset = ref("7d");
const timeRange = ref<[string, string]>([formatDateTimeValue(addDays(now, -7)), formatDateTimeValue(now)]);
const options = reactive<DataServiceMetricOptionsView>({
  services: [],
  subscriptions: [],
});
const filters = reactive<{
  serviceId: EntityId | "";
  subscriptionId: EntityId | "";
  logFocus: LogFocus;
  minDurationMs: number;
  success: TriState;
  cacheHit: TriState;
}>({
  serviceId: route.query.serviceId ? String(route.query.serviceId) : "",
  subscriptionId: "",
  logFocus: "ERROR_OR_SLOW",
  minDurationMs: 1000,
  success: "",
  cacheHit: "",
});
const pagination = reactive({ page: 1, pageSize: 20 });
const logs = ref<DataServiceAccessLogView[]>([]);
const total = ref(0);
const isLoading = ref(false);
const logDetailVisible = ref(false);
const activeLog = ref<DataServiceAccessLogView | null>(null);

const filteredSubscriptions = computed(() => {
  if (!filters.serviceId) {
    return options.subscriptions;
  }
  return options.subscriptions.filter((item) => String(item.serviceId) === String(filters.serviceId));
});

onMounted(async () => {
  await loadOptions();
  await loadLogs();
});

async function loadOptions() {
  const payload = await studioApi.dataServiceMetrics.options();
  options.services = payload.services || [];
  options.subscriptions = payload.subscriptions || [];
}

async function loadLogs() {
  isLoading.value = true;
  try {
    const page = await studioApi.dataServiceMetrics.queryAccessLogs(buildQuery({
      pageNo: pagination.page,
      pageSize: pagination.pageSize,
    }));
    logs.value = page.items || [];
    total.value = page.total || 0;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载访问日志失败");
  } finally {
    isLoading.value = false;
  }
}

function buildQuery(extra: Partial<DataServiceMetricQueryRequest> = {}): DataServiceMetricQueryRequest {
  return {
    startTime: timeRange.value?.[0],
    endTime: timeRange.value?.[1],
    serviceId: filters.serviceId || undefined,
    subscriptionId: filters.subscriptionId || undefined,
    logFocus: filters.logFocus,
    minDurationMs: filters.minDurationMs,
    success: parseTriState(filters.success),
    cacheHit: parseTriState(filters.cacheHit),
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
  filters.logFocus = "ERROR_OR_SLOW";
  filters.minDurationMs = 1000;
  filters.success = "";
  filters.cacheHit = "";
  changeTimePreset("7d");
  searchLogs();
}

function handlePageSizeChange() {
  pagination.page = 1;
  void loadLogs();
}

function openLogDetail(row: DataServiceAccessLogView) {
  activeLog.value = row;
  logDetailVisible.value = true;
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

function logTypeLabel(row: DataServiceAccessLogView) {
  if (!row.success) {
    return "异常";
  }
  if (Number(row.durationMs || 0) >= filters.minDurationMs) {
    return "慢查询";
  }
  return "正常";
}

function logTypeTone(row: DataServiceAccessLogView): "success" | "warning" | "neutral" | "primary" | "danger" {
  if (!row.success) {
    return "danger";
  }
  if (Number(row.durationMs || 0) >= filters.minDurationMs) {
    return "warning";
  }
  return "success";
}

function errorSummary(row: DataServiceAccessLogView) {
  if (row.errorCode || row.errorMessage) {
    return `${row.errorCode || ""} ${row.errorMessage || ""}`.trim();
  }
  if (Number(row.durationMs || 0) >= filters.minDurationMs) {
    return `慢查询超过 ${filters.minDurationMs}ms`;
  }
  return "-";
}

function cacheLabel(row: DataServiceAccessLogView) {
  if (!row.cacheEnabled) {
    return "未开启";
  }
  return row.cacheHit ? "命中" : "未命中";
}

function formatNumber(value?: number) {
  return Number(value || 0).toLocaleString();
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
.data-service-access-logs-page {
  gap: 14px;
}

.data-service-access-logs-page :deep(.section-card) {
  border-radius: 18px;
}

.data-service-access-logs-page :deep(.section-card__header) {
  padding: 12px 14px 0;
}

.data-service-access-logs-page :deep(.section-card__title) {
  font-size: 15px;
}

.data-service-access-logs-page :deep(.section-card__body) {
  padding: 10px 14px 14px;
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

.logs-filter-grid :deep(.el-select__wrapper),
.logs-filter-grid :deep(.el-input__wrapper),
.logs-filter-grid :deep(.el-date-editor) {
  min-height: 34px;
  height: 34px;
  box-sizing: border-box;
}

.logs-filter-grid :deep(.el-input-number) {
  width: 100%;
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

.logs-filter-actions {
  display: inline-flex;
  gap: 8px;
  justify-self: start;
  white-space: nowrap;
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

.log-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.log-detail-item {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  border-radius: 14px;
  background: rgba(16, 78, 139, 0.04);
}

.log-detail-item--wide {
  grid-column: 1 / -1;
}

.log-detail-item__label {
  font-size: 12px;
  color: var(--studio-text-soft);
}

.log-detail-message {
  margin: 0;
  padding: 14px;
  min-height: 120px;
  max-height: 40vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  border-radius: 14px;
  background: rgba(9, 30, 66, 0.04);
  color: var(--studio-text);
  font: inherit;
  line-height: 1.6;
}

@media (max-width: 1280px) {
  .logs-filter-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .logs-filter-grid {
    grid-template-columns: 1fr;
  }

  .time-filter-shell {
    grid-column: span 1;
  }

  .log-detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
