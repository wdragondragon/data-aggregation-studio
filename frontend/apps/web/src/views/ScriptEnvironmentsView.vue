<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>运行环境管理</h3>
        <p>维护 Java 脚本可绑定的运行环境和依赖包元数据。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="refreshAll">刷新</el-button>
        <el-button v-if="activeTab === 'dependencies'" type="primary" @click="openDependencyDialog()">新建依赖包</el-button>
        <el-button v-else type="primary" @click="openEnvironmentDialog()">新建运行环境</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="environment-tabs">
      <el-tab-pane label="依赖包" name="dependencies">
        <SectionCard title="依赖包" description="上传 Java 脚本运行环境使用的 JAR/ZIP 文件。">
          <div class="environment-filter-grid">
            <el-input v-model="dependencyFilters.keyword" clearable placeholder="名称、版本、脚本类型或描述" />
            <el-select v-model="dependencyFilters.enabled" clearable placeholder="状态">
              <el-option label="启用" :value="true" />
              <el-option label="停用" :value="false" />
            </el-select>
            <div class="environment-filter-actions">
              <el-button type="primary" @click="loadDependencies">查询</el-button>
              <el-button plain @click="resetDependencyFilters">重置</el-button>
            </div>
          </div>

          <StudioTableShell min-width="1180px">
            <el-table :data="dependencyPage.items" border>
              <el-table-column label="序号" width="78" align="center" header-align="center">
                <template #default="{ $index }">
                  {{ (dependencyPage.pageNo - 1) * dependencyPage.pageSize + $index + 1 }}
                </template>
              </el-table-column>
              <el-table-column prop="name" label="名称" min-width="180" />
              <el-table-column prop="version" label="版本" min-width="120" />
              <el-table-column label="脚本类型" width="110">
                <template #default="{ row }">
                  {{ formatScriptType(row.scriptType) }}
                </template>
              </el-table-column>
              <el-table-column label="文件" min-width="300">
                <template #default="{ row }">
                  <div v-if="dependencyVisibleFiles(row).length" class="dependency-file-summary">
                    <el-tag
                      v-for="file in dependencyVisibleFiles(row).slice(0, 3)"
                      :key="file.id ?? file.originalFileName"
                      size="small"
                      effect="plain"
                      class="dependency-file-tag"
                    >
                      {{ file.originalFileName }} · {{ file.artifactType }} · {{ formatFileSize(file.sizeBytes) }}
                    </el-tag>
                    <span v-if="dependencyVisibleFiles(row).length > 3" class="dependency-file-more">
                      +{{ dependencyVisibleFiles(row).length - 3 }}
                    </span>
                  </div>
                  <span v-else class="muted-text">未上传文件</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100" align="center" header-align="center">
                <template #default="{ row }">
                  <StatusPill :label="row.enabled ? '启用' : '停用'" :tone="row.enabled ? 'success' : 'neutral'" />
                </template>
              </el-table-column>
              <el-table-column prop="updatedAt" label="更新时间" min-width="170" />
              <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
                <template #default="{ row }">
                  <OverflowActionGroup :items="buildDependencyActions(row)" />
                </template>
              </el-table-column>
            </el-table>
          </StudioTableShell>

          <div class="table-pagination">
            <el-pagination
              v-model:current-page="dependencyPage.pageNo"
              v-model:page-size="dependencyPage.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="[10, 20, 50, 100]"
              :total="dependencyPage.total"
              @current-change="loadDependencies"
              @size-change="handleDependencyPageSizeChange"
            />
          </div>
        </SectionCard>
      </el-tab-pane>

      <el-tab-pane label="运行环境" name="environments">
        <SectionCard title="运行环境" description="运行环境决定 Java 脚本编译执行时可访问的依赖包和父加载器策略。">
          <div class="environment-filter-grid">
            <el-input v-model="environmentFilters.keyword" clearable placeholder="名称、编码或描述" />
            <el-select v-model="environmentFilters.enabled" clearable placeholder="状态">
              <el-option label="启用" :value="true" />
              <el-option label="停用" :value="false" />
            </el-select>
            <div class="environment-filter-actions">
              <el-button type="primary" @click="loadEnvironments">查询</el-button>
              <el-button plain @click="resetEnvironmentFilters">重置</el-button>
            </div>
          </div>

          <StudioTableShell min-width="1180px">
            <el-table :data="environmentPage.items" border>
              <el-table-column label="序号" width="78" align="center" header-align="center">
                <template #default="{ $index }">
                  {{ (environmentPage.pageNo - 1) * environmentPage.pageSize + $index + 1 }}
                </template>
              </el-table-column>
              <el-table-column prop="environmentName" label="名称" min-width="180" />
              <el-table-column prop="environmentCode" label="编码" min-width="170" />
              <el-table-column label="父加载器" min-width="150">
                <template #default="{ row }">
                  {{ row.useApplicationParent ? "应用作为父加载器" : "隔离加载器" }}
                </template>
              </el-table-column>
              <el-table-column label="依赖包" min-width="220">
                <template #default="{ row }">
                  {{ formatDependencyNames(row.dependencies) }}
                </template>
              </el-table-column>
              <el-table-column prop="environmentVersion" label="版本" width="90" />
              <el-table-column label="状态" width="100" align="center" header-align="center">
                <template #default="{ row }">
                  <StatusPill :label="row.enabled ? '启用' : '停用'" :tone="row.enabled ? 'success' : 'neutral'" />
                </template>
              </el-table-column>
              <el-table-column prop="updatedAt" label="更新时间" min-width="170" />
              <el-table-column label="操作" width="170" align="center" header-align="center" fixed="right">
                <template #default="{ row }">
                  <OverflowActionGroup :items="buildEnvironmentActions(row)" />
                </template>
              </el-table-column>
            </el-table>
          </StudioTableShell>

          <div class="table-pagination">
            <el-pagination
              v-model:current-page="environmentPage.pageNo"
              v-model:page-size="environmentPage.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="[10, 20, 50, 100]"
              :total="environmentPage.total"
              @current-change="loadEnvironments"
              @size-change="handleEnvironmentPageSizeChange"
            />
          </div>
        </SectionCard>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dependencyDialogVisible" title="依赖包" width="760px">
      <div class="studio-form-grid">
        <el-form-item label="名称">
          <el-input v-model="dependencyForm.name" placeholder="mysql-driver" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="dependencyForm.version" placeholder="8.0.33" />
        </el-form-item>
        <el-form-item label="脚本类型">
          <el-select v-model="dependencyForm.scriptType">
            <el-option label="Java" value="JAVA" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="dependencyForm.enabled" />
        </el-form-item>
        <el-form-item label="依赖文件" class="span-2">
          <el-upload
            v-model:file-list="dependencyUploadFiles"
            accept=".jar,.zip"
            :auto-upload="false"
            name="files"
            multiple
            :on-change="handleDependencyFileChange"
            :on-remove="handleDependencyFileRemove"
          >
            <el-button>选择 JAR/ZIP 文件</el-button>
            <template #tip>
              <div class="upload-tip">
                {{ dependencyForm.id ? "可一次选择多个文件；与已上传文件同名时会覆盖。" : "新建依赖包必须上传 JAR 或包含 JAR 的 ZIP。" }}
              </div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item v-if="dependencyForm.id" label="已上传文件" class="span-2">
          <div class="dependency-file-list">
            <div
              v-for="file in dependencyForm.uploadedFiles"
              :key="file.id ?? file.originalFileName"
              class="dependency-file-row"
            >
              <div class="dependency-file-main">
                <span class="dependency-file-name">{{ file.originalFileName }}</span>
                <span class="dependency-file-meta">{{ file.artifactType }} · {{ formatFileSize(file.sizeBytes) }}</span>
              </div>
              <div class="dependency-file-actions">
                <el-button link type="primary" :disabled="!file.id" @click="downloadDependencyFile(file)">下载</el-button>
                <el-button link type="danger" :disabled="!file.id" @click="deleteDependencyFile(file)">删除</el-button>
              </div>
            </div>
            <div v-if="dependencyForm.uploadedFiles.length === 0" class="dependency-file-empty">暂无已上传文件</div>
          </div>
        </el-form-item>
        <el-form-item label="描述" class="span-2">
          <el-input v-model="dependencyForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </div>
      <template #footer>
        <el-button @click="dependencyDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDependency">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="environmentDialogVisible" title="运行环境" width="720px">
      <div class="studio-form-grid">
        <el-form-item label="名称">
          <el-input v-model="environmentForm.environmentName" placeholder="客户脚本运行环境" />
        </el-form-item>
        <el-form-item label="编码">
          <el-input v-model="environmentForm.environmentCode" placeholder="customer-script-env" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="environmentForm.enabled" />
        </el-form-item>
        <el-form-item label="父加载器">
          <el-switch
            v-model="environmentForm.useApplicationParent"
            active-text="使用应用作为父加载器"
            inactive-text="隔离加载器"
          />
        </el-form-item>
        <el-form-item label="依赖包" class="span-2">
          <el-select v-model="environmentForm.dependencyIds" multiple filterable clearable placeholder="选择依赖包">
            <el-option
              v-for="dependency in dependencyOptions"
              :key="dependency.id"
              :label="formatDependencyOption(dependency)"
              :value="String(dependency.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" class="span-2">
          <el-input v-model="environmentForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </div>
      <template #footer>
        <el-button @click="environmentDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEnvironment">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type { UploadFile, UploadFiles, UploadUserFile } from "element-plus";
