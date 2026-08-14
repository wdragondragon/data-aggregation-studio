<template>
  <div class="studio-page unstructured-management-page">
    <div class="studio-toolbar">
      <div>
        <h3>非结构化管理</h3>
        <span class="page-subtitle">单源文件浏览与操作</span>
      </div>
      <div class="toolbar-actions">
        <el-select v-model="runtimeClusterId" clearable filterable placeholder="选择运行集群" @change="handleClusterChange">
          <el-option v-for="cluster in runtimeClusters" :key="String(cluster.id)" :label="cluster.name || cluster.code || `#${cluster.id}`" :value="cluster.id" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading.sources" @click="loadSources">刷新</el-button>
      </div>
    </div>

    <section class="management-workspace">
      <aside class="source-list">
        <header><strong>文件数据源</strong><span>{{ sources.length }}</span></header>
        <el-empty v-if="!runtimeClusterId" description="请选择运行集群" />
        <el-empty v-else-if="!sources.length && !loading.sources" description="当前集群没有文件数据源" />
        <button
          v-for="source in sources"
          :key="String(source.id)"
          type="button"
          class="source-list__item"
          :class="{ 'is-active': String(source.id) === String(datasourceId) }"
          @click="selectSource(source.id)"
        >
          <strong>{{ source.name }}</strong>
          <span>{{ source.typeCode }}</span>
        </button>
      </aside>

      <main class="browser-workspace">
        <FileTransferBrowserPanel
          title="文件浏览器"
          :runtime-cluster-id="runtimeClusterId"
          :datasource-id="datasourceId"
          :runtime-clusters="runtimeClusters"
          :show-runtime-cluster-selector="false"
          :datasources="datasources"
          :path="path"
          :entries="entries"
          :loading="loading.browser"
          :has-more="hasMore"
          :page-number="cursorHistory.length"
          :selected-count="selectedEntries.length"
          @update:datasource-id="selectSource"
          @browse="browse"
          @parent="browse(parentTransferPath(path))"
          @refresh="loadBrowser"
          @previous-page="previousPage"
          @next-page="nextPage"
          @selection-change="selectedEntries = $event"
        />
        <div class="operation-toolbar">
          <input ref="uploadInput" class="file-input" type="file" @change="handleUploadSelection" />
          <el-button :icon="Upload" :loading="loading.upload" :disabled="!canEdit || !datasourceId || loading.upload" @click="chooseUploadFile">上传文件</el-button>
          <el-progress v-if="loading.upload" class="upload-progress" :percentage="uploadProgress" :stroke-width="6" />
          <el-button :icon="FolderAdd" :disabled="!canEdit || loading.upload" @click="createDirectory">新建文件夹</el-button>
          <el-button :icon="EditPen" :disabled="selectedEntries.length !== 1 || !canEdit" @click="renameSelected">重命名</el-button>
          <el-button :icon="Rank" :disabled="selectedEntries.length !== 1 || !canEdit" @click="moveSelected">移动</el-button>
          <el-button :icon="Download" :loading="loading.download" :disabled="selectedEntries.length === 0 || !canDownload || loading.download" @click="downloadSelected">下载</el-button>
          <el-button type="danger" :icon="Delete" :disabled="selectedEntries.length === 0 || !canDelete" @click="deleteSelected">删除</el-button>
          <el-button v-if="selectedSource?.aclManageable" :icon="Lock" @click="openAcl">ACL</el-button>
        </div>
        <div class="permission-strip">
          <span>当前路径：<code>{{ path }}</code></span>
          <el-tag v-for="permission in permissions.effectivePermissions" :key="permission" size="small" type="success">{{ permission }}</el-tag>
          <span v-if="!permissions.effectivePermissions?.length">无可用权限</span>
        </div>
      </main>
    </section>

    <el-dialog v-model="aclDialogVisible" :title="aclDialogTitle" width="min(760px, calc(100vw - 24px))">
      <div class="acl-section">
        <div class="acl-section__header"><strong>源级权限</strong><el-button link type="primary" @click="addAclRow(sourceAclDraft)">新增</el-button></div>
        <el-table :data="sourceAclDraft" size="small">
          <el-table-column label="主体" min-width="170">
            <template #default="{ row }"><el-select v-model="row.principalType"><el-option label="项目成员" value="PROJECT" /><el-option label="指定用户" value="USER" /></el-select></template>
          </el-table-column>
          <el-table-column label="用户" min-width="180"><template #default="{ row }"><el-select v-model="row.userId" :disabled="row.principalType !== 'USER'"><el-option v-for="user in users" :key="String(user.id)" :label="user.displayName || user.username" :value="user.id" /></el-select></template></el-table-column>
          <el-table-column label="权限" min-width="150"><template #default="{ row }"><el-select v-model="row.permission"><el-option v-for="item in aclPermissions" :key="item" :label="item" :value="item" /></el-select></template></el-table-column>
          <el-table-column label="效果" min-width="140"><template #default="{ row }"><el-select v-model="row.effect"><el-option v-for="item in aclEffects" :key="item" :label="item" :value="item" /></el-select></template></el-table-column>
          <el-table-column width="72"><template #default="{ $index }"><el-button link type="danger" :icon="Delete" @click="sourceAclDraft.splice($index, 1)" /></template></el-table-column>
        </el-table>
      </div>
      <div class="acl-section">
        <div class="acl-section__header"><strong>{{ aclDirectory ? "目录权限（作用于后代）" : "文件权限（仅当前文件）" }}</strong><el-button link type="primary" @click="addAclRow(pathAclDraft)">新增</el-button></div>
        <code class="acl-scope-path">{{ aclPath }}</code>
        <el-table :data="pathAclDraft" size="small">
          <el-table-column label="主体" min-width="170"><template #default="{ row }"><el-select v-model="row.principalType"><el-option label="项目成员" value="PROJECT" /><el-option label="指定用户" value="USER" /></el-select></template></el-table-column>
          <el-table-column label="用户" min-width="180"><template #default="{ row }"><el-select v-model="row.userId" :disabled="row.principalType !== 'USER'"><el-option v-for="user in users" :key="String(user.id)" :label="user.displayName || user.username" :value="user.id" /></el-select></template></el-table-column>
          <el-table-column label="权限" min-width="150"><template #default="{ row }"><el-select v-model="row.permission"><el-option v-for="item in aclPermissions" :key="item" :label="item" :value="item" /></el-select></template></el-table-column>
          <el-table-column label="效果" min-width="140"><template #default="{ row }"><el-select v-model="row.effect"><el-option v-for="item in aclEffects" :key="item" :label="item" :value="item" /></el-select></template></el-table-column>
          <el-table-column width="72"><template #default="{ $index }"><el-button link type="danger" :icon="Delete" @click="pathAclDraft.splice($index, 1)" /></template></el-table-column>
        </el-table>
      </div>
      <template #footer><el-button @click="aclDialogVisible = false">取消</el-button><el-button type="primary" :loading="loading.acl" @click="saveAcl">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { Delete, Download, EditPen, FolderAdd, Lock, Rank, Refresh, Upload } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type { DataSourceOptionView, EntityId, FileTransferFileEntryView, RuntimeClusterView, StudioUserOption, UnstructuredAclEntryRequest, UnstructuredPermissionView, UnstructuredSourceView } from "@studio/api-sdk";
