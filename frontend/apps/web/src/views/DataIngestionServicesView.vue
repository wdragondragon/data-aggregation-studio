<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>数据接入服务</h3>
        <p>开放写向 API，接收 JSON 或表单数据并同步写入目标模型。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="loadServices">刷新</el-button>
        <el-button type="primary" @click="router.push('/data-ingestion-services/new')">新建接入服务</el-button>
      </div>
    </div>

    <SectionCard title="查询条件" description="按服务名称、编码、状态和目标类型筛选。">
      <div class="ingestion-filter-grid">
        <el-input v-model="filters.keyword" clearable placeholder="服务名称、服务编码、数据源或模型" />
        <el-select v-model="filters.status" clearable placeholder="服务状态">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="ONLINE" />
          <el-option label="已下线" value="OFFLINE" />
        </el-select>
        <el-select v-model="filters.targetType" clearable placeholder="目标类型">
          <el-option label="数据库表" value="DATABASE" />
          <el-option label="文件模型" value="FILE" />
        </el-select>
        <div class="ingestion-filter-actions">
          <el-button type="primary" @click="searchServices">查询</el-button>
          <el-button plain @click="resetFilters">重置</el-button>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="服务列表" description="发布后创建订阅 Token，外部系统即可调用接入地址。">
      <StudioTableShell min-width="1620px">
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
          <el-table-column label="目标" min-width="260">
            <template #default="{ row }">
              <div class="ingestion-target-cell">
                <div>{{ row.datasourceName || "-" }} → {{ row.modelName || "-" }}</div>
                <span>{{ targetTypeLabel(row.targetType) }} · {{ row.modelPhysicalLocator || "-" }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="取值来源" width="180" align="center" header-align="center">
            <template #default="{ row }">{{ sourceSummary(row) }}</template>
          </el-table-column>
          <el-table-column label="接入地址" min-width="320">
            <template #default="{ row }">
              <el-input :model-value="resolveEndpoint(row)" readonly size="small" />
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill :label="statusLabel(row.status)" :tone="statusTone(row.status)" />
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
          <p>外部调用需要在请求头中携带 <code>X-Data-Ingestion-Token</code>。</p>
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
      <StudioTableShell min-width="720px">
        <el-table :data="subscriptions" border>
          <el-table-column prop="subscriptionName" label="订阅名称" min-width="180" />
          <el-table-column prop="tokenMasked" label="Token" min-width="220" />
          <el-table-column label="状态" width="100" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill :label="row.enabled ? '启用' : '停用'" :tone="row.enabled ? 'success' : 'neutral'" />
            </template>
          </el-table-column>
          <el-table-column prop="rotatedAt" label="最近轮换" min-width="180" />
          <el-table-column label="操作" width="190" align="center" header-align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="rotateSubscription(row.id)">重新生成</el-button>
              <el-button v-if="row.enabled" link type="warning" @click="disableSubscription(row.id)">停用</el-button>
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
import { ElMessage, ElMessageBox } from "element-plus";
import type { DataIngestionServiceListView, DataIngestionSubscriptionView, EntityId } from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { resolveDataServiceOpenUrl, studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { isSharedFromAnotherProject, resolveProjectName } from "@/utils/studio";

const router = useRouter();
const authStore = useAuthStore();
const services = ref<DataIngestionServiceListView[]>([]);
const total = ref(0);
const filters = reactive({
  keyword: "",
  status: "",
  targetType: "",
});
const pagination = reactive({
  page: 1,
  pageSize: 10,
});
const subscriptionDialogVisible = ref(false);
const selectedService = ref<DataIngestionServiceListView | null>(null);
const subscriptions = ref<DataIngestionSubscriptionView[]>([]);
const subscriptionName = ref("");
const creatingSubscription = ref(false);
const newToken = ref("");

onMounted(() => {
  void loadServices();
});

async function loadServices() {
  const page = await studioApi.dataIngestionServices.list({
    pageNo: pagination.page,
    pageSize: pagination.pageSize,
    keyword: filters.keyword || undefined,
    status: filters.status || undefined,
    targetType: filters.targetType || undefined,
  });
  services.value = page.items;
  total.value = Number(page.total ?? 0);
}

function searchServices() {
  pagination.page = 1;
  void loadServices();
}

function resetFilters() {
  filters.keyword = "";
  filters.status = "";
  filters.targetType = "";
  searchServices();
}

function handlePageSizeChange() {
  pagination.page = 1;
  void loadServices();
}

function buildActions(row: DataIngestionServiceListView) {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => { void router.push(`/data-ingestion-services/${row.id}/edit`); } },
    { key: "debug", label: "调试", onClick: () => { void router.push(`/data-ingestion-services/${row.id}/edit?debug=1`); } },
    { key: "metrics", label: "监控", onClick: () => { void router.push(`/data-ingestion-metrics?serviceId=${row.id}`); } },
    { key: "accessLogs", label: "调用日志", onClick: () => { void router.push(`/data-ingestion-metrics/access-logs?serviceId=${row.id}`); } },
    { key: "publish", label: row.status === "ONLINE" ? "重新发布" : "发布", onClick: () => publishService(row) },
    { key: "offline", label: "下线", visible: row.status === "ONLINE", onClick: () => offlineService(row) },
    { key: "subscriptions", label: "订阅", onClick: () => openSubscriptions(row) },
    { key: "delete", label: "删除", type: "danger", onClick: () => deleteService(row) },
  ];
}