import type {
  EntityId,
  EnvironmentDependency,
  EnvironmentDependencyListView,
  EnvironmentDependencyOption,
  EnvironmentDependencyFile,
  EnvironmentDependencyFileListView,
  ScriptType,
  ScriptEnvironment,
  ScriptEnvironmentListView,
  ScriptEnvironmentSaveRequest,
} from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";

type VisibleDependencyFile = EnvironmentDependencyFile | EnvironmentDependencyFileListView;

const activeTab = ref<"dependencies" | "environments">("dependencies");
const dependencyDialogVisible = ref(false);
const environmentDialogVisible = ref(false);
const dependencyOptions = ref<EnvironmentDependencyOption[]>([]);
const dependencyUploadFiles = ref<UploadUserFile[]>([]);

const dependencyFilters = reactive<{ keyword: string; enabled?: boolean }>({
  keyword: "",
  enabled: undefined,
});
const environmentFilters = reactive<{ keyword: string; enabled?: boolean }>({
  keyword: "",
  enabled: undefined,
});

const dependencyPage = reactive({
  pageNo: 1,
  pageSize: 20,
  total: 0,
  items: [] as EnvironmentDependencyListView[],
});
const environmentPage = reactive({
  pageNo: 1,
  pageSize: 20,
  total: 0,
  items: [] as ScriptEnvironmentListView[],
});

