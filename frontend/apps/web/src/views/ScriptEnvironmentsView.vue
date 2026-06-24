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
        <SectionCard title="依赖包" description="保存 JAR/ZIP 的可访问地址和校验信息；文件上传由前端或外部流程完成。">
          <div class="environment-filter-grid">
            <el-input v-model="dependencyFilters.keyword" clearable placeholder="名称、版本、URL 或描述" />
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
              <el-table-column prop="artifactType" label="类型" width="90" />
              <el-table-column prop="artifactUrl" label="文件地址" min-width="260" show-overflow-tooltip />
              <el-table-column prop="checksum" label="SHA-256" min-width="220" show-overflow-tooltip />
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

    <el-dialog v-model="dependencyDialogVisible" title="依赖包" width="640px">
      <div class="studio-form-grid">
        <el-form-item label="名称">
          <el-input v-model="dependencyForm.name" placeholder="mysql-driver" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="dependencyForm.version" placeholder="8.0.33" />
        </el-form-item>
        <el-form-item label="文件类型">
          <el-select v-model="dependencyForm.artifactType">
            <el-option label="JAR" value="JAR" />
            <el-option label="ZIP" value="ZIP" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="dependencyForm.enabled" />
        </el-form-item>
        <el-form-item label="文件地址" class="span-2">
          <el-input v-model="dependencyForm.artifactUrl" placeholder="https://oss.example.com/script-deps/mysql-driver.jar" />
        </el-form-item>
        <el-form-item label="SHA-256" class="span-2">
          <el-input v-model="dependencyForm.checksum" placeholder="可选；填写后执行前校验文件内容" />
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
import type {
  EntityId,
  EnvironmentDependency,
  EnvironmentDependencySaveRequest,
  ScriptEnvironment,
  ScriptEnvironmentSaveRequest,
} from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";

const activeTab = ref<"dependencies" | "environments">("dependencies");
const dependencyDialogVisible = ref(false);
const environmentDialogVisible = ref(false);
const dependencyOptions = ref<EnvironmentDependency[]>([]);

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
  items: [] as EnvironmentDependency[],
});
const environmentPage = reactive({
  pageNo: 1,
  pageSize: 20,
  total: 0,
  items: [] as ScriptEnvironment[],
});

const dependencyForm = reactive({
  id: undefined as EntityId | undefined,
  name: "",
  version: "",
  artifactUrl: "",
  artifactType: "JAR",
  checksum: "",
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

function openDependencyDialog(dependency?: EnvironmentDependency) {
  dependencyForm.id = dependency?.id;
  dependencyForm.name = dependency?.name ?? "";
  dependencyForm.version = dependency?.version ?? "";
  dependencyForm.artifactUrl = dependency?.artifactUrl ?? "";
  dependencyForm.artifactType = dependency?.artifactType || "JAR";
  dependencyForm.checksum = dependency?.checksum ?? "";
  dependencyForm.enabled = dependency?.enabled !== false;
  dependencyForm.description = dependency?.description ?? "";
  dependencyDialogVisible.value = true;
}

function openEnvironmentDialog(environment?: ScriptEnvironment) {
  environmentForm.id = environment?.id;
  environmentForm.environmentName = environment?.environmentName ?? "";
  environmentForm.environmentCode = environment?.environmentCode ?? "";
  environmentForm.enabled = environment?.enabled !== false;
  environmentForm.useApplicationParent = environment?.useApplicationParent !== false;
  environmentForm.dependencyIds = (environment?.dependencyIds ?? []).map((item) => String(item));
  environmentForm.description = environment?.description ?? "";
  environmentDialogVisible.value = true;
  void loadDependencyOptions();
}

async function saveDependency() {
  try {
    const payload: EnvironmentDependencySaveRequest = {
      id: dependencyForm.id,
      name: dependencyForm.name.trim(),
      version: dependencyForm.version.trim() || undefined,
      artifactUrl: dependencyForm.artifactUrl.trim(),
      artifactType: dependencyForm.artifactType,
      checksum: dependencyForm.checksum.trim() || undefined,
      enabled: dependencyForm.enabled,
      description: dependencyForm.description.trim() || undefined,
    };
    await studioApi.environmentDependencies.saveOrUpdateCheck(payload);
    dependencyDialogVisible.value = false;
    ElMessage.success("依赖包已保存");
    await Promise.all([loadDependencies(), loadDependencyOptions()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存依赖包失败");
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
    await studioApi.scriptEnvironments.saveOrUpdateCheck(payload);
    environmentDialogVisible.value = false;
    ElMessage.success("运行环境已保存");
    await loadEnvironments();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存运行环境失败");
  }
}

function buildDependencyActions(dependency: EnvironmentDependency) {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => openDependencyDialog(dependency) },
    dependency.enabled
      ? { key: "disable", label: "停用", onClick: () => updateDependencyEnabled(dependency, false) }
      : { key: "enable", label: "启用", type: "success", onClick: () => updateDependencyEnabled(dependency, true) },
    { key: "delete", label: "删除", type: "danger", onClick: () => deleteDependency(dependency) },
  ];
}

function buildEnvironmentActions(environment: ScriptEnvironment) {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => openEnvironmentDialog(environment) },
    { key: "refresh", label: "刷新", onClick: () => refreshEnvironment(environment) },
    environment.enabled
      ? { key: "disable", label: "停用", onClick: () => updateEnvironmentEnabled(environment, false) }
      : { key: "enable", label: "启用", type: "success", onClick: () => updateEnvironmentEnabled(environment, true) },
  ];
}

