<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>Python 包管理</h3>
        <p>统一上传 WHL/TAR.GZ，并按包名聚合管理 OSS、GitLab PyPI 与 pypiserver 中的版本。</p>
      </div>
      <div class="download-count-badge">
        <span class="download-count-label">今日下载</span>
        <strong class="download-count-value">{{ todayDownloadCount }}</strong>
        <span class="download-count-unit">次</span>
      </div>
      <div class="studio-toolbar-actions">
        <el-button
          plain
          type="danger"
          :disabled="selectedPackages.length === 0"
          :loading="batchDeleting"
          @click="batchDeletePackages"
        >
          批量删除
        </el-button>
        <el-button plain :loading="exporting" @click="exportRequirements">导出清单</el-button>
        <el-button plain @click="loadPackages">刷新</el-button>
        <el-button type="primary" @click="openUpload()">上传依赖包</el-button>
      </div>
    </div>

    <SectionCard title="依赖包管理" description="包列表展示最新上传版本；版本详情保留每次上传记录和物理仓库绑定。">
      <div class="package-filters">
        <el-select v-model="filters.enabled" clearable placeholder="全部状态">
          <el-option label="启用" :value="true" />
          <el-option label="停用" :value="false" />
        </el-select>
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="搜索包名或版本"
          @keyup.enter="search"
        />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button plain @click="resetFilters">重置</el-button>
      </div>

      <StudioTableShell min-width="1128px">
        <el-table
          v-loading="loading"
          :data="page.items"
          row-key="normalizedName"
          border
          @selection-change="handlePackageSelectionChange"
        >
          <el-table-column type="selection" width="48" />
          <el-table-column prop="name" label="包名" min-width="190" />
          <el-table-column prop="latestVersion" label="最新版本" min-width="120" />
          <el-table-column label="类型" width="105" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ artifactTypeLabel(row.artifactType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="versionCount" label="版本数" width="90" align="center" />
          <el-table-column label="最新文件大小" width="130">
            <template #default="{ row }">{{ formatFileSize(row.latestSizeBytes) }}</template>
          </el-table-column>
          <el-table-column label="发布仓库" min-width="160">
            <template #default="{ row }">{{ storeLabel(row.artifactStoreId) }}</template>
          </el-table-column>
          <el-table-column prop="latestUploadedAt" label="上传时间" min-width="170" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <StatusPill :label="row.enabled ? '正常' : '停用'" :tone="row.enabled ? 'success' : 'neutral'" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="250" fixed="right" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openVersions(row)">查看版本</el-button>
              <el-button link type="primary" @click="openUpload(row)">新增版本</el-button>
              <el-button link type="primary" @click="copyInstallCommand(row)">复制安装命令</el-button>
            </template>
          </el-table-column>
        </el-table>
      </StudioTableShell>

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="page.pageNo"
          v-model:page-size="page.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="page.total"
          @current-change="loadPackages"
          @size-change="handlePageSizeChange"
        />
      </div>
    </SectionCard>

    <el-dialog v-model="uploadVisible" title="上传 Python 安装包" width="720px">
      <el-alert
        title="包名和版本会与文件名交叉校验；同一仓库中请使用新版本号发布，避免覆盖已有制品。"
        type="info"
        :closable="false"
        show-icon
        class="dialog-alert"
      />
      <el-form label-width="110px">
        <div class="form-grid">
          <el-form-item label="包名">
            <el-input v-model="uploadForm.name" placeholder="例如 custom-utils" />
          </el-form-item>
          <el-form-item label="版本">
            <el-input v-model="uploadForm.version" placeholder="例如 1.3.0" />
          </el-form-item>
          <el-form-item label="发布仓库" class="span-2">
            <el-select v-model="uploadForm.artifactStoreId" placeholder="请选择实际制品仓库">
              <el-option
                v-for="store in enabledStores"
                :key="store.id"
                :label="`${store.storeName}（${store.provider} / ${scopeLabel(store.scopeType)}）`"
                :value="store.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="安装包" class="span-2">
            <el-upload
              v-model:file-list="uploadFiles"
              accept=".whl,.tar.gz"
              :auto-upload="false"
              multiple
              :on-change="handleFileChange"
              :on-remove="handleFileChange"
            >
              <el-button>选择 WHL/TAR.GZ</el-button>
              <template #tip><div class="upload-tip">可同时上传同一版本的 wheel 和源码包。</div></template>
            </el-upload>
          </el-form-item>
          <el-form-item label="启用"><el-switch v-model="uploadForm.enabled" /></el-form-item>
          <el-form-item label="说明" class="span-2">
            <el-input v-model="uploadForm.description" type="textarea" :rows="3" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePackage">上传</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="versionsVisible" :title="`${selectedPackage?.name ?? ''} · 版本详情`" width="980px">
      <StudioTableShell min-width="840px">
        <el-table v-loading="versionsLoading" :data="versions" border>
          <el-table-column prop="version" label="版本" width="120" />
          <el-table-column label="文件" min-width="260">
            <template #default="{ row }">
              <div v-if="visibleFiles(row).length" class="version-files">
                <el-tag v-for="file in visibleFiles(row)" :key="file.id" size="small" effect="plain">
                  {{ file.originalFileName }} · {{ formatFileSize(file.sizeBytes) }}
                </el-tag>
              </div>
              <span v-else class="muted-text">无文件</span>
            </template>
          </el-table-column>
          <el-table-column label="仓库" min-width="150">
            <template #default="{ row }">{{ storeLabel(row.artifactStoreId) }}</template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="上传时间" width="170" />
          <el-table-column label="状态" width="85" align="center">
            <template #default="{ row }">
              <StatusPill :label="row.enabled ? '成功' : '停用'" :tone="row.enabled ? 'success' : 'neutral'" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="185" fixed="right" align="center">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="!firstFile(row)" @click="downloadVersion(row)">下载</el-button>
              <el-button link @click="toggleVersion(row)">{{ row.enabled ? "停用" : "启用" }}</el-button>
              <el-button link type="danger" @click="deleteVersion(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </StudioTableShell>
      <template #footer>
        <el-button @click="versionsVisible = false">关闭</el-button>
        <el-button type="primary" @click="openUpload(selectedPackage)">新增版本</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type { UploadFile, UploadFiles, UploadUserFile } from "element-plus";
import type {
  ArtifactStore,
  EntityId,
  EnvironmentDependency,
  EnvironmentDependencyFileListView,
  EnvironmentDependencyListView,
  PythonPackageSummary,
} from "@studio/api-sdk";
import { SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";

const loading = ref(false);
const saving = ref(false);
const batchDeleting = ref(false);
const exporting = ref(false);
const uploadVisible = ref(false);
const versionsVisible = ref(false);
const versionsLoading = ref(false);
const stores = ref<ArtifactStore[]>([]);
const versions = ref<EnvironmentDependencyListView[]>([]);
const selectedPackage = ref<PythonPackageSummary>();
const selectedPackages = ref<PythonPackageSummary[]>([]);
const uploadFiles = ref<UploadUserFile[]>([]);
const filters = reactive<{ keyword: string; enabled?: boolean }>({ keyword: "", enabled: undefined });
const page = reactive({ pageNo: 1, pageSize: 20, total: 0, items: [] as PythonPackageSummary[] });
const uploadForm = reactive({
  name: "",
  version: "",
  artifactStoreId: undefined as EntityId | undefined,
  enabled: true,
  description: "",
  files: [] as File[],
});
const enabledStores = computed(() => stores.value.filter((item) => item.enabled));
const todayDownloadCount = ref(0);

async function loadPackages() {
  loading.value = true;
  try {
    const result = await studioApi.pythonPackages.queryPage({
      pageNum: page.pageNo,
      pageSize: page.pageSize,
      keyword: filters.keyword.trim() || undefined,
      enabled: filters.enabled,
    });
    page.pageNo = Number(result.pageNo ?? 1);
    page.pageSize = Number(result.pageSize ?? page.pageSize);
    page.total = Number(result.total ?? 0);
    page.items = result.items;
    selectedPackages.value = [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载 Python 包失败");
  } finally {
    loading.value = false;
  }
}

async function loadStores() {
  try {
    stores.value = await studioApi.artifactStores.list({ enabledOnly: true });
  } catch {
    stores.value = [];
  }
}

async function loadTodayDownloadCount() {
  try {
    todayDownloadCount.value = Number(await studioApi.pythonPackages.todayDownloadCount()) || 0;
  } catch {
    todayDownloadCount.value = 0;
  }
}

function search() {
  page.pageNo = 1;
  void loadPackages();
}

function resetFilters() {
  filters.keyword = "";
  filters.enabled = undefined;
  search();
}

function handlePageSizeChange() {
  page.pageNo = 1;
  void loadPackages();
}

function handlePackageSelectionChange(rows: PythonPackageSummary[]) {
  selectedPackages.value = rows;
}

function openUpload(pkg?: PythonPackageSummary) {
  uploadForm.name = pkg?.name ?? "";
  uploadForm.version = "";
  uploadForm.artifactStoreId = pkg?.artifactStoreId;
  uploadForm.enabled = true;
  uploadForm.description = "";
  uploadForm.files = [];
  uploadFiles.value = [];
  uploadVisible.value = true;
  if (stores.value.length === 0) void loadStores();
}

function handleFileChange(_file: UploadFile, files: UploadFiles) {
  uploadFiles.value = files;
  uploadForm.files = files.map((item) => item.raw as File | undefined)
    .filter((item): item is File => Boolean(item));
}

async function savePackage() {
  if (!uploadForm.name.trim() || !uploadForm.version.trim()) {
    ElMessage.error("请填写包名和版本");
    return;
  }
  if (uploadForm.files.length === 0) {
    ElMessage.error("请选择 WHL 或 TAR.GZ 安装包");
    return;
  }
  if (uploadForm.artifactStoreId == null) {
    ElMessage.error("请选择实际制品仓库");
    return;
  }
  const payload = new FormData();
  payload.append("name", uploadForm.name.trim());
  payload.append("version", uploadForm.version.trim());
  payload.append("scriptType", "PYTHON");
  payload.append("enabled", String(uploadForm.enabled));
  payload.append("artifactStoreId", String(uploadForm.artifactStoreId));
  if (uploadForm.description.trim()) payload.append("description", uploadForm.description.trim());
  uploadForm.files.forEach((file) => payload.append("files", file));
  saving.value = true;
  try {
    await studioApi.environmentDependencies.saveOrUpdateCheckForm(payload);
    uploadVisible.value = false;
    ElMessage.success("Python 包上传成功");
    await loadPackages();
    if (selectedPackage.value
      && normalizeName(selectedPackage.value.name) === normalizeName(uploadForm.name)) {
      await loadVersions(selectedPackage.value.normalizedName);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Python 包上传失败");
  } finally {
    saving.value = false;
  }
}

async function openVersions(pkg: PythonPackageSummary) {
  selectedPackage.value = pkg;
  versionsVisible.value = true;
  await loadVersions(pkg.normalizedName);
}

async function loadVersions(packageName: string) {
  versionsLoading.value = true;
  try {
    versions.value = await studioApi.pythonPackages.versions(packageName);
  } catch (error) {
    versions.value = [];
    ElMessage.error(error instanceof Error ? error.message : "加载版本失败");
  } finally {
    versionsLoading.value = false;
  }
}

async function toggleVersion(row: EnvironmentDependencyListView) {
  if (!row.id) return;
  try {
    const updated: EnvironmentDependency = row.enabled
      ? await studioApi.environmentDependencies.disable(row.id)
      : await studioApi.environmentDependencies.enable(row.id);
    row.enabled = updated.enabled;
    ElMessage.success(updated.enabled ? "版本已启用" : "版本已停用");
    await loadPackages();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "更新状态失败");
  }
}

async function deleteVersion(row: EnvironmentDependencyListView) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(
      `确认删除 ${row.name}==${row.version ?? ""} 吗？该操作会同步删除支持删除的物理仓库制品。`,
      "删除版本",
      { type: "warning" },
    );
    await studioApi.environmentDependencies.delete(row.id);
    ElMessage.success("版本已删除");
    if (selectedPackage.value) await loadVersions(selectedPackage.value.normalizedName);
    await loadPackages();
    if (versions.value.length === 0) versionsVisible.value = false;
  } catch (error) {
    if (error !== "cancel") ElMessage.error(error instanceof Error ? error.message : "删除版本失败");
  }
}

async function batchDeletePackages() {
  if (selectedPackages.value.length === 0) return;
  const selected = [...selectedPackages.value];
  const expectedVersionCount = selected.reduce((total, item) => total + Number(item.versionCount ?? 0), 0);
  try {
    await ElMessageBox.confirm(
      `确认删除已选中的 ${selected.length} 个包及其全部 ${expectedVersionCount} 个版本吗？`
        + "该操作会同步删除支持删除的物理仓库制品；仓库不支持删除时将整体失败。",
      "批量删除 Python 包",
      { type: "warning" },
    );
    batchDeleting.value = true;
    const versionGroups = await Promise.all(
      selected.map((item) => studioApi.pythonPackages.versions(item.normalizedName)),
    );
    const ids = Array.from(new Set(
      versionGroups.flatMap((items) => items.map((item) => item.id).filter((id): id is EntityId => id != null))
        .map((id) => String(id)),
    ));
    if (ids.length === 0) {
      ElMessage.info("所选包没有可删除的版本");
      return;
    }
    if (ids.length > 200) {
      ElMessage.error("单次最多删除 200 个包版本，请减少选择数量");
      return;
    }
    await studioApi.environmentDependencies.batchDelete(ids);
    ElMessage.success(`已删除 ${selected.length} 个包，共 ${ids.length} 个版本`);
    if (selectedPackage.value
      && selected.some((item) => item.normalizedName === selectedPackage.value?.normalizedName)) {
      versionsVisible.value = false;
      versions.value = [];
      selectedPackage.value = undefined;
    }
    await loadPackages();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "批量删除失败");
    }
  } finally {
    batchDeleting.value = false;
  }
}