const dependencyForm = reactive({
  id: undefined as EntityId | undefined,
  name: "",
  version: "",
  scriptType: "JAVA" as ScriptType,
  files: [] as File[],
  uploadedFiles: [] as EnvironmentDependencyFile[],
  enabled: true,
  description: "",
});
const environmentForm = reactive({
  id: undefined as EntityId | undefined,
  environmentName: "",
  environmentCode: "",
  enabled: true,
  useApplicationParent: true,
  dependencyIds: [] as EntityId[],
  description: "",
});

async function refreshAll() {
  await Promise.all([
    loadDependencies(),
    loadEnvironments(),
    loadDependencyOptions(),
  ]);
}

async function loadDependencies() {
  try {
    const result = await studioApi.environmentDependencies.queryPage({
      pageNum: dependencyPage.pageNo,
      pageSize: dependencyPage.pageSize,
      keyword: dependencyFilters.keyword.trim() || undefined,
      enabled: dependencyFilters.enabled,
    });
    dependencyPage.pageNo = Number(result.pageNo ?? 1);
    dependencyPage.pageSize = Number(result.pageSize ?? dependencyPage.pageSize);
    dependencyPage.total = Number(result.total ?? 0);
    dependencyPage.items = result.items;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载依赖包失败");
  }
}