async function updateDependencyEnabled(dependency: EnvironmentDependency, enabled: boolean) {
  if (!dependency.id) {
    return;
  }
  try {
    if (enabled) {
      await studioApi.environmentDependencies.enable(dependency.id);
    } else {
      await studioApi.environmentDependencies.disable(dependency.id);
    }
    ElMessage.success(enabled ? "依赖包已启用" : "依赖包已停用");
    await Promise.all([loadDependencies(), loadDependencyOptions()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "更新依赖包状态失败");
  }
}

async function deleteDependency(dependency: EnvironmentDependency) {
  if (!dependency.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除依赖包 ${dependency.name} 吗？`, "确认", { type: "warning" });
    await studioApi.environmentDependencies.delete(dependency.id);
    ElMessage.success("依赖包已删除");
    await Promise.all([loadDependencies(), loadDependencyOptions(), loadEnvironments()]);
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除依赖包失败");
    }
  }
}

async function updateEnvironmentEnabled(environment: ScriptEnvironment, enabled: boolean) {
  if (!environment.id) {
    return;
  }
  try {
    if (enabled) {
      await studioApi.scriptEnvironments.enable(environment.id);
    } else {
      await studioApi.scriptEnvironments.disable(environment.id);
    }
    ElMessage.success(enabled ? "运行环境已启用" : "运行环境已停用");
    await loadEnvironments();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "更新运行环境状态失败");
  }
}

async function refreshEnvironment(environment: ScriptEnvironment) {
  if (!environment.id) {
    return;
  }
  try {
    await studioApi.scriptEnvironments.refresh(environment.id);
    ElMessage.success("运行环境缓存已刷新");
    await loadEnvironments();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "刷新运行环境失败");
  }
}

function formatDependencyNames(dependencies?: EnvironmentDependency[]) {
  if (!dependencies || dependencies.length === 0) {
    return "无额外依赖";
  }
  return dependencies.map(formatDependencyOption).join("，");
}

function formatDependencyOption(dependency: EnvironmentDependency) {
  return dependency.version ? `${dependency.name}@${dependency.version}` : dependency.name;
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

@media (max-width: 900px) {
  .environment-filter-grid {
    grid-template-columns: 1fr;
  }

  .environment-filter-actions {
    justify-content: flex-start;
  }
}
</style>