import { resolveStudioApiBaseUrl, studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import FileTransferBrowserPanel from "@/components/file-transfer/FileTransferBrowserPanel.vue";
import { normalizeTransferPath, parentTransferPath } from "@/utils/fileTransfer";

const authStore = useAuthStore();
const runtimeClusters = ref<RuntimeClusterView[]>([]);
const runtimeClusterId = ref<EntityId | "">("");
const sources = ref<UnstructuredSourceView[]>([]);
const datasources = ref<DataSourceOptionView[]>([]);
const datasourceId = ref<EntityId | "">("");
const selectedSource = computed(() => sources.value.find((item) => String(item.id) === String(datasourceId.value)));
const path = ref("/");
const entries = ref<FileTransferFileEntryView[]>([]);
const selectedEntries = ref<FileTransferFileEntryView[]>([]);
const cursorHistory = ref<Array<string | undefined>>([undefined]);
const nextCursor = ref<string>();
const hasMore = ref(false);
const loading = reactive({ sources: false, browser: false, acl: false, upload: false, download: false });
const uploadInput = ref<HTMLInputElement>();
const uploadProgress = ref(0);
const permissions = ref<UnstructuredPermissionView>({ effectivePermissions: [] });
let browserRequestSequence = 0;
let permissionRequestSequence = 0;
let initialPathPending = false;
const aclDialogVisible = ref(false);
const users = ref<StudioUserOption[]>([]);
const sourceAclDraft = ref<UnstructuredAclEntryRequest[]>([]);
const pathAclDraft = ref<UnstructuredAclEntryRequest[]>([]);
const aclPath = ref("/");
const aclDirectory = ref(true);
const aclDialogTitle = computed(() => `数据源 ACL · ${aclDirectory.value ? "目录" : "文件"}`);
const aclPermissions = ["BROWSE", "DOWNLOAD", "EDIT", "DELETE"];
const aclEffects = ["ALLOW", "DENY", "INHERIT"];

const canEdit = computed(() => permissions.value.effectivePermissions?.includes("EDIT") === true);
const canDelete = computed(() => permissions.value.effectivePermissions?.includes("DELETE") === true);
const canDownload = computed(() => permissions.value.effectivePermissions?.includes("DOWNLOAD") === true);

async function loadRuntimeClusters() {
  runtimeClusters.value = (await studioApi.runtimeClusters.options(authStore.currentProjectId ?? undefined)).filter((item) => item.enabled !== false);
}

async function handleClusterChange() {
  sources.value = []; datasources.value = []; datasourceId.value = ""; resetBrowser();
  if (runtimeClusterId.value) await loadSources();
}

async function loadSources() {
  if (!runtimeClusterId.value) return;
  loading.sources = true;
  try {
    sources.value = await studioApi.unstructuredManagement.sources(runtimeClusterId.value, { studioSkipGlobalLoading: true });
    datasources.value = sources.value.map((source) => ({ id: source.id, name: source.name, typeCode: source.typeCode } as DataSourceOptionView));
  } catch (error) { showError(error, "加载文件数据源失败"); }
  finally { loading.sources = false; }
}

async function selectSource(id?: EntityId) {
  datasourceId.value = id ?? ""; resetBrowser();
  if (!id) return;
  initialPathPending = true;
  await loadBrowser();
  await loadPermissions();
}

function resetBrowser() { browserRequestSequence += 1; permissionRequestSequence += 1; initialPathPending = false; loading.browser = false; path.value = "/"; entries.value = []; selectedEntries.value = []; cursorHistory.value = [undefined]; nextCursor.value = undefined; hasMore.value = false; permissions.value = { effectivePermissions: [] }; }

async function browse(nextPath: string) { path.value = normalizeTransferPath(nextPath); cursorHistory.value = [undefined]; await loadBrowser(); await loadPermissions(); }
async function loadBrowser() {
  if (!runtimeClusterId.value || !datasourceId.value) return;
  const requestSequence = ++browserRequestSequence;
  const requestedClusterId = runtimeClusterId.value;
  const requestedDatasourceId = datasourceId.value;
  const requestedPath = path.value;
  loading.browser = true;
  try {
    const page = await studioApi.unstructuredManagement.browser.list({ runtimeClusterId: requestedClusterId, datasourceId: requestedDatasourceId, path: requestedPath, cursor: cursorHistory.value[cursorHistory.value.length - 1], pageSize: 200 }, { studioSkipGlobalLoading: true });
    if (requestSequence !== browserRequestSequence || requestedClusterId !== runtimeClusterId.value || requestedDatasourceId !== datasourceId.value) return;
    if (initialPathPending && requestedPath === "/" && page.initialPath && normalizeTransferPath(page.initialPath) !== "/") {
      initialPathPending = false;
      path.value = normalizeTransferPath(page.initialPath);
      cursorHistory.value = [undefined];
      await loadBrowser();
      return;
    }
    initialPathPending = false;
    path.value = normalizeTransferPath(page.path || path.value); entries.value = page.entries ?? []; nextCursor.value = page.nextCursor || undefined; hasMore.value = Boolean(page.hasMore && page.nextCursor); selectedEntries.value = [];
  } catch (error) { if (requestSequence === browserRequestSequence) { entries.value = []; showError(error, "浏览目录失败"); } }
  finally { if (requestSequence === browserRequestSequence) loading.browser = false; }
}
async function nextPage() { if (!nextCursor.value) return; cursorHistory.value.push(nextCursor.value); await loadBrowser(); }
async function previousPage() { if (cursorHistory.value.length <= 1) return; cursorHistory.value.pop(); await loadBrowser(); }
async function loadPermissions() { if (!datasourceId.value) return; const requestSequence = ++permissionRequestSequence; const requestedDatasourceId = datasourceId.value; const value = await studioApi.unstructuredManagement.permissions(requestedDatasourceId, path.value); if (requestSequence === permissionRequestSequence && requestedDatasourceId === datasourceId.value) permissions.value = value; }

async function createDirectory() {
  try { const { value } = await ElMessageBox.prompt("目录名称", "新建文件夹", { inputPattern: /^(?!\.\.?$)[^\\/]+$/, inputErrorMessage: "请输入有效目录名称" }); await submitOperation("CREATE_DIRECTORY", `${path.value.replace(/\/$/, "")}/${value}`); } catch { /* cancelled */ }
}
async function renameSelected() { const entry = selectedEntries.value[0]; if (!entry) return; try { const { value } = await ElMessageBox.prompt("新名称", "重命名", { inputValue: entry.name }); await submitOperation("RENAME", entry.path, `${parentTransferPath(entry.path)}/${value}`); } catch { /* cancelled */ } }
async function moveSelected() { const entry = selectedEntries.value[0]; if (!entry) return; try { const { value } = await ElMessageBox.prompt("目标路径", "移动文件", { inputValue: path.value }); await submitOperation("MOVE", entry.path, `${normalizeTransferPath(value)}/${entry.name}`); } catch { /* cancelled */ } }
async function deleteSelected() {
  const entry = selectedEntries.value[0]; if (!entry) return;
  try { await ElMessageBox.confirm(entry.directory ? "非空目录需要递归删除，确认继续？" : `确认删除 ${entry.name}？`, "删除文件", { type: "warning" }); await submitOperation("DELETE", entry.path, undefined, Boolean(entry.directory)); } catch { /* cancelled */ }
}
async function submitOperation(operation: string, sourcePath: string, targetPath?: string, recursiveConfirmed = false) { if (!runtimeClusterId.value || !datasourceId.value) return; try { await studioApi.unstructuredManagement.operate({ runtimeClusterId: runtimeClusterId.value, datasourceId: datasourceId.value, operation, sourcePath, targetPath, recursiveConfirmed }); ElMessage.success("操作完成"); await loadBrowser(); await loadPermissions(); } catch (error) { showError(error, "文件操作失败"); } }

function chooseUploadFile() {
  if (!canEdit.value || loading.upload) return;
  if (uploadInput.value) uploadInput.value.value = "";
  uploadInput.value?.click();
}

async function handleUploadSelection(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file || !runtimeClusterId.value || !datasourceId.value) return;
  const targetPath = normalizeTransferPath(`${path.value === "/" ? "" : path.value}/${file.name}`);
  loading.upload = true;
  uploadProgress.value = 0;
  try {
    let overwrite = false;
    try {
      await studioApi.unstructuredManagement.stat({ runtimeClusterId: runtimeClusterId.value, datasourceId: datasourceId.value, path: targetPath }, { studioSkipGlobalLoading: true });
      overwrite = await confirmOverwrite(file.name);
      if (!overwrite) return;
    } catch (error) {
      if (errorStatus(error) !== 404) throw error;
    }
    try {
      await uploadFile(file, targetPath, overwrite);
    } catch (error) {
      if (errorStatus(error) !== 409 || overwrite || !(await confirmOverwrite(file.name))) throw error;
      await uploadFile(file, targetPath, true);
    }
    uploadProgress.value = 100;
    ElMessage.success("文件上传成功");
    await loadBrowser();
    await loadPermissions();
  } catch (error) {
    showError(error, "文件上传失败");
  } finally {
    loading.upload = false;
  }
}