async function loadEnvironments() {
  try {
    const result = await studioApi.scriptEnvironments.queryPage({
      pageNum: environmentPage.pageNo,
      pageSize: environmentPage.pageSize,
      keyword: environmentFilters.keyword.trim() || undefined,
      enabled: environmentFilters.enabled,
    });
    environmentPage.pageNo = Number(result.pageNo ?? 1);
    environmentPage.pageSize = Number(result.pageSize ?? environmentPage.pageSize);
    environmentPage.total = Number(result.total ?? 0);
    environmentPage.items = result.items;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载运行环境失败");
  }
}

async function loadDependencyOptions() {
  try {
    dependencyOptions.value = await studioApi.environmentDependencies.options({ enabledOnly: true });
  } catch (error) {
    dependencyOptions.value = [];
  }
}

function resetDependencyFilters() {
  dependencyFilters.keyword = "";
  dependencyFilters.enabled = undefined;
  dependencyPage.pageNo = 1;
  void loadDependencies();
}

function resetEnvironmentFilters() {
  environmentFilters.keyword = "";
  environmentFilters.enabled = undefined;
  environmentPage.pageNo = 1;
  void loadEnvironments();
}

function handleDependencyPageSizeChange() {
  dependencyPage.pageNo = 1;
  void loadDependencies();
}

function handleEnvironmentPageSizeChange() {
  environmentPage.pageNo = 1;
  void loadEnvironments();
}

function openDependencyDialog(dependency?: EnvironmentDependencyListView) {
  dependencyForm.id = dependency?.id;
  dependencyForm.name = dependency?.name ?? "";
  dependencyForm.version = dependency?.version ?? "";
  dependencyForm.scriptType = (dependency?.scriptType ?? "JAVA") as ScriptType;
  dependencyForm.files = [];
  dependencyForm.uploadedFiles = [];
  dependencyForm.enabled = dependency?.enabled !== false;
  dependencyForm.description = "";
  dependencyUploadFiles.value = [];
  dependencyDialogVisible.value = true;
  if (dependency?.id) {
    void loadDependencyDetailIntoForm(dependency.id);
  }
}

function handleDependencyFileChange(_uploadFile: UploadFile, uploadFiles: UploadFiles) {
  dependencyUploadFiles.value = uploadFiles;
  dependencyForm.files = uploadFiles
    .map((item) => item.raw as File | undefined)
    .filter((item): item is File => Boolean(item));
}

function handleDependencyFileRemove(_uploadFile: UploadFile, uploadFiles: UploadFiles) {
  dependencyUploadFiles.value = uploadFiles;
  dependencyForm.files = uploadFiles
    .map((item) => item.raw as File | undefined)
    .filter((item): item is File => Boolean(item));
}

function openEnvironmentDialog(environment?: ScriptEnvironmentListView) {
  environmentForm.id = environment?.id;
  environmentForm.environmentName = environment?.environmentName ?? "";
  environmentForm.environmentCode = environment?.environmentCode ?? "";
  environmentForm.enabled = environment?.enabled !== false;
  environmentForm.useApplicationParent = environment?.useApplicationParent !== false;
  environmentForm.dependencyIds = (environment?.dependencyIds ?? []).map((item) => String(item));
  environmentForm.description = "";
  environmentDialogVisible.value = true;
  void loadDependencyOptions();
  if (environment?.id) {
    void loadEnvironmentDetailIntoForm(environment.id);
  }
}