async function exportRequirements() {
  exporting.value = true;
  try {
    let blob: Blob;
    if (selectedPackages.value.length > 0) {
      const lines = [...selectedPackages.value]
        .filter((item) => item.name && item.latestVersion)
        .sort((left, right) => left.normalizedName.localeCompare(right.normalizedName))
        .map((item) => `${item.name}==${item.latestVersion}`);
      const manifest = [
        "# Generated by Data Aggregation Studio",
        "# Exact Python package versions from the selected rows",
        ...lines,
        "",
      ].join("\n");
      blob = new Blob([manifest], { type: "text/plain;charset=utf-8" });
    } else {
      blob = await studioApi.pythonPackages.exportRequirements({
        keyword: filters.keyword.trim() || undefined,
        enabled: filters.enabled,
      });
    }
    downloadBlob(blob, "requirements.txt");
    ElMessage.success(selectedPackages.value.length > 0 ? "已导出所选包清单" : "已导出当前筛选清单");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "导出清单失败");
  } finally {
    exporting.value = false;
  }
}

async function downloadVersion(row: EnvironmentDependencyListView) {
  const file = firstFile(row);
  if (!row.id || !file?.id) return;
  try {
    const blob = await studioApi.environmentDependencies.downloadFile(row.id, file.id);
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = file.originalFileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    void loadTodayDownloadCount();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "下载安装包失败");
  }
}