function uploadFile(file: File, targetPath: string, overwrite: boolean) {
  return studioApi.unstructuredManagement.upload(
    { runtimeClusterId: runtimeClusterId.value as EntityId, datasourceId: datasourceId.value as EntityId, targetPath, overwrite },
    file,
    (percentage) => { uploadProgress.value = percentage; },
    { studioSkipGlobalLoading: true },
  );
}

async function confirmOverwrite(fileName: string) {
  try {
    await ElMessageBox.confirm(`文件 ${fileName} 已存在，是否覆盖？`, "文件已存在", { type: "warning", confirmButtonText: "覆盖", cancelButtonText: "取消" });
    return true;
  } catch {
    return false;
  }
}

async function downloadSelected() {
  if (!selectedEntries.value.length || !runtimeClusterId.value || !datasourceId.value || loading.download) return;
  loading.download = true;
  try {
    const downloadTicket = await studioApi.unstructuredManagement.createDownloadTicket({
      runtimeClusterId: runtimeClusterId.value,
      datasourceId: datasourceId.value,
      paths: selectedEntries.value.map((item) => item.path),
    }, { studioSkipGlobalLoading: true });
    if (!downloadTicket.ticket) throw new Error("下载票据为空");
    startNativeDownload(downloadTicket.ticket);
  } catch (error) {
    showError(error, "下载失败");
  } finally {
    loading.download = false;
  }
}