async function saveDependency() {
  try {
    const name = dependencyForm.name.trim();
    if (!name) {
      ElMessage.error("请填写依赖包名称");
      return;
    }
    if (!dependencyForm.id && dependencyForm.files.length === 0) {
      ElMessage.error("请上传依赖文件");
      return;
    }
    const payload = new FormData();
    if (dependencyForm.id != null) {
      payload.append("id", String(dependencyForm.id));
    }
    payload.append("name", name);
    if (dependencyForm.version.trim()) {
      payload.append("version", dependencyForm.version.trim());
    }
    payload.append("scriptType", dependencyForm.scriptType);
    payload.append("enabled", String(dependencyForm.enabled));
    if (dependencyForm.description.trim()) {
      payload.append("description", dependencyForm.description.trim());
    }
    for (const file of dependencyForm.files) {
      payload.append("files", file);
    }
    const saved = await studioApi.environmentDependencies.saveOrUpdateCheckForm(payload);
    dependencyDialogVisible.value = false;
    ElMessage.success("依赖包已保存");
    upsertDependencyRow(toDependencyListItem(saved));
    upsertDependencyOption(saved);
    updateEnvironmentDependencyReference(saved);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存依赖包失败");
  }
}

async function loadDependencyDetailIntoForm(id: EntityId) {
  try {
    const latest = await studioApi.environmentDependencies.get(id);
    if (String(dependencyForm.id) !== String(id)) {
      return;
    }
    dependencyForm.name = latest.name;
    dependencyForm.version = latest.version ?? "";
    dependencyForm.scriptType = latest.scriptType as ScriptType;
    dependencyForm.enabled = latest.enabled !== false;
    dependencyForm.description = latest.description ?? "";
    dependencyForm.uploadedFiles = uploadedDependencyFiles(latest);
  } catch {
    dependencyForm.uploadedFiles = [];
  }
}

async function loadEnvironmentDetailIntoForm(id: EntityId) {
  try {
    const latest = await studioApi.scriptEnvironments.get(id);
    if (String(environmentForm.id) !== String(id)) {
      return;
    }
    environmentForm.environmentName = latest.environmentName;
    environmentForm.environmentCode = latest.environmentCode;
    environmentForm.enabled = latest.enabled !== false;
    environmentForm.useApplicationParent = latest.useApplicationParent !== false;
    environmentForm.dependencyIds = (latest.dependencyIds ?? []).map((item) => String(item));
    environmentForm.description = latest.description ?? "";
  } catch {
    environmentForm.description = "";
  }
}

async function downloadDependencyFile(file: EnvironmentDependencyFile) {
  if (!dependencyForm.id || !file.id) {
    return;
  }
  try {
    const blob = await studioApi.environmentDependencies.downloadFile(dependencyForm.id, file.id);
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = file.originalFileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "下载依赖文件失败");
  }
}