async function publishService(row: DataIngestionServiceListView) {
  await studioApi.dataIngestionServices.publish(row.id as EntityId);
  await loadServices();
  ElMessage.success("数据接入服务已发布");
}

async function offlineService(row: DataIngestionServiceListView) {
  await studioApi.dataIngestionServices.offline(row.id as EntityId);
  await loadServices();
  ElMessage.success("数据接入服务已下线");
}

async function deleteService(row: DataIngestionServiceListView) {
  await ElMessageBox.confirm(`确认删除接入服务「${row.serviceName}」？`, "删除确认", { type: "warning" });
  await studioApi.dataIngestionServices.delete(row.id as EntityId);
  await reloadCurrentPageAfterDelete();
  ElMessage.success("数据接入服务已删除");
}

async function reloadCurrentPageAfterDelete() {
  const nextTotal = Math.max(0, total.value - 1);
  const maxPage = Math.max(1, Math.ceil(nextTotal / pagination.pageSize));
  pagination.page = Math.min(pagination.page, maxPage);
  await loadServices();
}

async function openSubscriptions(row: DataIngestionServiceListView) {
  selectedService.value = row;
  subscriptionName.value = "";
  newToken.value = "";
  subscriptionDialogVisible.value = true;
  subscriptions.value = await studioApi.dataIngestionServices.listSubscriptions(row.id as EntityId);
}

function upsertSubscriptionRow(next: DataIngestionSubscriptionView) {
  const list = [...subscriptions.value];
  const index = list.findIndex((item) => String(item.id) === String(next.id));
  if (index >= 0) {
    list.splice(index, 1, { ...list[index], ...next });
  } else {
    list.unshift(next);
  }
  subscriptions.value = list.sort(compareSubscriptionRows);
}

function compareSubscriptionRows(left: DataIngestionSubscriptionView, right: DataIngestionSubscriptionView) {
  if (left.enabled !== right.enabled) {
    return left.enabled ? -1 : 1;
  }
  return subscriptionTime(right) - subscriptionTime(left);
}

function subscriptionTime(row: DataIngestionSubscriptionView) {
  const time = Date.parse(row.createdAt || row.updatedAt || "");
  return Number.isNaN(time) ? 0 : time;
}

async function createSubscription() {
  if (!selectedService.value || !subscriptionName.value.trim()) {
    ElMessage.warning("请输入订阅名称");
    return;
  }
  creatingSubscription.value = true;
  try {
    const created = await studioApi.dataIngestionServices.createSubscription(selectedService.value.id as EntityId, subscriptionName.value.trim());
    newToken.value = created.token || "";
    subscriptionName.value = "";
    upsertSubscriptionRow(created);
  } finally {
    creatingSubscription.value = false;
  }
}