function startNativeDownload(ticket: string) {
  const url = new URL(
    resolveStudioApiBaseUrl("/unstructured-management/download/native"),
    window.location.origin,
  );
  url.searchParams.set("ticket", ticket);
  const anchor = document.createElement("a");
  anchor.href = url.toString();
  anchor.style.display = "none";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}

function errorStatus(error: unknown) {
  return Number((error as { status?: number } | null)?.status ?? 0);
}

async function openAcl() { if (!datasourceId.value) return; const selected = selectedEntries.value.length === 1 ? selectedEntries.value[0] : undefined; aclPath.value = selected?.path || path.value; aclDirectory.value = selected ? Boolean(selected.directory) : true; loading.acl = true; try { const [source, pathEntries, optionUsers] = await Promise.all([studioApi.unstructuredManagement.sourceAcl(datasourceId.value), studioApi.unstructuredManagement.pathAcl(datasourceId.value, aclPath.value), studioApi.unstructuredManagement.userOptions()]); sourceAclDraft.value = source.map(toAclDraft); pathAclDraft.value = pathEntries.map(toAclDraft); users.value = optionUsers; aclDialogVisible.value = true; } catch (error) { showError(error, "加载 ACL 失败"); } finally { loading.acl = false; } }
function toAclDraft(entry: { principalType: string; userId?: EntityId; permission: string; effect: string }): UnstructuredAclEntryRequest { return { principalType: entry.principalType, userId: entry.userId, permission: entry.permission, effect: entry.effect }; }
function addAclRow(target: typeof sourceAclDraft.value) { target.push({ principalType: "PROJECT", permission: "BROWSE", effect: "ALLOW" }); }
async function saveAcl() { if (!datasourceId.value) return; loading.acl = true; try { await studioApi.unstructuredManagement.replaceSourceAcl(datasourceId.value, { datasourceId: datasourceId.value, entries: sourceAclDraft.value }); await studioApi.unstructuredManagement.replacePathAcl({ datasourceId: datasourceId.value, path: aclPath.value, directory: aclDirectory.value, entries: pathAclDraft.value }); aclDialogVisible.value = false; await loadPermissions(); ElMessage.success("ACL 已保存"); } catch (error) { showError(error, "保存 ACL 失败"); } finally { loading.acl = false; } }
function showError(error: unknown, fallback: string) { ElMessage.error(error instanceof Error && error.message ? error.message : fallback); }