async function deleteDependencyFile(file: EnvironmentDependencyFile) {
  if (!dependencyForm.id || !file.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除文件 ${file.originalFileName} 吗？`, "确认", { type: "warning" });
    await studioApi.environmentDependencies.deleteFile(dependencyForm.id, file.id);
    const latest = await studioApi.environmentDependencies.get(dependencyForm.id);
    dependencyForm.uploadedFiles = uploadedDependencyFiles(latest);
    upsertDependencyRow(toDependencyListItem(latest));
    ElMessage.success("文件已删除");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除依赖文件失败");
    }
  }
}

async function saveEnvironment() {
  try {
    const payload: ScriptEnvironmentSaveRequest = {
      id: environmentForm.id,
      environmentName: environmentForm.environmentName.trim(),
      environmentCode: environmentForm.environmentCode.trim(),
      enabled: environmentForm.enabled,
      useApplicationParent: environmentForm.useApplicationParent,
      dependencyIds: environmentForm.dependencyIds,
      description: environmentForm.description.trim() || undefined,
    };
    const saved = await studioApi.scriptEnvironments.saveOrUpdateCheck(payload);
    environmentDialogVisible.value = false;
    ElMessage.success("运行环境已保存");
    upsertEnvironmentRow(toScriptEnvironmentListItem(saved));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存运行环境失败");
  }
}

function buildDependencyActions(dependency: EnvironmentDependencyListView) {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => openDependencyDialog(dependency) },
    dependency.enabled
      ? { key: "disable", label: "停用", onClick: () => updateDependencyEnabled(dependency, false) }
      : { key: "enable", label: "启用", type: "success", onClick: () => updateDependencyEnabled(dependency, true) },
    { key: "delete", label: "删除", type: "danger", onClick: () => deleteDependency(dependency) },
  ];
}

function buildEnvironmentActions(environment: ScriptEnvironmentListView) {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => openEnvironmentDialog(environment) },
    { key: "refresh", label: "刷新", onClick: () => refreshEnvironment(environment) },
    environment.enabled
      ? { key: "disable", label: "停用", onClick: () => updateEnvironmentEnabled(environment, false) }
      : { key: "enable", label: "启用", type: "success", onClick: () => updateEnvironmentEnabled(environment, true) },
  ];
}

async function updateDependencyEnabled(dependency: EnvironmentDependencyListView, enabled: boolean) {
  if (!dependency.id) {
    return;
  }
  try {
    let updated: EnvironmentDependency;
    if (enabled) {
      updated = await studioApi.environmentDependencies.enable(dependency.id);
    } else {
      updated = await studioApi.environmentDependencies.disable(dependency.id);
    }
    ElMessage.success(enabled ? "依赖包已启用" : "依赖包已停用");
    upsertDependencyRow(toDependencyListItem(updated));
    upsertDependencyOption(updated);
    updateEnvironmentDependencyReference(updated);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "更新依赖包状态失败");
  }
}

async function deleteDependency(dependency: EnvironmentDependencyListView) {
  if (!dependency.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除依赖包 ${dependency.name} 吗？`, "确认", { type: "warning" });
    await studioApi.environmentDependencies.delete(dependency.id);
    ElMessage.success("依赖包已删除");
    removeDependencyRow(dependency.id);
    dependencyOptions.value = dependencyOptions.value.filter((item) => String(item.id) !== String(dependency.id));
    removeDependencyFromEnvironmentRows(dependency.id);
    if (dependencyPage.items.length === 0 && dependencyPage.pageNo > 1 && dependencyPage.total > 0) {
      dependencyPage.pageNo -= 1;
      await loadDependencies();
    }
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除依赖包失败");
    }
  }
}

async function updateEnvironmentEnabled(environment: ScriptEnvironmentListView, enabled: boolean) {
  if (!environment.id) {
    return;
  }
  try {
    let updated: ScriptEnvironment;
    if (enabled) {
      updated = await studioApi.scriptEnvironments.enable(environment.id);
    } else {
      updated = await studioApi.scriptEnvironments.disable(environment.id);
    }
    ElMessage.success(enabled ? "运行环境已启用" : "运行环境已停用");
    upsertEnvironmentRow(toScriptEnvironmentListItem(updated));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "更新运行环境状态失败");
  }
}

async function refreshEnvironment(environment: ScriptEnvironmentListView) {
  if (!environment.id) {
    return;
  }
  try {
    const updated = await studioApi.scriptEnvironments.refresh(environment.id);
    ElMessage.success("运行环境缓存已刷新");
    upsertEnvironmentRow(toScriptEnvironmentListItem(updated));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "刷新运行环境失败");
  }
}

function toDependencyListItem(dependency: EnvironmentDependency): EnvironmentDependencyListView {
  return {
    id: dependency.id,
    tenantId: dependency.tenantId,
    deleted: dependency.deleted,
    createdAt: dependency.createdAt,
    updatedAt: dependency.updatedAt,
    name: dependency.name,
    version: dependency.version,
    scriptType: dependency.scriptType,
    enabled: dependency.enabled,
    files: uploadedDependencyFiles(dependency).map(toDependencyFileListItem),
  };
}

function toDependencyFileListItem(file: EnvironmentDependencyFile | EnvironmentDependencyFileListView): EnvironmentDependencyFileListView {
  return {
    id: file.id,
    tenantId: file.tenantId,
    deleted: file.deleted,
    createdAt: file.createdAt,
    updatedAt: file.updatedAt,
    dependencyId: file.dependencyId,
    originalFileName: file.originalFileName,
    artifactType: file.artifactType,
    sizeBytes: file.sizeBytes,
    visible: file.visible,
    enabled: file.enabled,
  };
}