function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

async function copyInstallCommand(pkg: PythonPackageSummary) {
  const store = stores.value.find((item) => String(item.id) === String(pkg.artifactStoreId));
  const indexArg = store?.simpleIndexUrl ? ` --index-url ${store.simpleIndexUrl}` : "";
  const versionArg = pkg.latestVersion ? `==${pkg.latestVersion}` : "";
  const command = `pip install${indexArg} ${pkg.name}${versionArg}`;
  try {
    await navigator.clipboard.writeText(command);
    ElMessage.success("安装命令已复制");
  } catch {
    ElMessage.info(command);
  }
}

function visibleFiles(row: EnvironmentDependencyListView) {
  return (row.files ?? []).filter((item) => item.visible !== false);
}
function firstFile(row: EnvironmentDependencyListView): EnvironmentDependencyFileListView | undefined {
  return visibleFiles(row)[0];
}
function storeLabel(id?: EntityId) {
  if (id == null) return "未绑定实际仓库";
  const store = stores.value.find((item) => String(item.id) === String(id));
  return store ? `${store.storeName}（${store.provider}）` : `仓库 #${id}`;
}
function scopeLabel(scope?: string) {
  return scope === "PLATFORM" ? "平台" : "租户";
}
function artifactTypeLabel(type?: string) {
  if (type === "WHEEL") return "whl";
  if (type === "SDIST") return "tar.gz";
  return type || "-";
}
function formatFileSize(value?: number) {
  const size = Number(value ?? 0);
  if (!size) return "-";
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`;
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`;
}
function normalizeName(value: string) {
  return value.trim().toLowerCase().replace(/[-_.]+/g, "-");
}

