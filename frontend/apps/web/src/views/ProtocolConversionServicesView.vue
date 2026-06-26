<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>协议转换服务</h3>
        <p>开放 HTTP 或 SOAP 入口，按字段映射、整报文装载或 Body 直接桥接转发到下游目标接口。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="loadServices">刷新</el-button>
        <el-button type="primary" @click="router.push('/protocol-conversions/new')">新建协议转换</el-button>
      </div>
    </div>

    <SectionCard title="查询条件" description="按服务名称、编码和发布状态筛选。">
      <div class="filter-grid">
        <el-input v-model="filters.keyword" clearable placeholder="服务名称、服务编码或目标数据源" />
        <el-select v-model="filters.status" clearable placeholder="服务状态">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="ONLINE" />
          <el-option label="已下线" value="OFFLINE" />
        </el-select>
        <div class="filter-actions">
          <el-button type="primary" @click="searchServices">查询</el-button>
          <el-button plain @click="resetFilters">重置</el-button>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="服务列表" description="发布后可创建订阅 Token，外部系统调用入口会自动转换并转发。">
      <StudioTableShell min-width="1500px">
        <el-table :data="services" border>
          <el-table-column label="序号" width="76" align="center" header-align="center">
            <template #default="{ $index }">{{ (pagination.page - 1) * pagination.pageSize + $index + 1 }}</template>
          </el-table-column>
          <el-table-column label="服务" min-width="230">
            <template #default="{ row }">
              <div class="table-entity-cell">
                <span class="table-entity-cell__title">{{ row.serviceName }}</span>
                <span class="table-entity-cell__meta">{{ row.serviceCode }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="所属项目" min-width="160">
            <template #default="{ row }">
              <div class="table-entity-cell">
                <span>{{ resolveProjectLabel(row.projectId) }}</span>
                <span class="table-entity-cell__meta">{{ isSharedService(row) ? "共享来源" : "当前项目" }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="协议转换" min-width="230">
            <template #default="{ row }">
              <div class="table-entity-cell">
                <span>{{ protocolLabel(row.sourceProtocol) }} → {{ protocolLabel(row.targetProtocol) }}</span>
                <span class="table-entity-cell__meta">{{ modeLabel(row.conversionMode) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="目标" min-width="260">
            <template #default="{ row }">
              <div class="table-entity-cell">
                <span>{{ row.targetDatasourceName || "-" }}</span>
                <span class="table-entity-cell__meta">{{ row.targetPath || "-" }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="入口地址" min-width="320">
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
          <p>外部调用可在请求头中携带 <code>X-Protocol-Conversion-Token</code>。</p>
        </div>
        <div class="subscription-create">
          <el-input v-model="subscriptionName" placeholder="订阅名称" />
          <el-button type="primary" :loading="creatingSubscription" @click="createSubscription">创建 Token</el-button>
        </div>
      </div>
      <div v-if="newToken" class="token-once-card">
        <div class="token-once-card__header">
          <strong>新 Token 仅展示一次</strong>
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
import type {
  EntityId,
  ProtocolConversionMode,
  ProtocolConversionProtocol,
  ProtocolConversionServiceListView,
  ProtocolConversionSubscriptionView,
} from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { resolveDataServiceOpenUrl, studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { isSharedFromAnotherProject, resolveProjectName } from "@/utils/studio";

const router = useRouter();
const authStore = useAuthStore();
const services = ref<ProtocolConversionServiceListView[]>([]);
const total = ref(0);
const filters = reactive({ keyword: "", status: "" });
const pagination = reactive({ page: 1, pageSize: 10 });
const subscriptionDialogVisible = ref(false);
const selectedService = ref<ProtocolConversionServiceListView | null>(null);
const subscriptions = ref<ProtocolConversionSubscriptionView[]>([]);
const subscriptionName = ref("");
const creatingSubscription = ref(false);
const newToken = ref("");

onMounted(() => {
  void loadServices();
});

async function loadServices() {
  const page = await studioApi.protocolConversions.list({
    pageNo: pagination.page,
    pageSize: pagination.pageSize,
    keyword: filters.keyword || undefined,
    status: filters.status || undefined,
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
  searchServices();
}

function handlePageSizeChange() {
  pagination.page = 1;
  void loadServices();
}

function buildActions(row: ProtocolConversionServiceListView) {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => { void router.push(`/protocol-conversions/${row.id}/edit`); } },
    { key: "debug", label: "调试", onClick: () => { void router.push(`/protocol-conversions/${row.id}/edit?debug=1`); } },
    { key: "accessLogs", label: "调用日志", onClick: () => { void router.push(`/protocol-conversions/access-logs?serviceId=${row.id}`); } },
    { key: "publish", label: row.status === "ONLINE" ? "重新发布" : "发布", onClick: () => publishService(row) },
    { key: "offline", label: "下线", visible: row.status === "ONLINE", onClick: () => offlineService(row) },
    { key: "subscriptions", label: "订阅", onClick: () => openSubscriptions(row) },
    { key: "delete", label: "删除", type: "danger", onClick: () => deleteService(row) },
  ];
}

async function publishService(row: ProtocolConversionServiceListView) {
  await studioApi.protocolConversions.publish(row.id as EntityId);
  ElMessage.success("协议转换服务已发布");
  await loadServices();
}

async function offlineService(row: ProtocolConversionServiceListView) {
  await studioApi.protocolConversions.offline(row.id as EntityId);
  ElMessage.success("协议转换服务已下线");
  await loadServices();
}

async function deleteService(row: ProtocolConversionServiceListView) {
  await ElMessageBox.confirm(`确认删除协议转换服务「${row.serviceName}」？`, "删除确认", { type: "warning" });
  await studioApi.protocolConversions.delete(row.id as EntityId);
  ElMessage.success("协议转换服务已删除");
  await loadServices();
}

async function openSubscriptions(row: ProtocolConversionServiceListView) {
  selectedService.value = row;
  subscriptionName.value = "";
  newToken.value = "";
  subscriptionDialogVisible.value = true;
  subscriptions.value = await studioApi.protocolConversions.listSubscriptions(row.id as EntityId);
}

async function createSubscription() {
  if (!selectedService.value || !subscriptionName.value.trim()) {
    ElMessage.warning("请输入订阅名称");
    return;
  }
  creatingSubscription.value = true;
  try {
    const created = await studioApi.protocolConversions.createSubscription(selectedService.value.id as EntityId, subscriptionName.value.trim());
    newToken.value = created.token || "";
    subscriptionName.value = "";
    subscriptions.value = await studioApi.protocolConversions.listSubscriptions(selectedService.value.id as EntityId);
  } finally {
    creatingSubscription.value = false;
  }
}

async function rotateSubscription(subscriptionId?: EntityId) {
  if (!selectedService.value || !subscriptionId) {
    return;
  }
  await ElMessageBox.confirm("重新生成后旧 Token 会立即失效，是否继续？", "重新生成 Token", { type: "warning" });
  const rotated = await studioApi.protocolConversions.rotateSubscription(selectedService.value.id as EntityId, subscriptionId);
  newToken.value = rotated.token || "";
  subscriptions.value = await studioApi.protocolConversions.listSubscriptions(selectedService.value.id as EntityId);
}

async function disableSubscription(subscriptionId?: EntityId) {
  if (!selectedService.value || !subscriptionId) {
    return;
  }
  await studioApi.protocolConversions.disableSubscription(selectedService.value.id as EntityId, subscriptionId);
  subscriptions.value = await studioApi.protocolConversions.listSubscriptions(selectedService.value.id as EntityId);
}

async function enableSubscription(subscriptionId?: EntityId) {
  if (!selectedService.value || !subscriptionId) {
    return;
  }
  await studioApi.protocolConversions.enableSubscription(selectedService.value.id as EntityId, subscriptionId);
  subscriptions.value = await studioApi.protocolConversions.listSubscriptions(selectedService.value.id as EntityId);
}

function copyNewToken() {
  if (!newToken.value) {
    return;
  }
  void navigator.clipboard?.writeText(newToken.value);
  ElMessage.success("Token 已复制");
}

function resolveEndpoint(row: ProtocolConversionServiceListView) {
  const path = row.sourceProtocol === "SOAP_11" || row.sourceProtocol === "SOAP_12"
    ? row.webserviceEndpointPath
    : row.endpointPath;
  return path ? resolveDataServiceOpenUrl(path) : "-";
}

function statusLabel(status?: string) {
  if (status === "ONLINE") return "已发布";
  if (status === "OFFLINE") return "已下线";
  return "草稿";
}

function statusTone(status?: string) {
  if (status === "ONLINE") return "success";
  if (status === "OFFLINE") return "neutral";
  return "warning";
}

function protocolLabel(protocol?: ProtocolConversionProtocol) {
  const map: Record<string, string> = {
    HTTP_JSON: "HTTP JSON",
    HTTP_XML: "HTTP XML",
    SOAP_11: "SOAP 1.1",
    SOAP_12: "SOAP 1.2",
  };
  return protocol ? map[protocol] ?? protocol : "-";
}

function modeLabel(mode?: ProtocolConversionMode) {
  const map: Record<string, string> = {
    FIELD_MAPPING: "字段映射",
    RAW_MESSAGE_FIELD: "整报文字段",
    BODY_BRIDGE: "Body 直接桥接",
  };
  return mode ? map[mode] ?? mode : "-";
}

function resolveProjectLabel(projectId?: EntityId) {
  return resolveProjectName(authStore.projects, projectId);
}

function isSharedService(row: ProtocolConversionServiceListView) {
  return isSharedFromAnotherProject(authStore.currentProjectId, row.projectId);
}
</script>

<style scoped>
.filter-grid {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 180px auto;
  gap: 12px;
  align-items: center;
}

.filter-actions,
.subscription-create,
.token-once-card__header {
  display: flex;
  gap: 8px;
  align-items: center;
}

.subscription-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.subscription-header p {
  margin: 6px 0 0;
  color: var(--studio-text-muted);
}

.token-once-card {
  padding: 12px;
  margin-bottom: 16px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: var(--studio-surface-muted);
}

.token-once-card__header {
  justify-content: space-between;
  margin-bottom: 10px;
}

@media (max-width: 860px) {
  .filter-grid,
  .subscription-header {
    grid-template-columns: 1fr;
    display: grid;
  }
}
</style>