function toDependencyOption(dependency: EnvironmentDependency): EnvironmentDependencyOption {
  return {
    id: dependency.id,
    tenantId: dependency.tenantId,
    deleted: dependency.deleted,
    createdAt: dependency.createdAt,
    updatedAt: dependency.updatedAt,
    name: dependency.name,
    version: dependency.version,
    scriptType: dependency.scriptType,
    enabled: dependency.enabled,
  };
}

function toScriptEnvironmentListItem(environment: ScriptEnvironment): ScriptEnvironmentListView {
  return {
    id: environment.id,
    tenantId: environment.tenantId,
    deleted: environment.deleted,
    createdAt: environment.createdAt,
    updatedAt: environment.updatedAt,
    environmentName: environment.environmentName,
    environmentCode: environment.environmentCode,
    enabled: environment.enabled,
    useApplicationParent: environment.useApplicationParent,
    environmentVersion: environment.environmentVersion,
    dependencyIds: environment.dependencyIds ?? [],
    dependencies: (environment.dependencies ?? []).map(toDependencyOption),
  };
}

function upsertDependencyRow(dependency: EnvironmentDependencyListView) {
  const index = dependencyPage.items.findIndex((item) => String(item.id) === String(dependency.id));
  if (!matchesDependencyFilters(dependency)) {
    if (index >= 0) {
      dependencyPage.items.splice(index, 1);
      dependencyPage.total = Math.max(0, dependencyPage.total - 1);
    }
    return;
  }
  if (index >= 0) {
    dependencyPage.items.splice(index, 1, dependency);
    return;
  }
  dependencyPage.items.unshift(dependency);
  dependencyPage.items = dependencyPage.items.slice(0, dependencyPage.pageSize);
  dependencyPage.total += 1;
}

function removeDependencyRow(id: EntityId) {
  const beforeLength = dependencyPage.items.length;
  dependencyPage.items = dependencyPage.items.filter((item) => String(item.id) !== String(id));
  if (dependencyPage.items.length !== beforeLength) {
    dependencyPage.total = Math.max(0, dependencyPage.total - 1);
  }
}

function upsertDependencyOption(dependency: EnvironmentDependency) {
  if (dependency.enabled === false) {
    dependencyOptions.value = dependencyOptions.value.filter((item) => String(item.id) !== String(dependency.id));
    return;
  }
  const option = toDependencyOption(dependency);
  const index = dependencyOptions.value.findIndex((item) => String(item.id) === String(option.id));
  if (index >= 0) {
    dependencyOptions.value = dependencyOptions.value.map((item, itemIndex) => itemIndex === index ? option : item);
    return;
  }
  dependencyOptions.value = [option, ...dependencyOptions.value];
}

function updateEnvironmentDependencyReference(dependency: EnvironmentDependency) {
  const option = toDependencyOption(dependency);
  environmentPage.items = environmentPage.items.map((environment) => {
    if (!(environment.dependencyIds ?? []).some((id) => String(id) === String(dependency.id))) {
      return environment;
    }
    return {
      ...environment,
      dependencies: (environment.dependencies ?? []).map((item) => String(item.id) === String(dependency.id) ? option : item),
    };
  });
}

function removeDependencyFromEnvironmentRows(id: EntityId) {
  environmentPage.items = environmentPage.items.map((environment) => ({
    ...environment,
    dependencyIds: (environment.dependencyIds ?? []).filter((item) => String(item) !== String(id)),
    dependencies: (environment.dependencies ?? []).filter((item) => String(item.id) !== String(id)),
  }));
}

