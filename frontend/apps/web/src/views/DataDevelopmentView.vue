<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.dataDevelopment.heading") }}</h3>
        <p>{{ t("web.dataDevelopment.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="refreshAll">{{ t("common.refresh") }}</el-button>
        <el-button plain @click="openDirectoryDialog()">{{ t("web.dataDevelopment.newDirectory") }}</el-button>
        <el-button type="primary" @click="createNewScript">{{ t("web.dataDevelopment.newScript") }}</el-button>
        <el-button type="success" :disabled="!scriptEditorVisible" @click="executeSql">{{ t("web.dataDevelopment.executeSql") }}</el-button>
        <el-button type="primary" :disabled="!scriptEditorVisible" @click="saveScript">{{ t("web.dataDevelopment.saveScript") }}</el-button>
        <el-button plain :disabled="!selectedTreeNode" @click="openMoveDialog">{{ t("web.dataDevelopment.moveNode") }}</el-button>
        <el-button type="danger" plain :disabled="!selectedTreeNode" @click="deleteSelectedNode">{{ t("common.delete") }}</el-button>
      </div>
    </div>

    <div class="data-development-layout">
      <SectionCard :title="t('web.dataDevelopment.treeTitle')" :description="t('web.dataDevelopment.treeDescription')">
        <div class="tree-toolbar">
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
                  {{ slotProps?.data?.scriptType }} · {{ slotProps?.data?.datasourceName || t("common.none") }}
                </span>
              </div>
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
                <el-option label="SQL" value="SQL" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('web.dataDevelopment.datasource')">
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

          <el-form-item :label="t('web.dataDevelopment.content')">
            <el-input
              v-model="scriptForm.content"
              type="textarea"
              :rows="18"
              :placeholder="t('web.dataDevelopment.contentPlaceholder')"
            />
          </el-form-item>

          <SectionCard :title="t('web.dataDevelopment.executionTitle')" :description="t('web.dataDevelopment.executionDescription')">
            <template v-if="executionResult">
              <div class="execution-summary">
                <div class="soft-panel">
                  <strong>{{ t("web.dataDevelopment.statementCount") }}</strong>
                  <p>{{ executionResult.statementCount ?? executionResults.length }}</p>
                </div>
                <div class="soft-panel">
                  <strong>{{ t("web.dataDevelopment.resultRows") }}</strong>
                  <p>{{ currentExecutionResult?.rows?.length ?? 0 }}</p>
                </div>
                <div class="soft-panel">
                  <strong>{{ t("web.dataDevelopment.resultColumns") }}</strong>
                  <p>{{ currentExecutionResult?.columns?.length ?? 0 }}</p>
                </div>
                <div class="soft-panel">
                  <strong>{{ t("web.dataDevelopment.affectedRows") }}</strong>
                  <p>{{ currentExecutionResult?.affectedRows ?? executionResult.affectedRows ?? 0 }}</p>
                </div>
                <div class="soft-panel">
                  <strong>{{ t("web.dataDevelopment.executionMs") }}</strong>
                  <p>{{ currentExecutionResult?.executionMs ?? executionResult.executionMs ?? 0 }} ms</p>
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
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  DataDevelopmentDirectory,
  DataDevelopmentDirectorySaveRequest,
  DataDevelopmentScript,
  SqlStatementExecutionResult,
  DataDevelopmentTreeNode,
  DataSourceDefinition,
  EntityId,
  SqlExecutionResult,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";

const { t } = useI18n();

const treeData = ref<DataDevelopmentTreeNode[]>([]);
const directories = ref<DataDevelopmentDirectory[]>([]);
const sqlDatasources = ref<DataSourceDefinition[]>([]);
const selectedTreeNode = ref<DataDevelopmentTreeNode | null>(null);
const selectedDirectory = ref<DataDevelopmentDirectory | null>(null);
const executionResult = ref<SqlExecutionResult | null>(null);
const activeExecutionTab = ref("1");
const scriptEditorVisible = ref(false);
const directoryDialogVisible = ref(false);
const moveDialogVisible = ref(false);
const moveTargetDirectoryId = ref<string>("");

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
const executionResults = computed<SqlStatementExecutionResult[]>(() => {
  return executionResult.value?.results?.length
    ? executionResult.value.results
    : executionResult.value
      ? [{
          statementIndex: 1,
          query: executionResult.value.query,
          affectedRows: executionResult.value.affectedRows,
          executionMs: executionResult.value.executionMs,
          message: executionResult.value.message,
          columns: executionResult.value.columns ?? [],
          rows: executionResult.value.rows ?? [],
          summary: executionResult.value.summary ?? {},
        }]
      : [];
});
const currentExecutionResult = computed<SqlStatementExecutionResult | null>(() => {
  if (!executionResults.value.length) {
    return null;
  }
  return executionResults.value.find((item) => String(item.statementIndex) === activeExecutionTab.value) ?? executionResults.value[0];
});
const executionSummaryText = computed(() => JSON.stringify(currentExecutionResult.value?.summary ?? executionResult.value?.summary ?? {}, null, 2));
const moveDirectoryOptions = computed(() => {
  if (!selectedTreeNode.value) {
    return directories.value;
  }
  if (selectedTreeNode.value.nodeType === "DIRECTORY") {
    return directories.value.filter((item) => String(item.id) !== String(selectedTreeNode.value?.directoryId));
  }
  return directories.value;
});

async function refreshAll() {
  try {
    const [tree, directoryList, datasourceList] = await Promise.all([
      studioApi.dataDevelopment.tree(),
      studioApi.dataDevelopment.listDirectories(),
      studioApi.dataDevelopment.listSqlDatasources(),
    ]);
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
  scriptForm.fileName = script.fileName;
  scriptForm.scriptType = script.scriptType;
  scriptForm.datasourceId = script.datasourceId;
  scriptForm.datasourceName = script.datasourceName;
  scriptForm.datasourceTypeCode = script.datasourceTypeCode;
  scriptForm.description = script.description;
  scriptForm.content = script.content;
}

function createNewScript() {
  const directoryId = selectedTreeNode.value?.nodeType === "DIRECTORY"
    ? selectedTreeNode.value.directoryId
    : selectedTreeNode.value?.directoryId;
  scriptEditorVisible.value = true;
  scriptForm.id = undefined;
  scriptForm.directoryId = directoryId;
  scriptForm.fileName = "";
  scriptForm.scriptType = "SQL";
  scriptForm.datasourceId = "";
  scriptForm.datasourceName = undefined;
  scriptForm.datasourceTypeCode = undefined;
  scriptForm.description = "";
  scriptForm.content = "";
  executionResult.value = null;
  activeExecutionTab.value = "1";
}

function openDirectoryDialog() {
  directoryForm.id = undefined;
  directoryForm.parentId = selectedTreeNode.value?.nodeType === "DIRECTORY"
    ? selectedTreeNode.value.directoryId
    : selectedTreeNode.value?.directoryId;
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
  try {
    const saved = await studioApi.dataDevelopment.saveScript({
      id: scriptForm.id,
      directoryId: normalizeEntityId(scriptForm.directoryId),
      fileName: scriptForm.fileName,
      scriptType: scriptForm.scriptType,
      datasourceId: requireEntityId(scriptForm.datasourceId, t("web.dataDevelopment.datasource")),
      description: scriptForm.description,
      content: scriptForm.content,
    });
    scriptForm.id = saved.id;
    selectedTreeNode.value = saved.id
      ? { nodeKey: `script-${saved.id}`, nodeType: "SCRIPT", scriptId: saved.id, directoryId: saved.directoryId, name: saved.fileName, children: [] }
      : selectedTreeNode.value;
    ElMessage.success(t("web.dataDevelopment.saveScriptSuccess"));
    await refreshAll();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.dataDevelopment.saveScriptFailed"));
  }
}

async function executeSql() {
  try {
    executionResult.value = await studioApi.dataDevelopment.executeSql({
      datasourceId: requireEntityId(scriptForm.datasourceId, t("web.dataDevelopment.datasource")),
      scriptType: scriptForm.scriptType,
      content: scriptForm.content,
      maxRows: 100,
    });
    activeExecutionTab.value = String(executionResult.value.results?.[0]?.statementIndex ?? 1);
    ElMessage.success(t("web.dataDevelopment.executeSuccess"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.dataDevelopment.executeFailed"));
  }
}

function openMoveDialog() {
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

onMounted(async () => {
  scriptForm.scriptType = "SQL";
  await refreshAll();
});
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
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 18px;
}

.tree-toolbar {
  margin-bottom: 16px;
}

.tree-context p {
  margin-top: 4px;
}

.tree-node {
  width: 100%;
}

.tree-node__content {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 0;
}

.tree-node__content strong {
  font-size: 13px;
}

.tree-node__content span {
  font-size: 12px;
  color: var(--studio-text-soft);
}

.span-2 {
  grid-column: span 2;
}

.execution-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.execution-tabs {
  margin-bottom: 12px;
}

.execution-message {
  margin-bottom: 12px;
}

.execution-message p {
  margin-top: 8px;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: "Cascadia Code", "Consolas", monospace;
}

.empty-editor {
  min-height: 220px;
  display: grid;
  align-content: center;
  gap: 8px;
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
