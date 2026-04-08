<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.dataDevelopment.heading") }}</h3>
        <p>{{ t("web.dataDevelopment.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="refreshAll">{{ t("common.refresh") }}</el-button>
        <el-button plain :disabled="!hasCurrentProject" @click="openDirectoryDialog()">{{ t("web.dataDevelopment.newDirectory") }}</el-button>
        <el-button type="primary" :disabled="!hasCurrentProject" @click="createNewScript">{{ t("web.dataDevelopment.newScript") }}</el-button>
        <el-button type="success" :disabled="!canExecuteCurrentScript" @click="executeCurrentScript">{{ executeButtonLabel }}</el-button>
        <el-button type="primary" :disabled="!canSaveCurrentScript" @click="saveScript">{{ t("web.dataDevelopment.saveScript") }}</el-button>
        <el-button plain :disabled="!canMoveSelectedNode" @click="openMoveDialog">{{ t("web.dataDevelopment.moveNode") }}</el-button>
        <el-button type="danger" plain :disabled="!canDeleteSelectedNode" @click="deleteSelectedNode">{{ t("common.delete") }}</el-button>
      </div>
    </div>

    <div class="data-development-layout">
      <SectionCard :title="t('web.dataDevelopment.treeTitle')" :description="t('web.dataDevelopment.treeDescription')">
        <div class="tree-toolbar">
          <div class="soft-panel tree-context">
            <strong>当前项目</strong>
            <p>{{ currentProjectLabel }}</p>
          </div>
          <div class="soft-panel tree-context">
            <strong>{{ t("web.dataDevelopment.currentDirectory") }}</strong>
            <p>{{ currentDirectoryLabel }}</p>
          </div>
        </div>

        <el-tree
          v-if="treeData.length"
          :data="treeData"
          node-key="nodeKey"
          default-expand-all
          highlight-current
          :props="{ label: 'name', children: 'children' }"
          @node-click="handleTreeClick"
        >
          <template #default="slotProps">
            <div class="tree-node">
              <div class="tree-node__content">
                <strong>{{ slotProps?.data?.name }}</strong>
                <span v-if="slotProps?.data?.nodeType === 'DIRECTORY'">
                  {{ slotProps?.data?.permissionCode || t("common.none") }}
                </span>
                <span v-else>
                  {{ formatScriptType(t, slotProps?.data?.scriptType) }} · {{ slotProps?.data?.datasourceName || t("common.none") }}
                </span>
              </div>
              <el-tag
                v-if="slotProps?.data?.nodeType === 'SCRIPT' && isSharedScriptNode(slotProps?.data)"
                type="warning"
                size="small"
                effect="plain"
              >
                共享 · {{ resolveProjectLabel(slotProps?.data?.projectId) }}
              </el-tag>
            </div>
          </template>
        </el-tree>
        <div v-else class="soft-panel">{{ t("web.dataDevelopment.emptyTree") }}</div>
      </SectionCard>

      <SectionCard :title="t('web.dataDevelopment.editorTitle')" :description="t('web.dataDevelopment.editorDescription')">
        <template v-if="scriptEditorVisible">
          <div class="studio-form-grid">
            <el-form-item :label="t('web.dataDevelopment.currentDirectory')">
              <el-input :model-value="currentDirectoryLabel" disabled />
            </el-form-item>
            <el-form-item :label="t('web.dataDevelopment.scriptName')">
              <el-input v-model="scriptForm.fileName" :placeholder="t('web.dataDevelopment.scriptNamePlaceholder')" />
            </el-form-item>
            <el-form-item :label="t('web.dataDevelopment.scriptType')">
              <el-select v-model="scriptForm.scriptType">
                <el-option
                  v-for="option in scriptTypeOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                  :disabled="option.disabled"
                />
              </el-select>
            </el-form-item>
            <el-form-item v-if="currentScriptRegistryEntry.requiresDatasource" :label="t('web.dataDevelopment.datasource')">
              <el-select v-model="scriptForm.datasourceId" filterable :placeholder="t('web.dataDevelopment.datasourcePlaceholder')">
                <el-option
                  v-for="datasource in sqlDatasources"
                  :key="datasource.id"
                  :label="`${datasource.name} (${datasource.typeCode})`"
                  :value="String(datasource.id)"
                />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('web.dataDevelopment.scriptDescription')" class="span-2">
              <el-input v-model="scriptForm.description" />
            </el-form-item>
          </div>

          <div v-if="isCurrentScriptShared" class="soft-panel shared-script-note">
            共享脚本来自项目 {{ resolveProjectLabel(scriptForm.projectId) }}，当前项目仅支持查看和执行，不能直接保存、移动或删除。
          </div>

          <div v-if="isStructuredScriptType" class="soft-panel java-script-hint">
            <strong>{{ scriptTemplateTitle }}</strong>
            <p>{{ scriptTemplateDescription }}</p>
          </div>

          <el-form-item :label="t('web.dataDevelopment.content')" class="editor-form-item">
            <ScriptEditorPanel
              v-model="scriptForm.content"
              :script-type="scriptForm.scriptType"
              :placeholder="t('web.dataDevelopment.contentPlaceholder')"
              :sql-hints="currentSqlHints"
            />
          </el-form-item>

          <el-form-item v-if="supportsExecutionArguments" :label="t('web.dataDevelopment.scriptArguments')" class="editor-form-item">
            <el-input
              v-model="scriptExecutionArgumentsText"
              type="textarea"
              :rows="6"
              :placeholder="t('web.dataDevelopment.scriptArgumentsPlaceholder')"
            />
          </el-form-item>

          <SectionCard :title="t('web.dataDevelopment.executionTitle')" :description="t('web.dataDevelopment.executionDescription')">
            <template v-if="executionResult">
              <div class="execution-summary">
                <div class="soft-panel">
                  <strong>{{ t("web.dataDevelopment.executionStatus") }}</strong>
                  <p>{{ formatStatusLabel(t, executionResult.status) }}</p>
                </div>
                <div class="soft-panel">
                  <strong>{{ t("web.dataDevelopment.executionMs") }}</strong>
                  <p>{{ executionResult.executionMs ?? 0 }} ms</p>
                </div>
                <div v-if="sqlExecutionResult" class="soft-panel">
                  <strong>{{ t("web.dataDevelopment.statementCount") }}</strong>
                  <p>{{ sqlExecutionResult.statementCount ?? executionResults.length }}</p>
                </div>
                <div v-if="sqlExecutionResult" class="soft-panel">
                  <strong>{{ t("web.dataDevelopment.resultRows") }}</strong>
                  <p>{{ currentExecutionResult?.rows?.length ?? 0 }}</p>
                </div>
                <div v-if="sqlExecutionResult" class="soft-panel">
                  <strong>{{ t("web.dataDevelopment.resultColumns") }}</strong>
                  <p>{{ currentExecutionResult?.columns?.length ?? 0 }}</p>
                </div>
                <div v-if="sqlExecutionResult" class="soft-panel">
                  <strong>{{ t("web.dataDevelopment.affectedRows") }}</strong>
                  <p>{{ currentExecutionResult?.affectedRows ?? sqlExecutionResult.affectedRows ?? 0 }}</p>
                </div>
              </div>

              <el-tabs v-if="executionResults.length > 1" v-model="activeExecutionTab" class="execution-tabs">
                <el-tab-pane
                  v-for="resultItem in executionResults"
                  :key="resultItem.statementIndex"
                  :label="t('web.dataDevelopment.resultTab', { index: resultItem.statementIndex })"
                  :name="String(resultItem.statementIndex)"
                />
              </el-tabs>

              <div class="soft-panel execution-message">
                <strong>{{ currentExecutionResult?.message || executionResult.message || t("web.dataDevelopment.summary") }}</strong>
                <p>{{ executionSummaryText }}</p>
              </div>

              <el-table v-if="currentExecutionResult?.columns?.length" :data="currentExecutionResult.rows" border size="small" max-height="320">
                <el-table-column
                  v-for="column in currentExecutionResult.columns"
                  :key="column"
                  :prop="column"
                  :label="column"
                  min-width="160"
                  show-overflow-tooltip
                />
              </el-table>

              <template v-else>
                <el-form-item :label="t('web.dataDevelopment.executionLogs')" class="execution-output-item">
                  <el-input :model-value="executionResult.logs || ''" type="textarea" :rows="8" readonly />
                </el-form-item>
                <el-form-item :label="t('web.dataDevelopment.executionResultJson')" class="execution-output-item">
                  <el-input :model-value="prettyJson(executionResult.resultJson)" type="textarea" :rows="10" readonly />
                </el-form-item>
              </template>
            </template>
            <div v-else class="soft-panel">{{ t("web.dataDevelopment.resultsEmpty") }}</div>
          </SectionCard>
        </template>

        <div v-else class="soft-panel empty-editor">
          <strong>{{ selectedDirectory?.name || t("web.dataDevelopment.rootDirectory") }}</strong>
          <p>{{ selectedDirectory?.description || t("web.dataDevelopment.emptyEditor") }}</p>
        </div>
      </SectionCard>
    </div>

    <el-dialog v-model="directoryDialogVisible" :title="t('web.dataDevelopment.directoryDialogTitle')" width="520px">
      <div class="studio-form-grid">
        <el-form-item :label="t('web.dataDevelopment.directoryName')">
          <el-input v-model="directoryForm.name" :placeholder="t('web.dataDevelopment.directoryNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('web.dataDevelopment.directoryPermission')">
          <el-input v-model="directoryForm.permissionCode" :placeholder="t('web.dataDevelopment.directoryPermissionPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('web.dataDevelopment.currentDirectory')" class="span-2">
          <el-select v-model="directoryForm.parentId" clearable :placeholder="t('web.dataDevelopment.rootDirectory')">
            <el-option :label="t('web.dataDevelopment.rootDirectory')" value="" />
            <el-option v-for="directory in directories" :key="directory.id" :label="directory.name" :value="String(directory.id)" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('web.dataDevelopment.directoryDescription')" class="span-2">
          <el-input v-model="directoryForm.description" type="textarea" :rows="4" />
        </el-form-item>
      </div>
      <template #footer>
        <el-button @click="directoryDialogVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveDirectory">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="moveDialogVisible" :title="t('web.dataDevelopment.moveToDirectory')" width="460px">
      <el-form-item :label="t('web.dataDevelopment.moveToDirectory')">
        <el-select v-model="moveTargetDirectoryId" clearable :placeholder="t('web.dataDevelopment.moveToDirectoryPlaceholder')">
          <el-option :label="t('web.dataDevelopment.rootDirectory')" value="" />
          <el-option
            v-for="directory in moveDirectoryOptions"
            :key="directory.id"
            :label="directory.name"
            :value="String(directory.id)"
          />
        </el-select>
      </el-form-item>
      <template #footer>
        <el-button @click="moveDialogVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="moveSelectedNode">{{ t("common.confirm") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  DataDevelopmentDirectory,
  DataDevelopmentDirectorySaveRequest,
  DataScriptExecutionResult,
  DataDevelopmentScript,
  DataModelDefinition,
  SqlStatementExecutionResult,
  DataDevelopmentTreeNode,
  DataSourceDefinition,
  EntityId,
  SqlExecutionResult,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { formatScriptType, formatStatusLabel, isSharedFromAnotherProject, prettyJson, resolveProjectName, sameEntityId } from "@/utils/studio";
import ScriptEditorPanel from "../components/data-development/ScriptEditorPanel.vue";
import type { SqlEditorHintSource } from "../components/data-development/editorTypes";
import { resolveScriptEditorEntry } from "../components/data-development/scriptEditorRegistry";

const { t } = useI18n();
const authStore = useAuthStore();

const treeData = ref<DataDevelopmentTreeNode[]>([]);
const directories = ref<DataDevelopmentDirectory[]>([]);
const sqlDatasources = ref<DataSourceDefinition[]>([]);
const selectedTreeNode = ref<DataDevelopmentTreeNode | null>(null);
const selectedDirectory = ref<DataDevelopmentDirectory | null>(null);
const executionResult = ref<DataScriptExecutionResult | null>(null);
const activeExecutionTab = ref("1");
const scriptEditorVisible = ref(false);
const directoryDialogVisible = ref(false);
const moveDialogVisible = ref(false);
const moveTargetDirectoryId = ref<string>("");
const sqlHintCache = ref<Record<string, SqlEditorHintSource>>({});
const scriptExecutionArgumentsText = ref("{\n  \n}");
const refreshToken = ref(0);

const directoryForm = reactive<DataDevelopmentDirectorySaveRequest>({
  id: undefined,
  parentId: undefined,
  name: "",
  permissionCode: "",
  description: "",
});

const scriptForm = reactive<DataDevelopmentScript>({
  id: undefined,
  directoryId: undefined,
  projectId: undefined,
  fileName: "",
  scriptType: "SQL",
  datasourceId: "",
  description: "",
  content: "",
});

const currentDirectoryLabel = computed(() => {
  if (!scriptForm.directoryId) {
    return t("web.dataDevelopment.rootDirectory");
  }
  const directory = directories.value.find((item) => String(item.id) === String(scriptForm.directoryId));
  return directory?.name || t("web.dataDevelopment.rootDirectory");
});
const currentProjectLabel = computed(() => authStore.currentProjectName || t("common.none"));
const hasCurrentProject = computed(() => Boolean(authStore.currentProjectId));
const currentScriptRegistryEntry = computed(() => resolveScriptEditorEntry(scriptForm.scriptType));
const isCurrentScriptShared = computed(() => Boolean(
  scriptForm.id && isSharedFromAnotherProject(authStore.currentProjectId, scriptForm.projectId),
));
const isJavaScriptType = computed(() => scriptForm.scriptType === "JAVA");
const isPythonScriptType = computed(() => scriptForm.scriptType === "PYTHON");
const isStructuredScriptType = computed(() => isJavaScriptType.value || isPythonScriptType.value);
const supportsExecutionArguments = computed(() => isStructuredScriptType.value);
const scriptTypeOptions = computed(() => [
  { value: "SQL", label: t("web.dataDevelopment.scriptTypeSql"), disabled: false },
  { value: "JAVA", label: t("web.dataDevelopment.scriptTypeJava"), disabled: false },
  { value: "PYTHON", label: t("web.dataDevelopment.scriptTypePython"), disabled: false },
]);
const canExecuteCurrentScript = computed(() =>
  hasCurrentProject.value && scriptEditorVisible.value && currentScriptRegistryEntry.value.supportsExecution);
const canSaveCurrentScript = computed(() =>
  hasCurrentProject.value && scriptEditorVisible.value && currentScriptRegistryEntry.value.supportsSave && !isCurrentScriptShared.value);
const executeButtonLabel = computed(() => isStructuredScriptType.value
  ? t("web.dataDevelopment.executeScript")
  : t("web.dataDevelopment.executeSql"));
const scriptTemplateTitle = computed(() => isPythonScriptType.value
  ? t("web.dataDevelopment.pythonTemplateTitle")
  : t("web.dataDevelopment.javaTemplateTitle"));
const scriptTemplateDescription = computed(() => isPythonScriptType.value
  ? t("web.dataDevelopment.pythonTemplateDescription")
  : t("web.dataDevelopment.javaTemplateDescription"));
const currentSqlHints = computed<SqlEditorHintSource | undefined>(() => {
  if (!scriptForm.datasourceId) {
    return undefined;
  }
  return sqlHintCache.value[String(scriptForm.datasourceId)];
});
const sqlExecutionResult = computed<SqlExecutionResult | null>(() => executionResult.value?.sqlResult ?? null);
const executionResults = computed<SqlStatementExecutionResult[]>(() => {
  return sqlExecutionResult.value?.results?.length
    ? sqlExecutionResult.value.results
    : sqlExecutionResult.value
      ? [{
          statementIndex: 1,
          query: sqlExecutionResult.value.query,
          affectedRows: sqlExecutionResult.value.affectedRows,
          executionMs: sqlExecutionResult.value.executionMs,
          message: sqlExecutionResult.value.message,
          columns: sqlExecutionResult.value.columns ?? [],
          rows: sqlExecutionResult.value.rows ?? [],
          summary: sqlExecutionResult.value.summary ?? {},
        }]
      : [];
});
const currentExecutionResult = computed<SqlStatementExecutionResult | null>(() => {
  if (!executionResults.value.length) {
    return null;
  }
  return executionResults.value.find((item) => String(item.statementIndex) === activeExecutionTab.value) ?? executionResults.value[0];
});
const executionSummaryText = computed(() => prettyJson(
  currentExecutionResult.value?.summary
  ?? sqlExecutionResult.value?.summary
  ?? executionResult.value?.resultJson
  ?? {},
));
const moveDirectoryOptions = computed(() => {
  if (!selectedTreeNode.value) {
    return directories.value;
  }
  if (selectedTreeNode.value.nodeType === "DIRECTORY") {
    return directories.value.filter((item) => String(item.id) !== String(selectedTreeNode.value?.directoryId));
  }
  return directories.value;
});
const isSelectedNodeShared = computed(() => {
  if (!selectedTreeNode.value || selectedTreeNode.value.nodeType !== "SCRIPT") {
    return false;
  }
  const ownerProjectId = selectedTreeNode.value.projectId
    ?? (scriptForm.id != null && sameEntityId(scriptForm.id, selectedTreeNode.value.scriptId) ? scriptForm.projectId : undefined);
  return isSharedFromAnotherProject(authStore.currentProjectId, ownerProjectId);
});
const canMoveSelectedNode = computed(() =>
  Boolean(hasCurrentProject.value && selectedTreeNode.value && !isSelectedNodeShared.value));
const canDeleteSelectedNode = computed(() =>
  Boolean(hasCurrentProject.value && selectedTreeNode.value && !isSelectedNodeShared.value));

async function refreshAll() {
  const currentRefreshToken = ++refreshToken.value;
  if (!hasCurrentProject.value) {
    resetProjectScopedState();
    return;
  }
  try {
    const [tree, directoryList, datasourceList] = await Promise.all([
      studioApi.dataDevelopment.tree(),
      studioApi.dataDevelopment.listDirectories(),
      studioApi.dataDevelopment.listSqlDatasources(),
    ]);
    if (currentRefreshToken !== refreshToken.value) {
      return;
    }
    treeData.value = tree;
    directories.value = directoryList;
    sqlDatasources.value = datasourceList;
    if (selectedTreeNode.value) {
      synchronizeSelection();
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.dataDevelopment.loadFailed"));
  }
}

function resetProjectScopedState() {
  treeData.value = [];
  directories.value = [];
  sqlDatasources.value = [];
  sqlHintCache.value = {};
  selectedTreeNode.value = null;
  selectedDirectory.value = null;
  executionResult.value = null;
  scriptEditorVisible.value = false;
  directoryDialogVisible.value = false;
  moveDialogVisible.value = false;
  activeExecutionTab.value = "1";
  scriptForm.id = undefined;
  scriptForm.directoryId = undefined;
  scriptForm.projectId = undefined;
  scriptForm.fileName = "";
  scriptForm.scriptType = "SQL";
  scriptForm.datasourceId = "";
  scriptForm.datasourceName = undefined;
  scriptForm.datasourceTypeCode = undefined;
  scriptForm.description = "";
  scriptForm.content = "";
  scriptExecutionArgumentsText.value = "{\n  \n}";
  moveTargetDirectoryId.value = "";
}

function synchronizeSelection() {
  if (!selectedTreeNode.value) {
    return;
  }
  const matched = findTreeNode(selectedTreeNode.value.nodeKey, treeData.value);
  selectedTreeNode.value = matched;
  if (!matched) {
    selectedDirectory.value = null;
    return;
  }
  if (matched.nodeType === "DIRECTORY") {
    selectedDirectory.value = directories.value.find((item) => String(item.id) === String(matched.directoryId)) ?? null;
  }
}

function findTreeNode(nodeKey: string, nodes: DataDevelopmentTreeNode[]): DataDevelopmentTreeNode | null {
  for (const node of nodes) {
    if (node.nodeKey === nodeKey) {
      return node;
    }
    const child = findTreeNode(nodeKey, node.children ?? []);
    if (child) {
      return child;
    }
  }
  return null;
}

async function handleTreeClick(node: DataDevelopmentTreeNode) {
  selectedTreeNode.value = node;
  executionResult.value = null;
  if (node.nodeType === "DIRECTORY") {
    selectedDirectory.value = directories.value.find((item) => String(item.id) === String(node.directoryId)) ?? null;
    createNewScript();
    return;
  }
  selectedDirectory.value = node.directoryId
    ? directories.value.find((item) => String(item.id) === String(node.directoryId)) ?? null
    : null;
  await loadScript(node.scriptId);
}

async function loadScript(scriptId: string | number | undefined) {
  if (!scriptId) {
    return;
  }
  const script = await studioApi.dataDevelopment.getScript(scriptId);
  scriptEditorVisible.value = true;
  activeExecutionTab.value = "1";
  scriptForm.id = script.id;
  scriptForm.directoryId = script.directoryId;
  scriptForm.projectId = script.projectId;
  scriptForm.fileName = script.fileName;
  scriptForm.scriptType = script.scriptType;
  scriptForm.datasourceId = script.datasourceId;
  scriptForm.datasourceName = script.datasourceName;
  scriptForm.datasourceTypeCode = script.datasourceTypeCode;
  scriptForm.description = script.description;
  scriptForm.content = script.content;
  scriptExecutionArgumentsText.value = "{\n  \n}";
  await ensureSqlHintsLoaded(script.datasourceId);
}

function createNewScript() {
  const directoryId = resolveSelectedWritableDirectoryId();
  scriptEditorVisible.value = true;
  scriptForm.id = undefined;
  scriptForm.directoryId = directoryId;
  scriptForm.projectId = authStore.currentProjectId ?? undefined;
  scriptForm.fileName = "";
  scriptForm.scriptType = "SQL";
  scriptForm.datasourceId = "";
  scriptForm.datasourceName = undefined;
  scriptForm.datasourceTypeCode = undefined;
  scriptForm.description = "";
  scriptForm.content = "";
  scriptExecutionArgumentsText.value = "{\n  \n}";
  executionResult.value = null;
  activeExecutionTab.value = "1";
}

function openDirectoryDialog() {
  directoryForm.id = undefined;
  directoryForm.parentId = resolveSelectedWritableDirectoryId();
  directoryForm.name = "";
  directoryForm.permissionCode = "";
  directoryForm.description = "";
  directoryDialogVisible.value = true;
}

async function saveDirectory() {
  try {
    await studioApi.dataDevelopment.saveDirectory({
      ...directoryForm,
      parentId: normalizeEntityId(directoryForm.parentId),
    });
    directoryDialogVisible.value = false;
    ElMessage.success(t("web.dataDevelopment.saveDirectorySuccess"));
    await refreshAll();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.dataDevelopment.saveDirectoryFailed"));
  }
}

async function saveScript() {
  if (!currentScriptRegistryEntry.value.supportsSave) {
    ElMessage.warning(t("web.dataDevelopment.unsupportedScriptType"));
    return;
  }
  try {
    const saved = await studioApi.dataDevelopment.saveScript({
      id: scriptForm.id,
      directoryId: normalizeEntityId(scriptForm.directoryId),
      fileName: scriptForm.fileName,
      scriptType: scriptForm.scriptType,
      datasourceId: currentScriptRegistryEntry.value.requiresDatasource
        ? requireEntityId(scriptForm.datasourceId, t("web.dataDevelopment.datasource"))
        : undefined,
      description: scriptForm.description,
      content: scriptForm.content,
    });
    scriptForm.id = saved.id;
    scriptForm.projectId = saved.projectId;
    selectedTreeNode.value = saved.id
      ? {
          nodeKey: `script-${saved.id}`,
          nodeType: "SCRIPT",
          scriptId: saved.id,
          directoryId: saved.directoryId,
          projectId: saved.projectId,
          name: saved.fileName,
          children: [],
        }
      : selectedTreeNode.value;
    ElMessage.success(t("web.dataDevelopment.saveScriptSuccess"));
    await refreshAll();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.dataDevelopment.saveScriptFailed"));
  }
}

async function executeCurrentScript() {
  if (!currentScriptRegistryEntry.value.supportsExecution) {
    ElMessage.warning(t("web.dataDevelopment.unsupportedScriptType"));
    return;
  }
  try {
    executionResult.value = await studioApi.dataDevelopment.executeScript({
      scriptType: scriptForm.scriptType,
      datasourceId: currentScriptRegistryEntry.value.requiresDatasource
        ? requireEntityId(scriptForm.datasourceId, t("web.dataDevelopment.datasource"))
        : undefined,
      content: scriptForm.content,
      arguments: supportsExecutionArguments.value ? parseScriptExecutionArguments() : undefined,
      maxRows: 100,
    });
    activeExecutionTab.value = String(executionResult.value.sqlResult?.results?.[0]?.statementIndex ?? 1);
    if (executionResult.value.success === false) {
      ElMessage.error(executionResult.value.message || t("web.dataDevelopment.executeFailed"));
    } else {
      ElMessage.success(t("web.dataDevelopment.executeSuccess"));
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.dataDevelopment.executeFailed"));
  }
}

function parseScriptExecutionArguments() {
  if (!scriptExecutionArgumentsText.value.trim()) {
    return {};
  }
  const parsed = JSON.parse(scriptExecutionArgumentsText.value);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(t("web.dataDevelopment.scriptArgumentsInvalid"));
  }
  return parsed as Record<string, unknown>;
}

async function ensureSqlHintsLoaded(datasourceId: EntityId | undefined) {
  if (!datasourceId) {
    return;
  }
  const cacheKey = String(datasourceId);
  if (sqlHintCache.value[cacheKey]) {
    return;
  }
  try {
    const models = await studioApi.models.listByDatasource(datasourceId);
    const datasource = sqlDatasources.value.find((item) => String(item.id) === cacheKey);
    sqlHintCache.value = {
      ...sqlHintCache.value,
      [cacheKey]: buildSqlHintSource(models, datasource),
    };
  } catch (error) {
    sqlHintCache.value = {
      ...sqlHintCache.value,
      [cacheKey]: buildSqlHintSource([], sqlDatasources.value.find((item) => String(item.id) === cacheKey)),
    };
  }
}

function buildSqlHintSource(models: DataModelDefinition[], datasource?: DataSourceDefinition): SqlEditorHintSource {
  const tableMap = new Map<string, { name: string; modelName?: string; columns: Set<string> }>();
  for (const model of models) {
    const tableName = String(model.physicalLocator || model.name || "").trim();
    if (!tableName) {
      continue;
    }
    const current = tableMap.get(tableName) ?? {
      name: tableName,
      modelName: model.name,
      columns: new Set<string>(),
    };
    for (const column of extractModelColumns(model)) {
      current.columns.add(column);
    }
    tableMap.set(tableName, current);
  }
  return {
    datasourceName: datasource?.name,
    datasourceTypeCode: datasource?.typeCode,
    tables: Array.from(tableMap.values()).map((item) => ({
      name: item.name,
      modelName: item.modelName,
      columns: Array.from(item.columns.values()).sort((left, right) => left.localeCompare(right)),
    })),
  };
}

function extractModelColumns(model: DataModelDefinition): string[] {
  const columns = model.technicalMetadata?.columns;
  if (!Array.isArray(columns)) {
    return [];
  }
  const result = new Set<string>();
  for (const column of columns) {
    if (!column || typeof column !== "object") {
      continue;
    }
    const fieldName = typeof (column as Record<string, unknown>).name === "string"
      ? String((column as Record<string, unknown>).name).trim()
      : "";
    if (fieldName) {
      result.add(fieldName);
    }
  }
  return Array.from(result.values());
}

function openMoveDialog() {
  if (!canMoveSelectedNode.value) {
    return;
  }
  moveTargetDirectoryId.value = "";
  moveDialogVisible.value = true;
}

async function moveSelectedNode() {
  if (!selectedTreeNode.value) {
    return;
  }
  try {
    const payload = { targetDirectoryId: normalizeEntityId(moveTargetDirectoryId.value) };
    if (selectedTreeNode.value.nodeType === "DIRECTORY" && selectedTreeNode.value.directoryId) {
      await studioApi.dataDevelopment.moveDirectory(selectedTreeNode.value.directoryId, payload);
      ElMessage.success(t("web.dataDevelopment.moveDirectorySuccess"));
    } else if (selectedTreeNode.value.scriptId) {
      await studioApi.dataDevelopment.moveScript(selectedTreeNode.value.scriptId, payload);
      scriptForm.directoryId = normalizeEntityId(moveTargetDirectoryId.value);
      ElMessage.success(t("web.dataDevelopment.moveScriptSuccess"));
    }
    moveDialogVisible.value = false;
    await refreshAll();
  } catch (error) {
    const messageKey = selectedTreeNode.value?.nodeType === "DIRECTORY"
      ? "web.dataDevelopment.moveDirectoryFailed"
      : "web.dataDevelopment.moveScriptFailed";
    ElMessage.error(error instanceof Error ? error.message : t(messageKey));
  }
}

async function deleteSelectedNode() {
  if (!selectedTreeNode.value) {
    return;
  }
  try {
    if (selectedTreeNode.value.nodeType === "DIRECTORY" && selectedTreeNode.value.directoryId) {
      await ElMessageBox.confirm(
        t("web.dataDevelopment.deleteDirectoryConfirm", { name: selectedTreeNode.value.name }),
        t("common.confirm"),
        { type: "warning" },
      );
      await studioApi.dataDevelopment.deleteDirectory(selectedTreeNode.value.directoryId);
      ElMessage.success(t("web.dataDevelopment.deleteDirectorySuccess"));
    } else if (selectedTreeNode.value.scriptId) {
      await ElMessageBox.confirm(
        t("web.dataDevelopment.deleteScriptConfirm", { name: selectedTreeNode.value.name }),
        t("common.confirm"),
        { type: "warning" },
      );
      await studioApi.dataDevelopment.deleteScript(selectedTreeNode.value.scriptId);
      ElMessage.success(t("web.dataDevelopment.deleteScriptSuccess"));
      createNewScript();
    }
    selectedTreeNode.value = null;
    selectedDirectory.value = null;
    executionResult.value = null;
    if (!scriptForm.id && !scriptForm.fileName && !scriptForm.content && !scriptForm.datasourceId) {
      scriptEditorVisible.value = false;
    }
    await refreshAll();
  } catch (error) {
    if (error !== "cancel") {
      const messageKey = selectedTreeNode.value?.nodeType === "DIRECTORY"
        ? "web.dataDevelopment.deleteDirectoryFailed"
        : "web.dataDevelopment.deleteScriptFailed";
      ElMessage.error(error instanceof Error ? error.message : t(messageKey));
    }
  }
}

function normalizeEntityId(value: unknown): EntityId | undefined {
  if (value == null || value === "") {
    return undefined;
  }
  return value as EntityId;
}

function requireEntityId(value: unknown, fieldName: string): EntityId {
  const normalized = normalizeEntityId(value);
  if (normalized == null) {
    throw new Error(`${fieldName} is required`);
  }
  return normalized;
}

function resolveProjectLabel(projectId?: EntityId | null) {
  return resolveProjectName(authStore.projects, projectId);
}

function isSharedScriptNode(node?: DataDevelopmentTreeNode | null) {
  if (!node || node.nodeType !== "SCRIPT") {
    return false;
  }
  return isSharedFromAnotherProject(authStore.currentProjectId, node.projectId);
}

function resolveSelectedWritableDirectoryId() {
  const directoryId = selectedTreeNode.value?.nodeType === "DIRECTORY"
    ? selectedTreeNode.value.directoryId
    : selectedTreeNode.value?.directoryId;
  if (!directoryId) {
    return undefined;
  }
  return directories.value.some((item) => sameEntityId(item.id, directoryId)) ? directoryId : undefined;
}

onMounted(async () => {
  scriptForm.scriptType = "SQL";
  await refreshAll();
});

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], () => {
  if (!authStore.isAuthenticated) {
    return;
  }
  resetProjectScopedState();
  refreshAll();
});

watch(
  () => scriptForm.scriptType,
  (scriptType, previousType) => {
    executionResult.value = null;
    activeExecutionTab.value = "1";
    if (resolveScriptEditorEntry(scriptType).requiresDatasource === false) {
      scriptForm.datasourceId = undefined;
      scriptForm.datasourceName = undefined;
      scriptForm.datasourceTypeCode = undefined;
    }
    if (scriptType === "JAVA" && (!scriptForm.content || scriptForm.content.trim().length === 0 || previousType === "SQL")) {
      scriptForm.content = defaultJavaTemplate();
    }
    if (scriptType === "PYTHON" && (!scriptForm.content || scriptForm.content.trim().length === 0 || previousType === "SQL")) {
      scriptForm.content = defaultPythonTemplate();
    }
  },
);

watch(
  () => [scriptForm.datasourceId, scriptForm.scriptType] as const,
  async ([datasourceId, scriptType]) => {
    if (scriptType === "SQL" && datasourceId) {
      await ensureSqlHintsLoaded(datasourceId);
    }
  },
  { immediate: true },
);

function defaultJavaTemplate() {
  return [
    "import com.jdragon.studio.infra.script.java.JavaDataScript;",
    "import com.jdragon.studio.infra.script.java.JavaDataScriptContext;",
    "import com.jdragon.studio.infra.script.java.JavaDataScriptResult;",
    "",
    "public class DemoJavaDataScript implements JavaDataScript {",
    "    @Override",
    "    public JavaDataScriptResult execute(JavaDataScriptContext context) throws Exception {",
    "        context.getLogger().info(\"Java script started by \" + context.getUsername());",
    "        JavaDataScriptResult result = new JavaDataScriptResult();",
    "        result.setMessage(\"Java script executed successfully\");",
    "        result.getResultJson().put(\"tenantId\", context.getTenantId());",
    "        result.getResultJson().put(\"arguments\", context.getArguments());",
    "        return result;",
    "    }",
    "}",
  ].join("\n");
}

function defaultPythonTemplate() {
  return [
    "def execute(context):",
    "    context.logger.info(\"Python script started by %s\" % context.username)",
    "    datasources = context.services.list_datasources()",
    "    return {",
    "        \"tenantId\": context.tenant_id,",
    "        \"arguments\": context.arguments,",
    "        \"datasourceCount\": len(datasources),",
    "    }",
  ].join("\n");
}
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.data-development-layout {
  display: grid;
  grid-template-columns: 290px minmax(0, 1fr);
  gap: 14px;
}

.tree-toolbar {
  display: grid;
  gap: 10px;
  margin-bottom: 10px;
}

.tree-context p {
  margin-top: 4px;
}

.tree-node {
  width: 100%;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.tree-node__content {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  padding: 2px 0;
  overflow: hidden;
}

.tree-node__content strong {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-node__content span {
  font-size: 12px;
  color: var(--studio-text-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.java-script-hint {
  margin-bottom: 12px;
}

.java-script-hint p {
  margin-top: 6px;
}

.shared-script-note {
  margin-bottom: 12px;
  border: 1px solid rgba(198, 107, 0, 0.24);
  background: rgba(255, 248, 233, 0.9);
  color: #8a5200;
}

.execution-output-item :deep(.el-textarea__inner) {
  font-family: "Cascadia Code", "Consolas", monospace;
}

.span-2 {
  grid-column: span 2;
}

.editor-form-item {
  display: block;
}

:deep(.editor-form-item.el-form-item) {
  display: block;
  width: 100%;
}

:deep(.editor-form-item .el-form-item__label) {
  display: flex;
  justify-content: flex-start;
  width: 100%;
  margin-bottom: 8px;
  padding: 0;
  line-height: 1.4;
}

:deep(.editor-form-item .el-form-item__content) {
  display: block;
  width: 100%;
  line-height: normal;
}

.execution-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 10px;
  margin-bottom: 10px;
}

.execution-tabs {
  margin-bottom: 10px;
}

.execution-message {
  margin-bottom: 10px;
}

.execution-message p {
  margin-top: 8px;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: "Cascadia Code", "Consolas", monospace;
}

.empty-editor {
  min-height: 160px;
  display: grid;
  align-content: center;
  gap: 8px;
}

:deep(.el-tree) {
  background: transparent;
}

:deep(.el-tree-node__content) {
  border-radius: 10px;
  padding-right: 6px;
}

@media (max-width: 1180px) {
  .data-development-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .execution-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .span-2 {
    grid-column: span 1;
  }
}
</style>