function upsertEnvironmentRow(environment: ScriptEnvironmentListView) {
  const index = environmentPage.items.findIndex((item) => String(item.id) === String(environment.id));
  if (!matchesEnvironmentFilters(environment)) {
    if (index >= 0) {
      environmentPage.items.splice(index, 1);
      environmentPage.total = Math.max(0, environmentPage.total - 1);
    }
    return;
  }
  if (index >= 0) {
    environmentPage.items.splice(index, 1, environment);
    return;
  }
  environmentPage.items.unshift(environment);
  environmentPage.items = environmentPage.items.slice(0, environmentPage.pageSize);
  environmentPage.total += 1;
}

function matchesDependencyFilters(dependency: EnvironmentDependencyListView) {
  if (dependencyFilters.enabled != null && dependency.enabled !== dependencyFilters.enabled) {
    return false;
  }
  const keyword = dependencyFilters.keyword.trim().toLowerCase();
  if (!keyword) {
    return true;
  }
  return [dependency.name, dependency.version, dependency.scriptType]
    .some((value) => String(value ?? "").toLowerCase().includes(keyword));
}

function matchesEnvironmentFilters(environment: ScriptEnvironmentListView) {
  if (environmentFilters.enabled != null && environment.enabled !== environmentFilters.enabled) {
    return false;
  }
  const keyword = environmentFilters.keyword.trim().toLowerCase();
  if (!keyword) {
    return true;
  }
  return [environment.environmentName, environment.environmentCode]
    .some((value) => String(value ?? "").toLowerCase().includes(keyword));
}

function formatDependencyNames(dependencies?: Array<Pick<EnvironmentDependencyOption, "name" | "version" | "scriptType">>) {
  if (!dependencies || dependencies.length === 0) {
    return "无额外依赖";
  }
  return dependencies.map(formatDependencyOption).join("，");
}

function dependencyVisibleFiles(dependency?: { files?: VisibleDependencyFile[] }) {
  return (dependency?.files ?? []).filter((file) => file.visible !== false && file.enabled !== false);
}

function uploadedDependencyFiles(dependency?: EnvironmentDependency) {
  return (dependency?.files ?? []).filter((file) => file.visible !== false && file.enabled !== false);
}

function formatDependencyOption(dependency: Pick<EnvironmentDependencyOption, "name" | "version" | "scriptType">) {
  const name = dependency.version ? `${dependency.name}@${dependency.version}` : dependency.name;
  return `${formatScriptType(dependency.scriptType)} / ${name}`;
}

function formatFileSize(value?: number) {
  if (value == null || Number.isNaN(Number(value))) {
    return "-";
  }
  const size = Number(value);
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function formatScriptType(scriptType?: string) {
  if (scriptType === "JAVA") {
    return "Java";
  }
  if (scriptType === "SQL") {
    return "SQL";
  }
  if (scriptType === "PYTHON") {
    return "Python";
  }
  return scriptType || "Java";
}

onMounted(refreshAll);
</script>

<style scoped>
.environment-tabs {
  display: grid;
  gap: 12px;
}

.environment-filter-grid {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(160px, 220px) auto;
  gap: 12px;
  align-items: end;
  margin-bottom: 12px;
}

.environment-filter-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}

.span-2 {
  grid-column: span 2;
}

.upload-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
  margin-top: 6px;
}

.muted-text {
  color: var(--el-text-color-secondary);
}

.dependency-file-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.dependency-file-tag {
  max-width: 100%;
}

.dependency-file-more {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.dependency-file-list {
  display: grid;
  gap: 8px;
  width: 100%;
}

.dependency-file-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  min-height: 34px;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
}

.dependency-file-main {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.dependency-file-name {
  overflow: hidden;
  color: var(--el-text-color-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dependency-file-meta,
.dependency-file-empty {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.dependency-file-actions {
  display: flex;
  flex: none;
  gap: 6px;
}

@media (max-width: 900px) {
  .environment-filter-grid {
    grid-template-columns: 1fr;
  }

  .environment-filter-actions {
    justify-content: flex-start;
  }

  .dependency-file-row {
    grid-template-columns: 1fr;
  }

  .dependency-file-actions {
    justify-content: flex-start;
  }
}
</style>
