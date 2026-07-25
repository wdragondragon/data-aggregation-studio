<template>
  <div class="studio-page runtime-cluster-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.runtimeClusters.heading") }}</h3>
        <p>{{ t("web.runtimeClusters.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="refreshAll">{{ t("common.refresh") }}</el-button>
        <el-button type="primary" @click="openClusterDialog()">{{ t("web.runtimeClusters.createCluster") }}</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane :label="t('web.runtimeClusters.clusterTitle')" name="clusters">
        <SectionCard :title="t('web.runtimeClusters.clusterTitle')" :description="t('web.runtimeClusters.clusterDescription')">
          <StudioTableShell min-width="1120px">
            <el-table v-loading="clustersLoading" :data="clusters" border>
              <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
                <template #default="{ $index }">{{ $index + 1 }}</template>
              </el-table-column>
              <el-table-column prop="name" :label="t('web.runtimeClusters.clusterName')" min-width="180" />
              <el-table-column prop="code" :label="t('web.runtimeClusters.clusterCode')" min-width="160" />
              <el-table-column :label="t('web.runtimeClusters.clusterStatus')" width="110" align="center" header-align="center">
                <template #default="{ row }"><StatusPill :label="clusterStatusLabel(row.status)" :tone="clusterStatusTone(row.status)" /></template>
              </el-table-column>
              <el-table-column prop="version" :label="t('web.runtimeClusters.clusterVersion')" min-width="120" />
              <el-table-column :label="t('web.runtimeClusters.instances')" width="120" align="center" header-align="center">
                <template #default="{ row }">
                  <el-button link type="primary" :disabled="row.id == null" @click="openInstances(row)">
                    {{ row.onlineInstanceCount ?? 0 }}/{{ row.instances?.length ?? 0 }}
                  </el-button>
                </template>
              </el-table-column>
              <el-table-column :label="t('web.runtimeClusters.lastHeartbeatAt')" min-width="180">
                <template #default="{ row }">{{ row.lastHeartbeatAt || "--" }}</template>
              </el-table-column>
              <el-table-column :label="t('web.runtimeClusters.enabled')" width="100" align="center" header-align="center">
                <template #default="{ row }"><StatusPill :label="row.enabled ? t('common.on') : t('common.off')" :tone="row.enabled ? 'success' : 'neutral'" /></template>
              </el-table-column>
              <el-table-column :label="t('common.actions')" width="210" fixed="right" align="center" header-align="center">
                <template #default="{ row }"><OverflowActionGroup :items="clusterActions(row)" /></template>
              </el-table-column>
            </el-table>
          </StudioTableShell>
        </SectionCard>
      </el-tab-pane>

      <el-tab-pane :label="t('web.runtimeClusters.projectAuthorization')" name="authorizations">
        <SectionCard :title="t('web.runtimeClusters.projectAuthorization')" :description="t('web.runtimeClusters.projectAuthorizationDescription')">
          <div class="authorization-toolbar">
            <el-select v-model="authorizationProjectId" filterable :placeholder="t('web.runtimeClusters.selectProject')" @change="loadAuthorizations">
              <el-option v-for="project in authStore.projects" :key="project.projectId" :label="project.projectName" :value="String(project.projectId)" />
            </el-select>
          </div>
          <StudioTableShell min-width="920px">
            <el-table v-loading="authorizationsLoading" :data="authorizationRows" border>
              <el-table-column prop="name" :label="t('web.runtimeClusters.clusterName')" min-width="200" />
              <el-table-column prop="code" :label="t('web.runtimeClusters.clusterCode')" min-width="160" />
              <el-table-column :label="t('web.runtimeClusters.clusterStatus')" width="120" align="center" header-align="center">
                <template #default="{ row }"><StatusPill :label="row.clusterEnabled ? t('common.on') : t('common.off')" :tone="row.clusterEnabled ? 'success' : 'neutral'" /></template>
              </el-table-column>
              <el-table-column :label="t('web.runtimeClusters.authorized')" width="130" align="center" header-align="center">
                <template #default="{ row }"><el-switch v-model="row.authorized" :disabled="!row.clusterEnabled" /></template>
              </el-table-column>
              <el-table-column :label="t('web.runtimeClusters.preferred')" width="130" align="center" header-align="center">
                <template #default="{ row }"><el-radio v-model="preferredClusterId" :label="String(row.id)" :disabled="!row.clusterEnabled || !row.authorized">{{ t("web.runtimeClusters.preferred") }}</el-radio></template>
              </el-table-column>
              <el-table-column :label="t('web.runtimeClusters.manualOverride')" width="170" align="center" header-align="center">
                <template #default="{ row }"><el-switch v-model="row.allowManualOverride" :disabled="!row.clusterEnabled || !row.authorized" /></template>
              </el-table-column>
              <el-table-column :label="t('common.actions')" width="120" align="center" header-align="center">
                <template #default="{ row }"><el-button link type="primary" :disabled="!authorizationProjectId" @click="saveAuthorization(row)">{{ t("web.runtimeClusters.saveAuthorization") }}</el-button></template>
              </el-table-column>
            </el-table>
          </StudioTableShell>
        </SectionCard>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="clusterDialogVisible" :title="clusterForm.id ? t('web.runtimeClusters.editCluster') : t('web.runtimeClusters.createCluster')" width="min(620px, 94vw)">
      <div class="studio-form-grid">
        <el-form-item :label="t('web.runtimeClusters.clusterName')"><el-input v-model="clusterForm.name" /></el-form-item>
        <el-form-item :label="t('web.runtimeClusters.clusterCode')">
          <el-input v-model="clusterForm.code" :disabled="Boolean(clusterForm.id)" />
          <p class="form-item-hint">{{ t("web.runtimeClusters.clusterCodeHint") }}</p>
        </el-form-item>
        <el-form-item :label="t('web.runtimeClusters.clusterVersion')"><el-input v-model="clusterForm.version" /></el-form-item>
        <el-form-item :label="t('web.runtimeClusters.enabled')"><el-switch v-model="clusterForm.enabled" /></el-form-item>
      </div>
      <template #footer><el-button @click="clusterDialogVisible = false">{{ t("common.cancel") }}</el-button><el-button type="primary" :loading="clusterSaving" @click="saveCluster">{{ t("common.save") }}</el-button></template>
    </el-dialog>

    <el-drawer v-model="instanceDrawerVisible" size="min(720px, 94vw)" :title="instanceCluster ? `${t('web.runtimeClusters.instanceTitle')} / ${instanceCluster.name}` : t('web.runtimeClusters.instanceTitle')">
      <p class="drawer-description">{{ t("web.runtimeClusters.instanceDescription") }}</p>
      <div class="drawer-toolbar"><el-button plain :loading="instancesLoading" @click="loadInstances">{{ t("common.refresh") }}</el-button></div>
      <StudioTableShell min-width="920px">
        <el-table v-loading="instancesLoading" :data="instances" border>
          <el-table-column prop="instanceId" :label="t('web.runtimeClusters.instanceId')" min-width="180" show-overflow-tooltip />
          <el-table-column prop="workerGroupCode" :label="t('web.runtimeClusters.workerGroupCode')" min-width="150" show-overflow-tooltip />
          <el-table-column prop="version" :label="t('web.runtimeClusters.clusterVersion')" min-width="120" />
          <el-table-column :label="t('web.runtimeClusters.instanceStatus')" width="100" align="center" header-align="center">
            <template #default="{ row }"><StatusPill :label="clusterStatusLabel(row.status)" :tone="clusterStatusTone(row.status)" /></template>
          </el-table-column>
          <el-table-column :label="t('web.runtimeClusters.lastHeartbeatAt')" min-width="180"><template #default="{ row }">{{ row.heartbeatAt || "--" }}</template></el-table-column>
          <el-table-column prop="summary" :label="t('web.runtimeClusters.instanceSummary')" min-width="180" show-overflow-tooltip />
        </el-table>
      </StudioTableShell>
    </el-drawer>

    <el-drawer v-model="endpointDrawerVisible" size="min(680px, 94vw)" :title="endpointCluster ? `${t('web.runtimeClusters.endpoints')} / ${endpointCluster.name}` : t('web.runtimeClusters.endpoints')">
      <p class="drawer-description">{{ t("web.runtimeClusters.endpointDescription") }}</p>
      <div class="drawer-toolbar"><el-button type="primary" :disabled="!endpointCluster?.id" @click="openEndpointDialog()">{{ t("web.runtimeClusters.addEndpoint") }}</el-button></div>
      <StudioTableShell min-width="900px">
        <el-table v-loading="endpointsLoading" :data="endpoints" border>
          <el-table-column :label="t('web.runtimeClusters.endpointMode')" width="130">
            <template #default="{ row }">
              <el-tag
                size="small"
                effect="plain"
                :type="endpointModeTone(row)"
                :title="isLegacyLocalEndpoint(row) ? t('web.runtimeClusters.legacyEndpointHint') : ''"
              >
                {{ endpointModeLabel(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="endpointMasked" :label="t('web.runtimeClusters.endpointUrl')" min-width="190" show-overflow-tooltip />
          <el-table-column :label="t('web.runtimeClusters.secretSummary')" min-width="220">
            <template #default="{ row }">
              <div class="endpoint-secret-summary">
                <el-tag v-if="row.hasToken" size="small" effect="plain">{{ t("web.runtimeClusters.savedToken") }}</el-tag>
                <el-tag v-for="header in row.headerNames || []" :key="header" size="small" effect="plain">Header: {{ header }}</el-tag>
                <span v-if="!row.hasToken && !row.headerNames?.length" class="form-item-hint">{{ t("common.none") }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.runtimeClusters.enabled')" width="90" align="center" header-align="center"><template #default="{ row }"><StatusPill :label="row.enabled ? t('common.on') : t('common.off')" :tone="row.enabled ? 'success' : 'neutral'" /></template></el-table-column>
          <el-table-column :label="t('web.runtimeClusters.lastTest')" min-width="210">
            <template #default="{ row }">
              <div class="endpoint-test-summary">
                <StatusPill
                  v-if="row.lastTestStatus"
                  :label="endpointTestStatusLabel(row.lastTestStatus)"
                  :tone="endpointTestStatusTone(row.lastTestStatus)"
                />
                <span>{{ row.lastTestedAt || "--" }}</span>
                <span v-if="row.lastTestMessage" class="form-item-hint endpoint-test-message" :title="row.lastTestMessage">{{ row.lastTestMessage }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="140" fixed="right"><template #default="{ row }"><OverflowActionGroup :items="endpointActions(row)" /></template></el-table-column>
        </el-table>
      </StudioTableShell>
    </el-drawer>

    <el-dialog v-model="endpointDialogVisible" :title="endpointForm.id ? t('web.runtimeClusters.editEndpoint') : t('web.runtimeClusters.addEndpoint')" width="min(680px, 94vw)">
      <div class="studio-form-grid">
        <el-form-item :label="t('web.runtimeClusters.endpointMode')"><el-input model-value="HTTP" disabled /></el-form-item>
        <el-form-item :label="t('web.runtimeClusters.enabled')"><el-switch v-model="endpointForm.enabled" /></el-form-item>
        <el-form-item :label="t('web.runtimeClusters.endpointUrl')" class="span-2" required><el-input v-model="endpointForm.endpointUrl" placeholder="https://runtime.internal" /><p class="form-item-hint">{{ endpointForm.id && editingEndpoint?.endpointMasked ? t("web.runtimeClusters.savedEndpointSummary", { value: editingEndpoint.endpointMasked }) : t("web.runtimeClusters.endpointUrlHint") }}</p></el-form-item>
        <el-form-item :label="t('web.runtimeClusters.endpointHeaders')" class="span-2"><el-input v-model="endpointHeadersText" type="textarea" :rows="4" placeholder='{"X-Internal-Token":"..."}' /><p class="form-item-hint">{{ endpointForm.id && editingEndpoint?.headerNames?.length ? t("web.runtimeClusters.savedHeadersSummary", { value: editingEndpoint.headerNames.join(", ") }) : t("web.runtimeClusters.endpointHeadersHint") }}</p></el-form-item>
        <el-form-item :label="t('web.runtimeClusters.endpointToken')"><el-input v-model="endpointForm.token" type="password" show-password autocomplete="new-password" /><p v-if="endpointForm.id && editingEndpoint?.hasToken" class="form-item-hint">{{ t("web.runtimeClusters.savedTokenHint") }}</p></el-form-item>
        <el-form-item :label="t('web.runtimeClusters.clearToken')"><el-switch v-model="endpointForm.clearToken" :disabled="!editingEndpoint?.hasToken" /></el-form-item>
        <el-form-item :label="t('web.runtimeClusters.connectTimeout')"><el-input-number v-model="endpointForm.connectTimeoutMillis" :min="100" :max="60000" controls-position="right" /></el-form-item>
        <el-form-item :label="t('web.runtimeClusters.readTimeout')"><el-input-number v-model="endpointForm.readTimeoutMillis" :min="100" :max="60000" controls-position="right" /></el-form-item>
      </div>
      <template #footer><el-button @click="endpointDialogVisible = false">{{ t("common.cancel") }}</el-button><el-button type="primary" :loading="endpointSaving" @click="saveEndpoint">{{ t("common.save") }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { EntityId, RuntimeClusterInstanceView, RuntimeClusterView, RuntimeEndpointView } from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";

interface AuthorizationRow extends RuntimeClusterView {
  clusterEnabled: boolean;
  authorized: boolean;
  allowManualOverride: boolean;
}

const { t } = useI18n();
const authStore = useAuthStore();
const activeTab = ref("clusters");
const clusters = ref<RuntimeClusterView[]>([]);
const clustersLoading = ref(false);
const clusterDialogVisible = ref(false);
const clusterSaving = ref(false);
const clusterForm = reactive({ id: undefined as EntityId | undefined, code: "", name: "", version: "", enabled: true });
const instanceDrawerVisible = ref(false);
const instanceCluster = ref<RuntimeClusterView | null>(null);
const instances = ref<RuntimeClusterInstanceView[]>([]);
const instancesLoading = ref(false);
const endpointDrawerVisible = ref(false);
const endpointCluster = ref<RuntimeClusterView | null>(null);
const endpoints = ref<RuntimeEndpointView[]>([]);
const endpointsLoading = ref(false);
const endpointDialogVisible = ref(false);
const endpointSaving = ref(false);
const endpointHeadersText = ref("");
const editingEndpoint = ref<RuntimeEndpointView | null>(null);
const endpointForm = reactive({ id: undefined as EntityId | undefined, runtimeClusterId: undefined as EntityId | undefined, mode: "HTTP" as const, endpointUrl: "", token: "", clearToken: false, connectTimeoutMillis: 3000, readTimeoutMillis: 5000, enabled: true });
const authorizationProjectId = ref("");
const authorizationsLoading = ref(false);
const authorizationRows = ref<AuthorizationRow[]>([]);
const preferredClusterId = ref("");
let refreshTimer: number | undefined;
let clustersLoadSequence = 0;
let instancesLoadSequence = 0;
let endpointsLoadSequence = 0;
let authorizationsLoadSequence = 0;

const endpointClusterId = computed(() => endpointCluster.value?.id);

function clusterStatusLabel(status?: string) {
  const normalized = String(status || "").toUpperCase();
  if (normalized === "ONLINE") return t("web.runtimeClusters.statusOnline");
  if (normalized === "OFFLINE") return t("web.runtimeClusters.statusOffline");
  return t("web.runtimeClusters.statusUnknown");
}

function clusterStatusTone(status?: string) {
  const normalized = String(status || "").toUpperCase();
  return normalized === "ONLINE" ? "success" : normalized === "OFFLINE" ? "danger" : "warning";
}

function endpointTestStatusLabel(status?: string) {
  const normalized = String(status || "").toUpperCase();
  if (normalized === "SUCCESS") return t("common.statusSuccess");
  if (normalized === "FAILED") return t("common.statusFailed");
  if (normalized === "DISABLED") return t("common.off");
  return t("common.unknown");
}

function endpointTestStatusTone(status?: string) {
  const normalized = String(status || "").toUpperCase();
  if (normalized === "SUCCESS") return "success";
  if (normalized === "FAILED") return "danger";
  return normalized === "DISABLED" ? "neutral" : "warning";
}

function isLegacyLocalEndpoint(endpoint?: RuntimeEndpointView | null) {
  return String(endpoint?.mode || "").toUpperCase() === "LOCAL";
}

function endpointModeLabel(endpoint?: RuntimeEndpointView | null) {
  const normalized = String(endpoint?.mode || "").toUpperCase();
  if (normalized === "LOCAL") return t("web.runtimeClusters.legacyEndpointMode");
  return normalized || "--";
}

function endpointModeTone(endpoint?: RuntimeEndpointView | null) {
  const normalized = String(endpoint?.mode || "").toUpperCase();
  return normalized === "HTTP" ? "success" : normalized === "LOCAL" ? "warning" : "danger";
}

function errorMessage(error: unknown, fallback: string) {
  const candidate = error as { response?: { data?: { message?: string } }; message?: string };
  return candidate?.response?.data?.message || candidate?.message || fallback;
}

async function loadClusters() {
  const sequence = ++clustersLoadSequence;
  clustersLoading.value = true;
  try {
    const result = await studioApi.runtimeClusters.list();
    if (sequence === clustersLoadSequence) {
      clusters.value = result;
      if (authorizationRows.value.length) {
        const clusterById = new Map(result.map((cluster) => [String(cluster.id), cluster]));
        authorizationRows.value = authorizationRows.value.map((row) => {
          const cluster = clusterById.get(String(row.id));
          return cluster
            ? { ...row, ...cluster, clusterEnabled: cluster.enabled !== false }
            : row;
        });
      }
    }
  } catch (error) {
    if (sequence === clustersLoadSequence) {
      ElMessage.error(errorMessage(error, t("web.runtimeClusters.clusterLoadFailed")));
    }
  } finally {
    if (sequence === clustersLoadSequence) {
      clustersLoading.value = false;
    }
  }
}

async function refreshAll() {
  await loadClusters();
  if (instanceDrawerVisible.value) await loadInstances();
  if (endpointClusterId.value != null) await loadEndpoints(endpointClusterId.value);
  if (authorizationProjectId.value) await loadAuthorizations();
}

async function refreshRuntimeStatus() {
  await loadClusters();
  if (instanceDrawerVisible.value) await loadInstances();
  if (endpointClusterId.value != null) await loadEndpoints(endpointClusterId.value);
}

function openClusterDialog(cluster?: RuntimeClusterView) {
  clusterForm.id = cluster?.id;
  clusterForm.code = cluster?.code || "";
  clusterForm.name = cluster?.name || "";
  clusterForm.version = cluster?.version || "";
  clusterForm.enabled = cluster?.enabled !== false;
  clusterDialogVisible.value = true;
}

async function saveCluster() {
  if (!clusterForm.name.trim() || !clusterForm.code.trim()) return;
  clusterSaving.value = true;
  try {
    await studioApi.runtimeClusters.save({ ...clusterForm, name: clusterForm.name.trim(), code: clusterForm.code.trim(), version: clusterForm.version.trim() || undefined });
    ElMessage.success(t("web.runtimeClusters.clusterSaved"));
    clusterDialogVisible.value = false;
    await loadClusters();
  } catch (error) {
    ElMessage.error(errorMessage(error, t("web.runtimeClusters.clusterSaveFailed")));
  } finally {
    clusterSaving.value = false;
  }
}

function clusterActions(cluster: RuntimeClusterView) {
  return [
    { key: "edit", label: t("common.edit"), type: "primary", onClick: () => openClusterDialog(cluster) },
    { key: "instances", label: t("web.runtimeClusters.viewInstances"), type: "info", onClick: () => openInstances(cluster) },
    { key: "endpoint", label: t("web.runtimeClusters.manageEndpoints"), type: "success", onClick: () => openEndpoints(cluster) },
    { key: "toggle", label: cluster.enabled ? t("common.disable") : t("common.enable"), type: cluster.enabled ? "warning" : "success", onClick: () => toggleCluster(cluster) },
    { key: "delete", label: t("common.delete"), type: "danger", disabled: cluster.id == null, onClick: () => deleteCluster(cluster) },
  ];
}

async function toggleCluster(cluster: RuntimeClusterView) {
  if (cluster.id == null) return;
  try {
    if (cluster.enabled) await studioApi.runtimeClusters.disable(cluster.id);
    else await studioApi.runtimeClusters.enable(cluster.id);
    await loadClusters();
  } catch (error) {
    ElMessage.error(errorMessage(error, t("web.runtimeClusters.clusterLoadFailed")));
  }
}

async function openInstances(cluster: RuntimeClusterView) {
  instanceCluster.value = cluster;
  instances.value = cluster.instances || [];
  instanceDrawerVisible.value = true;
  await loadInstances();
}

async function loadInstances() {
  const clusterId = instanceCluster.value?.id;
  if (clusterId == null) return;
  const sequence = ++instancesLoadSequence;
  instancesLoading.value = true;
  try {
    const result = await studioApi.runtimeClusters.instances(clusterId);
    if (sequence === instancesLoadSequence && String(instanceCluster.value?.id) === String(clusterId)) {
      instances.value = result;
    }
  } catch (error) {
    if (sequence === instancesLoadSequence) {
      ElMessage.error(errorMessage(error, t("web.runtimeClusters.instanceLoadFailed")));
    }
  } finally {
    if (sequence === instancesLoadSequence) {
      instancesLoading.value = false;
    }
  }
}

async function deleteCluster(cluster: RuntimeClusterView) {
  if (cluster.id == null) return;
  if (cluster.enabled) {
    ElMessage.warning(t("web.runtimeClusters.disableClusterBeforeDelete"));
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.runtimeClusters.deleteClusterConfirm", { name: cluster.name || cluster.code }),
      t("common.delete"),
      { type: "warning" },
    );
    await studioApi.runtimeClusters.delete(cluster.id);
    if (String(instanceCluster.value?.id) === String(cluster.id)) instanceDrawerVisible.value = false;
    if (String(endpointCluster.value?.id) === String(cluster.id)) endpointDrawerVisible.value = false;
    ElMessage.success(t("web.runtimeClusters.clusterDeleted"));
    await loadClusters();
    if (authorizationProjectId.value) await loadAuthorizations();
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(errorMessage(error, t("web.runtimeClusters.clusterDeleteFailed")));
  }
}

async function openEndpoints(cluster: RuntimeClusterView) {
  endpointCluster.value = cluster;
  endpointDrawerVisible.value = true;
  if (cluster.id != null) await loadEndpoints(cluster.id);
}

async function loadEndpoints(clusterId: EntityId) {
  const sequence = ++endpointsLoadSequence;
  endpointsLoading.value = true;
  try {
    const result = await studioApi.runtimeClusters.endpoints(clusterId);
    if (sequence === endpointsLoadSequence && String(endpointClusterId.value) === String(clusterId)) {
      endpoints.value = result;
    }
  } catch (error) {
    if (sequence === endpointsLoadSequence) {
      ElMessage.error(errorMessage(error, t("web.runtimeClusters.endpointLoadFailed")));
    }
  } finally {
    if (sequence === endpointsLoadSequence) {
      endpointsLoading.value = false;
    }
  }
}

function openEndpointDialog(endpoint?: RuntimeEndpointView) {
  if (endpointClusterId.value == null) return;
  if (isLegacyLocalEndpoint(endpoint)) {
    ElMessage.warning(t("web.runtimeClusters.legacyEndpointHint"));
    return;
  }
  editingEndpoint.value = endpoint ?? null;
  endpointForm.id = endpoint?.id;
  endpointForm.runtimeClusterId = endpoint?.runtimeClusterId ?? endpointClusterId.value;
  endpointForm.mode = "HTTP";
  endpointForm.endpointUrl = "";
  endpointForm.token = "";
  endpointForm.clearToken = false;
  endpointForm.connectTimeoutMillis = endpoint?.connectTimeoutMillis ?? 3000;
  endpointForm.readTimeoutMillis = endpoint?.readTimeoutMillis ?? 5000;
  endpointForm.enabled = endpoint?.enabled !== false;
  endpointHeadersText.value = "";
  endpointDialogVisible.value = true;
}

function endpointActions(endpoint: RuntimeEndpointView) {
  return [
    ...(!isLegacyLocalEndpoint(endpoint)
      ? [{ key: "edit", label: t("common.edit"), type: "primary", onClick: () => openEndpointDialog(endpoint) }]
      : []),
    { key: "test", label: t("web.runtimeClusters.testEndpoint"), type: "success", disabled: endpoint.id == null, onClick: () => testEndpoint(endpoint) },
    ...(endpoint.enabled
      ? [{ key: "disable", label: t("common.disable"), type: "warning", disabled: endpoint.id == null, onClick: () => disableEndpoint(endpoint) }]
      : []),
    { key: "delete", label: t("common.delete"), type: "danger", disabled: endpoint.id == null, onClick: () => deleteEndpoint(endpoint) },
  ];
}

function parseHeaders(): { valid: true; value?: Record<string, string> } | { valid: false } {
  if (!endpointHeadersText.value.trim()) {
    return { valid: true, value: endpointForm.id == null ? {} : undefined };
  }
  try {
    const value: unknown = JSON.parse(endpointHeadersText.value);
    if (!value || Array.isArray(value) || typeof value !== "object" || Object.values(value as Record<string, unknown>).some((item) => typeof item !== "string")) throw new Error();
    return { valid: true, value: value as Record<string, string> };
  } catch {
    ElMessage.warning(t("web.runtimeClusters.invalidHeaders"));
    return { valid: false };
  }
}

async function saveEndpoint() {
  const runtimeClusterId = endpointForm.runtimeClusterId;
  if (runtimeClusterId == null) return;
  const endpointUrl = endpointForm.endpointUrl.trim();
  if (!endpointUrl && !(endpointForm.id != null && editingEndpoint.value?.endpointMasked)) {
    ElMessage.warning(t("web.runtimeClusters.endpointUrlRequired"));
    return;
  }
  const parsedHeaders = parseHeaders();
  if (!parsedHeaders.valid) return;
  endpointSaving.value = true;
  try {
    await studioApi.runtimeClusters.saveEndpoint({ ...endpointForm, mode: "HTTP", runtimeClusterId, headers: parsedHeaders.value, endpointUrl: endpointUrl || undefined, token: endpointForm.token || undefined });
    ElMessage.success(t("web.runtimeClusters.endpointSaved"));
    endpointDialogVisible.value = false;
    await loadEndpoints(runtimeClusterId);
  } catch (error) {
    ElMessage.error(errorMessage(error, t("web.runtimeClusters.endpointSaveFailed")));
  } finally {
    endpointSaving.value = false;
  }
}

async function disableEndpoint(endpoint: RuntimeEndpointView) {
  if (endpoint.id == null) return;
  try {
    await studioApi.runtimeClusters.disableEndpoint(endpoint.id);
    ElMessage.success(t("web.runtimeClusters.endpointDisabled"));
    if (endpointClusterId.value != null) await loadEndpoints(endpointClusterId.value);
  } catch (error) {
    ElMessage.error(errorMessage(error, t("web.runtimeClusters.endpointDisableFailed")));
  }
}

async function testEndpoint(endpoint: RuntimeEndpointView) {
  if (endpoint.id == null) return;
  try {
    const result = await studioApi.runtimeClusters.testEndpoint(endpoint.id);
    const status = String(result.lastTestStatus || "").toUpperCase();
    const message = result.lastTestMessage || endpointTestStatusLabel(status);
    if (status === "SUCCESS") ElMessage.success(message);
    else if (status === "FAILED") ElMessage.error(message);
    else ElMessage.warning(message);
    if (endpointClusterId.value != null) await loadEndpoints(endpointClusterId.value);
  } catch (error) {
    ElMessage.error(errorMessage(error, t("web.runtimeClusters.endpointTestFailed")));
  }
}

async function deleteEndpoint(endpoint: RuntimeEndpointView) {
  if (endpoint.id == null) return;
  if (endpoint.enabled) {
    ElMessage.warning(t("web.runtimeClusters.disableEndpointBeforeDelete"));
    return;
  }
  try {
    await ElMessageBox.confirm(t("web.runtimeClusters.deleteEndpointConfirm"), t("common.delete"), { type: "warning" });
    await studioApi.runtimeClusters.deleteEndpoint(endpoint.id);
    ElMessage.success(t("web.runtimeClusters.endpointDeleted"));
    if (endpointClusterId.value != null) await loadEndpoints(endpointClusterId.value);
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(errorMessage(error, t("web.runtimeClusters.endpointDeleteFailed")));
  }
}

async function loadAuthorizations() {
  if (!authorizationProjectId.value) {
    authorizationsLoadSequence += 1;
    authorizationRows.value = [];
    preferredClusterId.value = "";
    authorizationsLoading.value = false;
    return;
  }
  const projectId = authorizationProjectId.value;
  const sequence = ++authorizationsLoadSequence;
  authorizationsLoading.value = true;
  try {
    const authorizations = await studioApi.runtimeClusters.projectAuthorizations(projectId);
    if (sequence !== authorizationsLoadSequence || authorizationProjectId.value !== projectId) {
      return;
    }
    const byClusterId = new Map(authorizations.map((item) => [String(item.runtimeClusterId), item]));
    authorizationRows.value = clusters.value.map((cluster) => {
      const authorization = byClusterId.get(String(cluster.id));
      return {
        ...cluster,
        clusterEnabled: cluster.enabled !== false,
        authorized: authorization?.enabled === true,
        allowManualOverride: authorization?.allowManualOverride === true,
      };
    });
    preferredClusterId.value = String(authorizations.find((item) => item.preferred)?.runtimeClusterId ?? "");
  } catch (error) {
    if (sequence === authorizationsLoadSequence) {
      ElMessage.error(errorMessage(error, t("web.runtimeClusters.authorizationLoadFailed")));
    }
  } finally {
    if (sequence === authorizationsLoadSequence) {
      authorizationsLoading.value = false;
    }
  }
}

async function saveAuthorization(row: AuthorizationRow) {
  if (!authorizationProjectId.value || row.id == null) return;
  try {
    await studioApi.runtimeClusters.saveProjectAuthorization({
      projectId: authorizationProjectId.value,
      runtimeClusterId: row.id,
      enabled: row.authorized,
      preferred: row.authorized && preferredClusterId.value === String(row.id),
      allowManualOverride: row.authorized && row.allowManualOverride,
    });
    ElMessage.success(t("web.runtimeClusters.authorizationSaved"));
    await loadAuthorizations();
  } catch (error) {
    ElMessage.error(errorMessage(error, t("web.runtimeClusters.authorizationLoadFailed")));
  }
}

onMounted(async () => {
  authorizationProjectId.value = authStore.currentProjectId == null ? "" : String(authStore.currentProjectId);
  await loadClusters();
  await loadAuthorizations();
  refreshTimer = window.setInterval(() => { void refreshRuntimeStatus(); }, 30000);
});

onBeforeUnmount(() => {
  if (refreshTimer != null) window.clearInterval(refreshTimer);
});

watch(
  [
    () => authStore.currentTenantId,
    () => authStore.currentProjectId,
    () => authStore.projects.map((project) => String(project.projectId)).join(","),
  ],
  async ([tenantId, projectId, projectIds], [previousTenantId, previousProjectId]) => {
    const availableProjectIds = new Set(String(projectIds || "").split(",").filter(Boolean));
    const currentProjectId = projectId == null ? "" : String(projectId);
    const contextChanged = tenantId !== previousTenantId || String(projectId ?? "") !== String(previousProjectId ?? "");
    if (contextChanged) {
      authorizationProjectId.value = currentProjectId && availableProjectIds.has(currentProjectId) ? currentProjectId : "";
      authorizationRows.value = [];
      preferredClusterId.value = "";
      if (tenantId !== previousTenantId) await loadClusters();
      await loadAuthorizations();
      return;
    }
    if (authorizationProjectId.value && !availableProjectIds.has(authorizationProjectId.value)) {
      authorizationProjectId.value = "";
      await loadAuthorizations();
      return;
    }
    if (!authorizationProjectId.value && currentProjectId && availableProjectIds.has(currentProjectId)) {
      authorizationProjectId.value = currentProjectId;
      await loadAuthorizations();
    }
  },
);
</script>

<style scoped>
.runtime-cluster-page { display: flex; flex-direction: column; gap: 16px; }
.authorization-toolbar, .drawer-toolbar { display: flex; justify-content: flex-end; margin-bottom: 12px; }
.authorization-toolbar .el-select { width: min(100%, 320px); }
.drawer-description { margin: 0 0 16px; color: var(--el-text-color-secondary); }
.form-item-hint { margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.45; }
.endpoint-secret-summary { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.endpoint-test-summary { display: grid; justify-items: start; gap: 4px; min-width: 0; }
.endpoint-test-message { width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 640px) {
  .authorization-toolbar { justify-content: stretch; }
  .authorization-toolbar .el-select { width: 100%; }
  .drawer-toolbar { justify-content: stretch; }
  .drawer-toolbar .el-button { width: 100%; }
}
</style>