onMounted(async () => {
  await Promise.all([loadStores(), loadPackages(), loadTodayDownloadCount()]);
});
</script>

<style scoped>
.download-count-badge {
  display: flex;
  align-items: baseline;
  gap: 4px;
  padding: 8px 14px;
  border: 1px solid rgba(64, 113, 187, 0.14);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.6);
  white-space: nowrap;
}
.download-count-label {
  font-size: 12px;
  color: var(--studio-text-soft);
}
.download-count-value {
  font-size: 22px;
  line-height: 1;
  color: var(--studio-primary-deep);
}
.download-count-unit {
  font-size: 12px;
  color: var(--studio-text-soft);
}
.package-filters {
  display: grid;
  grid-template-columns: 180px minmax(240px, 420px) auto auto;
  gap: 12px;
  margin-bottom: 16px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 18px;
}
.span-2 { grid-column: span 2; }
.dialog-alert { margin-bottom: 18px; }
.version-files { display: flex; flex-wrap: wrap; gap: 6px; }
.muted-text { color: var(--el-text-color-secondary); }
.table-pagination { display: flex; justify-content: flex-end; margin-top: 16px; }
@media (max-width: 760px) {
  .package-filters, .form-grid { grid-template-columns: 1fr; }
  .span-2 { grid-column: span 1; }
}
</style>