onMounted(async () => { try { await loadRuntimeClusters(); } catch (error) { showError(error, "加载运行集群失败"); } });
</script>

<style scoped>
.unstructured-management-page { gap: 12px; }
.studio-toolbar, .toolbar-actions { display: flex; align-items: center; gap: 10px; }
.studio-toolbar { justify-content: space-between; }
.page-subtitle { color: var(--studio-text-soft); font-size: 12px; }
.toolbar-actions .el-select { width: min(280px, 48vw); }
.management-workspace { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 12px; min-height: 0; }
.source-list { border: 1px solid var(--studio-border); border-radius: 8px; background: var(--studio-surface-strong); padding: 10px; min-height: 460px; }
.source-list header { display: flex; justify-content: space-between; color: var(--studio-text-soft); margin-bottom: 8px; }
.source-list__item { display: grid; width: 100%; gap: 3px; text-align: left; padding: 10px; border: 0; border-radius: 6px; background: transparent; color: var(--studio-text); cursor: pointer; }
.source-list__item span { font-size: 12px; color: var(--studio-text-soft); }
.source-list__item:hover, .source-list__item.is-active { background: var(--studio-surface-hover); }
.browser-workspace { min-width: 0; display: grid; gap: 10px; }
.operation-toolbar, .permission-strip { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.file-input { display: none; }
.upload-progress { width: 150px; }
.permission-strip { color: var(--studio-text-soft); font-size: 12px; }
.acl-section { display: grid; gap: 8px; margin-bottom: 16px; }
.acl-section__header { display: flex; justify-content: space-between; align-items: center; }
.acl-scope-path { color: var(--studio-text-soft); overflow-wrap: anywhere; }
@media (max-width: 800px) { .management-workspace { grid-template-columns: 1fr; } .source-list { min-height: auto; } .studio-toolbar { align-items: stretch; flex-direction: column; } .toolbar-actions { align-items: stretch; } .toolbar-actions .el-select { width: 100%; } }
</style>