async function rotateSubscription(subscriptionId?: EntityId) {
  if (!selectedService.value || !subscriptionId) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      "重新生成后旧 Token 会立即失效，外部调用方必须更新请求头中的 X-Data-Ingestion-Token。是否继续？",
      "重新生成 Token",
      { type: "warning", confirmButtonText: "重新生成", cancelButtonText: "取消" },
    );
    const rotated = await studioApi.dataIngestionServices.rotateSubscription(selectedService.value.id as EntityId, subscriptionId);
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
  if (!selectedService.value || !subscriptionId) {
    return;
  }
  const updated = await studioApi.dataIngestionServices.disableSubscription(selectedService.value.id as EntityId, subscriptionId);
  upsertSubscriptionRow(updated);
}

async function enableSubscription(subscriptionId?: EntityId) {
  if (!selectedService.value || !subscriptionId) {
    return;
  }
  const updated = await studioApi.dataIngestionServices.enableSubscription(selectedService.value.id as EntityId, subscriptionId);
  upsertSubscriptionRow(updated);
}

async function copyNewToken() {
  if (!newToken.value) {
    return;
  }
  try {
    await navigator.clipboard.writeText(newToken.value);
    ElMessage.success("Token 已复制");
  } catch (error) {
    ElMessage.warning("当前浏览器不允许直接复制，请手动复制 Token");
  }
}

function resolveEndpoint(row: DataIngestionServiceListView) {
  return resolveDataServiceOpenUrl(row.endpointPath);
}

function resolveProjectLabel(projectId?: EntityId | null) {
  return resolveProjectName(authStore.projects, projectId);
}

function isSharedService(row: DataIngestionServiceListView) {
  return isSharedFromAnotherProject(authStore.currentProjectId, row.projectId);
}

function statusLabel(status?: string) {
  if (status === "ONLINE") {
    return "已发布";
  }
  if (status === "OFFLINE") {
    return "已下线";
  }
  return "草稿";
}

function statusTone(status?: string) {
  if (status === "ONLINE") {
    return "success";
  }
  if (status === "OFFLINE") {
    return "warning";
  }
  return "neutral";
}

function targetTypeLabel(targetType?: string) {
  return targetType === "FILE" ? "文件模型" : "数据库表";
}

function sourceSummary(row: DataIngestionServiceListView) {
  const labels = new Set<string>();
  const positions = row.sourcePositions ?? [];
  for (const position of positions) {
    if (position === "FORM") {
      labels.add("Form Body");
    } else if (position === "QUERY") {
      labels.add("Query");
    } else if (position === "HEADER") {
      labels.add("Header");
    } else {
      labels.add("JSON Body");
    }
  }
  return labels.size ? Array.from(labels).join(" / ") : "未配置";
}
</script>

<style scoped>
.ingestion-filter-grid {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) 180px 180px auto;
  gap: 12px;
  align-items: center;
}

.ingestion-filter-actions,
.studio-toolbar-actions {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
}

.ingestion-target-cell {
  display: grid;
  gap: 4px;
}

.ingestion-target-cell span,
.table-entity-cell__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.table-entity-cell {
  display: grid;
  gap: 4px;
}

.table-entity-cell__title {
  font-weight: 600;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
}

.subscription-header {
  display: grid;
  grid-template-columns: 1fr minmax(320px, 420px);
  gap: 16px;
  align-items: start;
  margin-bottom: 16px;
}

.subscription-header p {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
}

.subscription-create {
  display: flex;
  gap: 8px;
}

.token-once-card {
  display: grid;
  gap: 10px;
  margin-bottom: 16px;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
}

.token-once-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.token-once-card__header p {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
}

@media (max-width: 960px) {
  .ingestion-filter-grid,
  .subscription-header {
    grid-template-columns: 1fr;
  }
}
</style>
