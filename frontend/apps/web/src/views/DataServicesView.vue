<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>数据服务</h3>
        <p>将模型表或自定义 SELECT 查询发布为可订阅调用的 API。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="loadServices">刷新</el-button>
        <el-button type="primary" @click="router.push('/data-services/new')">新建服务</el-button>
      </div>
    </div>

    <SectionCard title="查询条件" description="按服务名称、编码、状态和服务类型筛选。">
      <div class="service-filter-grid">
        <el-input v-model="filters.keyword" class="service-filter-keyword" clearable placeholder="服务名称、服务编码、数据源或模型" />
        <el-select v-model="filters.status" class="service-filter-select" clearable placeholder="服务状态">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="ONLINE" />
          <el-option label="已下线" value="OFFLINE" />
        </el-select>
        <el-select v-model="filters.serviceType" class="service-filter-select" clearable placeholder="服务类型">
          <el-option label="模型发布" value="MODEL_PUBLISH" />
          <el-option label="服务代理（暂不支持）" value="SERVICE_PROXY" disabled />
        </el-select>
        <div class="service-filter-actions">
          <el-button type="primary" @click="searchServices">查询</el-button>
          <el-button plain @click="resetFilters">重置</el-button>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="服务列表" description="服务发布后，需要创建订阅 Token 才能开放调用。">
      <StudioTableShell min-width="1820px">
        <el-table :data="services" border>
        <el-table-column label="序号" width="76" align="center" header-align="center">
          <template #default="{ $index }">{{ (pagination.page - 1) * pagination.pageSize + $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="服务" min-width="220">
          <template #default="{ row }">
            <div class="table-entity-cell">
              <span class="table-entity-cell__title">{{ row.serviceName }}</span>
              <span class="table-entity-cell__meta">{{ row.serviceCode }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="所属项目" min-width="170">
          <template #default="{ row }">
            <div class="table-entity-cell">
              <span>{{ resolveProjectLabel(row.projectId) }}</span>
              <span class="table-entity-cell__meta">{{ isSharedService(row) ? "共享来源" : "当前项目" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.runtimeClusterSelection.runtimeCluster')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.runtimeClusterName || (row.runtimeClusterId != null ? `#${row.runtimeClusterId}` : t('common.none')) }}</template>
        </el-table-column>
        <el-table-column label="来源" min-width="260">
          <template #default="{ row }">
            <div class="source-cell">
              <div class="source-cell__main">
                <span>{{ row.datasourceName || "-" }}</span>
                <span class="source-cell__arrow">→</span>
                <span>{{ row.sourceType === "SQL" ? "自定义 SQL" : (row.modelName || "-") }}</span>
              </div>
              <div class="source-cell__meta">{{ row.datasourceTypeCode || "-" }} · {{ row.sourceType === "SQL" ? "SQL 语句" : row.modelPhysicalLocator || "-" }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="发布地址" min-width="320">
          <template #default="{ row }">
            <el-input :model-value="resolveEndpoint(row)" readonly size="small" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center" header-align="center">
          <template #default="{ row }">
            <el-tooltip v-if="row.runtimeValid === false" :content="row.runtimeValidationMessage || t('web.runtimeClusterSelection.invalidFallback')" placement="top">
              <span><StatusPill :label="t('web.runtimeClusterSelection.invalid')" tone="danger" /></span>
            </el-tooltip>
            <StatusPill v-else :label="resolveStatusLabel(row.status)" :tone="resolveStatusTone(row.status)" />
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="180" />
        <el-table-column prop="updatedAt" label="更新时间" min-width="180" />
        <el-table-column label="操作" width="190" align="center" header-align="center" fixed="right">
          <template #default="{ row }">
            <OverflowActionGroup :items="buildActions(row)" />
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
          @current-change="loadServices"
          @size-change="handlePageSizeChange"
        />
      </div>
    </SectionCard>

    <el-dialog v-model="subscriptionDialogVisible" title="订阅 Token 管理" width="760px">
      <div class="subscription-header">
        <div>
          <strong>{{ selectedService?.serviceName }}</strong>
          <p>外部调用需要在请求头中携带 <code>X-Data-Service-Token</code>。</p>
        </div>
        <div class="subscription-create">
          <el-input v-model="subscriptionName" placeholder="订阅名称" />
          <el-button type="primary" :loading="creatingSubscription" @click="createSubscription">创建 Token</el-button>
        </div>
      </div>
      <div v-if="newToken" class="token-once-card">
        <div class="token-once-card__header">
          <div>
            <strong>新 Token 仅展示一次</strong>
            <p>请立即记录或复制，关闭弹窗后将只能看到脱敏信息。</p>
          </div>
          <el-button plain @click="copyNewToken">复制 Token</el-button>
        </div>
        <el-input :model-value="newToken" readonly />
      </div>
      <StudioTableShell min-width="760px">
        <el-table :data="subscriptions" border>
        <el-table-column prop="subscriptionName" label="订阅名称" min-width="180" />
        <el-table-column prop="tokenMasked" label="Token" min-width="220" />
        <el-table-column label="状态" width="100" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="row.enabled ? '启用' : '停用'" :tone="row.enabled ? 'success' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column prop="lastUsedAt" label="最近使用" min-width="180" />
        <el-table-column prop="rotatedAt" label="最近轮换" min-width="180" />
        <el-table-column label="操作" width="190" align="center" header-align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="rotateSubscription(row.id)">重新生成</el-button>
            <el-button v-if="row.enabled" link type="danger" @click="disableSubscription(row.id)">停用</el-button>
            <el-button v-else link type="primary" @click="enableSubscription(row.id)">启用</el-button>
          </template>
        </el-table-column>
        </el-table>
      </StudioTableShell>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import type { DataServiceListView, DataServiceSubscriptionView, EntityId } from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { resolveDataServiceOpenUrl, studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { isSharedFromAnotherProject, resolveProjectName } from "@/utils/studio";

const router = useRouter();
const { t } = useI18n();
const authStore = useAuthStore();

const filters = reactive({
  keyword: "",
  status: "",
  serviceType: "",
});
const pagination = reactive({
  page: 1,
  pageSize: 10,
});
const services = ref<DataServiceListView[]>([]);
const total = ref(0);
const subscriptionDialogVisible = ref(false);
const selectedService = ref<DataServiceListView | null>(null);
const subscriptions = ref<DataServiceSubscriptionView[]>([]);
const subscriptionName = ref("");
const newToken = ref("");
const creatingSubscription = ref(false);

async function loadServices() {
  try {
    const page = await studioApi.dataServices.list({
      pageNo: pagination.page,
      pageSize: pagination.pageSize,
      keyword: filters.keyword.trim() || undefined,
      status: filters.status || undefined,
      serviceType: filters.serviceType || undefined,
    });
    services.value = page.items;
    total.value = page.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载数据服务失败");
  }
}

async function searchServices() {
  pagination.page = 1;
  await loadServices();
}

function resetFilters() {
  filters.keyword = "";
  filters.status = "";
  filters.serviceType = "";
  pagination.page = 1;
  void loadServices();
}

function handlePageSizeChange() {
  pagination.page = 1;
  void loadServices();
}

function buildActions(row: DataServiceListView) {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => { void router.push(`/data-services/${row.id}/edit`); } },
    { key: "debug", label: "调试", disabled: row.runtimeValid === false, onClick: () => { void router.push(`/data-services/${row.id}/edit?debug=1`); } },
    { key: "metrics", label: "监控", onClick: () => { void router.push(`/data-service-metrics?serviceId=${row.id}`); } },
    { key: "accessLogs", label: "调用日志", onClick: () => { void router.push(`/data-service-metrics/access-logs?serviceId=${row.id}`); } },
    { key: "publish", label: row.status === "ONLINE" ? "重新发布" : "发布", disabled: row.runtimeValid === false, onClick: () => publishService(row) },
    { key: "offline", label: "下线", visible: row.status === "ONLINE", onClick: () => offlineService(row) },
    { key: "subscriptions", label: "订阅", onClick: () => openSubscriptions(row) },
    { key: "delete", label: "删除", type: "danger", onClick: () => deleteService(row) },
  ];
}

async function publishService(row: DataServiceListView) {
  if (!row.id) {
    return;
  }
  try {
    await studioApi.dataServices.publishSummary(row.id);
    await loadServices();
    ElMessage.success("数据服务已发布");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "发布失败");
  }
}

async function offlineService(row: DataServiceListView) {
  if (!row.id) {
    return;
  }
  try {
    await studioApi.dataServices.offlineSummary(row.id);
    await loadServices();
    ElMessage.success("数据服务已下线");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "下线失败");
  }
}

async function deleteService(row: DataServiceListView) {
  if (!row.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除数据服务“${row.serviceName}”吗？`, "提示", { type: "warning" });
    await studioApi.dataServices.delete(row.id);
    await reloadCurrentPageAfterDelete();
    ElMessage.success("数据服务已删除");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除失败");
    }
  }
}

async function reloadCurrentPageAfterDelete() {
  const nextTotal = Math.max(0, total.value - 1);
  const maxPage = Math.max(1, Math.ceil(nextTotal / pagination.pageSize));
  pagination.page = Math.min(pagination.page, maxPage);
  await loadServices();
}

async function openSubscriptions(row: DataServiceListView) {
  selectedService.value = row;
  subscriptionDialogVisible.value = true;
  subscriptionName.value = "";
  newToken.value = "";
  await loadSubscriptions();
}

async function loadSubscriptions() {
  if (!selectedService.value?.id) {
    subscriptions.value = [];
    return;
  }
  subscriptions.value = await studioApi.dataServices.listSubscriptions(selectedService.value.id);
}

function upsertSubscriptionRow(next: DataServiceSubscriptionView) {
  const list = [...subscriptions.value];
  const index = list.findIndex((item) => String(item.id) === String(next.id));
  if (index >= 0) {
    list.splice(index, 1, { ...list[index], ...next });
  } else {
    list.unshift(next);
  }
  subscriptions.value = list.sort(compareSubscriptionRows);
}

function compareSubscriptionRows(left: DataServiceSubscriptionView, right: DataServiceSubscriptionView) {
  if (left.enabled !== right.enabled) {
    return left.enabled ? -1 : 1;
  }
  return subscriptionTime(right) - subscriptionTime(left);
}

function subscriptionTime(row: DataServiceSubscriptionView) {
  const time = Date.parse(row.createdAt || row.updatedAt || "");
  return Number.isNaN(time) ? 0 : time;
}

async function createSubscription() {
  if (!selectedService.value?.id || !subscriptionName.value.trim()) {
    ElMessage.warning("请填写订阅名称");
    return;
  }
  creatingSubscription.value = true;
  try {
    const created = await studioApi.dataServices.createSubscription(selectedService.value.id, subscriptionName.value.trim());
    newToken.value = created.token || "";
    subscriptionName.value = "";
    if (!newToken.value) {
      ElMessage.warning("订阅已创建，但接口未返回明文 Token；请联系管理员检查服务端返回。");
    }
    upsertSubscriptionRow(created);
    ElMessage.success("订阅 Token 已创建或刷新");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "创建订阅 Token 失败");
  } finally {
    creatingSubscription.value = false;
  }
}

async function rotateSubscription(subscriptionId?: EntityId) {
  if (!selectedService.value?.id || !subscriptionId) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      "重新生成后旧 Token 会立即失效，外部调用方必须更新请求头中的 X-Data-Service-Token。是否继续？",
      "重新生成 Token",
      { type: "warning", confirmButtonText: "重新生成", cancelButtonText: "取消" },
    );
    const rotated = await studioApi.dataServices.rotateSubscription(selectedService.value.id, subscriptionId);
    newToken.value = rotated.token || "";
    if (!newToken.value) {
      ElMessage.warning("Token 已重新生成，但接口未返回明文 Token；请联系管理员检查服务端返回。");
    }
    upsertSubscriptionRow(rotated);
    ElMessage.success("订阅 Token 已重新生成，请立即复制保存");
  } catch (error) {
    if (error === "cancel" || error === "close") {
      return;
    }
    ElMessage.error(error instanceof Error ? error.message : "重新生成订阅 Token 失败");
  }
}

async function disableSubscription(subscriptionId?: EntityId) {
  if (!selectedService.value?.id || !subscriptionId) {
    return;
  }
  try {
    const updated = await studioApi.dataServices.disableSubscription(selectedService.value.id, subscriptionId);
    upsertSubscriptionRow(updated);
    ElMessage.success("订阅 Token 已停用");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "停用订阅 Token 失败");
  }
}

async function enableSubscription(subscriptionId?: EntityId) {
  if (!selectedService.value?.id || !subscriptionId) {
    return;
  }
  try {
    const updated = await studioApi.dataServices.enableSubscription(selectedService.value.id, subscriptionId);
    upsertSubscriptionRow(updated);
    ElMessage.success("订阅 Token 已启用");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "启用订阅 Token 失败");
  }
}

async function copyNewToken() {
  if (!newToken.value) {
    return;
  }
  try {
    await navigator.clipboard.writeText(newToken.value);
    ElMessage.success("Token 已复制");
  } catch {
    fallbackCopyText(newToken.value);
  }
}

function fallbackCopyText(value: string) {
  const textarea = document.createElement("textarea");
  textarea.value = value;
  textarea.setAttribute("readonly", "true");
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  document.body.appendChild(textarea);
  textarea.select();
  document.execCommand("copy");
  document.body.removeChild(textarea);
  ElMessage.success("Token 已复制");
}

function resolveEndpoint(row: DataServiceListView) {
  if (!row.endpointPath) {
    return "-";
  }
  return resolveDataServiceOpenUrl(row.endpointPath);
}

function resolveProjectLabel(projectId?: EntityId | null) {
  return resolveProjectName(authStore.projects, projectId);
}

function isSharedService(row: DataServiceListView) {
  return isSharedFromAnotherProject(authStore.currentProjectId, row.projectId);
}

function resolveStatusLabel(status?: string) {
  if (status === "ONLINE") {
    return "已发布";
  }
  if (status === "OFFLINE") {
    return "已下线";
  }
  return "草稿";
}

function resolveStatusTone(status?: string) {
  if (status === "ONLINE") {
    return "success";
  }
  if (status === "OFFLINE") {
    return "neutral";
  }
  return "warning";
}

onMounted(loadServices);
</script>

<style scoped>
.service-filter-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.service-filter-keyword {
  flex: 0 1 360px;
}

.service-filter-select {
  width: 180px;
}

.service-filter-actions {
  display: inline-flex;
  gap: 8px;
}

.source-cell {
  display: grid;
  gap: 4px;
}

.source-cell__main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-weight: 700;
}

.source-cell__arrow {
  color: var(--studio-text-soft);
}

.source-cell__meta {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.subscription-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.subscription-header p {
  margin: 6px 0 0;
  color: var(--studio-text-soft);
}

.subscription-create {
  display: flex;
  gap: 8px;
  min-width: 340px;
}

.token-once-card {
  display: grid;
  gap: 10px;
  margin-bottom: 14px;
  padding: 14px;
  border: 1px solid rgba(22, 163, 74, 0.24);
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(22, 163, 74, 0.08), rgba(255, 255, 255, 0.92));
}

.token-once-card__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.token-once-card__header p {
  margin: 4px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
}
</style>
