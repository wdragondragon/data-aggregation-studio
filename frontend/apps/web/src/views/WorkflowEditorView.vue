<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ workflowId ? t("web.workflows.editorTitle") : t("web.workflows.createTitle") }}</h3>
        <p>{{ t("web.workflows.editorDescription") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/workflows')">{{ t("common.backToList") }}</el-button>
        <el-button plain @click="refreshWorkflowEditor">{{ t("common.refresh") }}</el-button>
        <el-button v-if="!(detailLoadError && workflowId)" type="primary" :loading="saving" @click="saveWorkflow">{{ t("common.saveDraft") }}</el-button>
      </div>
    </div>

    <SectionCard v-if="detailLoadError && workflowId" title="工作流不可用" description="该工作流可能已被删除、取消共享，或当前项目无权访问。">
      <el-result
        icon="warning"
        title="工作流不可用"
        :sub-title="detailLoadError"
      />
    </SectionCard>

    <template v-else>
    <WorkflowBasicsSection :form="form" />

    <WorkflowCanvasSection
      :nodes="form.nodes"
      :edges="form.edges"
      @update:nodes="form.nodes = $event"
      @update:edges="form.edges = $event"
      @select-node="selectedNodeCode = $event"
    />

    <div class="studio-grid columns-2">
      <WorkflowSelectedNodePanel
        v-model:data-script-arguments-text="dataScriptArgumentsText"
        v-model:http-body-text="httpBodyText"
        :selected-node="selectedNode"
        :selected-bound-task="selectedBoundTask"
        :selected-bound-quality-task="selectedBoundQualityTask"
        :selected-bound-script="selectedBoundScript"
        :collection-tasks-loading="collectionTasksLoading"
        :quality-tasks-loading="qualityTasksLoading"
        :scripts-loading="scriptsLoading"
        :script-tree-loading="scriptTreeLoading"
        :selected-script-max-rows="selectedScriptMaxRows"
        :selected-http-url="selectedHttpUrl"
        :selected-http-method="selectedHttpMethod"
        :selected-http-query-params="selectedHttpQueryParams"
        :selected-http-headers="selectedHttpHeaders"
        :selected-node-fields="selectedNodeFields"
        :selected-node-config="selectedNodeConfig"
        :node-actions="selectedNodePanelActions"
      />

      <SectionCard :title="t('web.workflows.edgeTitle')" :description="t('web.workflows.edgeDescription')">
        <el-table :data="form.edges" border>
          <el-table-column prop="fromNodeCode" :label="t('web.workflows.from')" min-width="120" />
          <el-table-column prop="toNodeCode" :label="t('web.workflows.to')" min-width="120" />
          <el-table-column :label="t('web.workflows.condition')" min-width="160">
            <template #default="{ row, $index }">
              <el-select :model-value="row.condition" @update:model-value="updateEdgeCondition($index, $event)">
                <el-option label="ON_SUCCESS" value="ON_SUCCESS" />
                <el-option label="ON_FAILURE" value="ON_FAILURE" />
                <el-option label="ALWAYS" value="ALWAYS" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>
    </div>

    <WorkflowResourceDialogs
      v-model:collection-task-dialog-visible="collectionTaskDialogVisible"
      v-model:quality-task-dialog-visible="qualityTaskDialogVisible"
      v-model:script-dialog-visible="scriptDialogVisible"
      v-model:collection-task-keyword="collectionTaskKeyword"
      v-model:quality-task-keyword="qualityTaskKeyword"
      v-model:script-keyword="scriptKeyword"
      v-model:pending-collection-task-id="pendingCollectionTaskId"
      v-model:pending-quality-task-id="pendingQualityTaskId"
      v-model:pending-script-id="pendingScriptId"
      v-model:preview-script-content="previewScriptContent"
      :collection-tasks-loading="collectionTasksLoading"
      :quality-tasks-loading="qualityTasksLoading"
      :script-tree-loading="scriptTreeLoading"
      :script-preview-loading="scriptPreviewLoading"
      :online-collection-tasks="onlineCollectionTasks"
      :collection-task-pagination="collectionTaskPagination"
      :collection-task-total="collectionTaskTotal"
      :online-quality-tasks="onlineQualityTasks"
      :quality-task-pagination="qualityTaskPagination"
      :quality-task-total="qualityTaskTotal"
      :filtered-script-tree-data="filteredScriptTreeData"
      :preview-script="previewScript"
      :actions="resourceDialogActions"
    />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  CollectionTaskWorkflowOptionView,
  DataDevelopmentScript,
  DataDevelopmentScriptListView,
  DataDevelopmentTreeNode,
  DataSourceOptionView,
  MetadataFieldDefinition,
  QualityTaskWorkflowOptionView,
  WorkflowDefinitionView,
  WorkflowNodeDefinition,
  WorkflowSaveRequest,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import WorkflowBasicsSection from "@/components/workflow/WorkflowBasicsSection.vue";
import WorkflowCanvasSection from "@/components/workflow/WorkflowCanvasSection.vue";
import WorkflowResourceDialogs from "@/components/workflow/WorkflowResourceDialogs.vue";
import WorkflowSelectedNodePanel from "@/components/workflow/WorkflowSelectedNodePanel.vue";
import {
  filterScriptTree,
  formatNullableText,
  formatQualityDimension,
  formatQualityGranularity,
  getDialogRowIndex,
  normalizeHttpParamRows,
  normalizeMaxRows,
  parseDataScriptArguments,
  parseHttpBody,
  type DialogPagination,
  type HttpParamField,
  type HttpParamRow,
  type HttpParamSection,
} from "@/components/workflow/workflowEditorSupport";
import {
  cloneDeep,
  prettyJson,
} from "@/utils/studio";
import { resolveErrorMessage } from "@/composables/useAsyncAction";

interface WorkflowEditor extends WorkflowSaveRequest {
  definitionId?: string | number;
  schedule: {
    cronExpression?: string;
    enabled?: boolean;
    timezone?: string;
  };
}

const { t } = useI18n();
const route = useRoute();
const router = useRouter();

const workflowId = computed(() => route.params.workflowId as string | undefined);
const datasources = ref<DataSourceOptionView[]>([]);
const onlineCollectionTasks = ref<CollectionTaskWorkflowOptionView[]>([]);
const onlineQualityTasks = ref<QualityTaskWorkflowOptionView[]>([]);
const scripts = ref<DataDevelopmentScriptListView[]>([]);
const selectedNodeCode = ref<string | null>(null);
const saving = ref(false);
const detailLoadError = ref("");
const collectionTasksLoading = ref(false);
const collectionTaskReloadPending = ref(false);
const qualityTasksLoading = ref(false);
const qualityTaskReloadPending = ref(false);
const scriptsLoading = ref(false);
const collectionTasksLoaded = ref(false);
const qualityTasksLoaded = ref(false);
const collectionTaskDialogVisible = ref(false);
const qualityTaskDialogVisible = ref(false);
const scriptDialogVisible = ref(false);
const collectionTaskKeyword = ref("");
const qualityTaskKeyword = ref("");
const scriptKeyword = ref("");
const pendingCollectionTaskId = ref<string>();
const pendingQualityTaskId = ref<string>();
const pendingScriptId = ref<string>();
const scriptTreeData = ref<DataDevelopmentTreeNode[]>([]);
const scriptTreeLoading = ref(false);
const scriptTreeLoaded = ref(false);
const scriptPreviewLoading = ref(false);
const previewScript = ref<DataDevelopmentScript | null>(null);
const collectionTaskPagination = reactive<DialogPagination>({ page: 1, pageSize: 8 });
const collectionTaskTotal = ref(0);
const qualityTaskPagination = reactive<DialogPagination>({ page: 1, pageSize: 8 });
const qualityTaskTotal = ref(0);
const dataScriptArgumentsText = ref("{}");
const httpBodyText = ref("");
const form = reactive<WorkflowEditor>({
  code: "",
  name: "",
  schedule: {
    cronExpression: "0 */30 * * * ?",
    enabled: false,
    timezone: "Asia/Shanghai",
  },
  nodes: [],
  edges: [],
});

const datasourceOptions = computed(() => datasources.value.map((item) => item.name));
const selectedNode = computed(() => form.nodes.find((node) => node.nodeCode === selectedNodeCode.value));
const filteredScriptTreeData = computed(() => filterScriptTree(scriptTreeData.value, scriptKeyword.value));
const previewScriptContent = computed({
  get: () => previewScript.value?.content ?? "",
  set: (value: string) => {
    if (previewScript.value) {
      previewScript.value = {
        ...previewScript.value,
        content: value,
      };
    }
  },
});
const selectedCollectionTaskId = computed(() => {
  if (!selectedNode.value?.config?.collectionTaskId) {
    return undefined;
  }
  return String(selectedNode.value.config.collectionTaskId);
});
const selectedBoundTask = computed(() =>
  onlineCollectionTasks.value.find((item) => String(item.id) === selectedCollectionTaskId.value) ?? buildSelectedCollectionTaskFallback(),
);
const selectedQualityTaskId = computed(() => {
  if (!selectedNode.value?.config?.qualityTaskId) {
    return undefined;
  }
  return String(selectedNode.value.config.qualityTaskId);
});
const selectedBoundQualityTask = computed(() =>
  onlineQualityTasks.value.find((item) => String(item.id) === selectedQualityTaskId.value) ?? buildSelectedQualityTaskFallback(),
);
const selectedScriptId = computed(() => {
  if (!selectedNode.value?.config?.scriptId) {
    return undefined;
  }
  return String(selectedNode.value.config.scriptId);
});
const selectedBoundScript = computed(() =>
  scripts.value.find((item) => String(item.id) === selectedScriptId.value) ?? buildSelectedScriptFallback(),
);

const selectedNodeConfig = computed<Record<string, unknown>>({
  get() {
    return selectedNode.value?.config ?? {};
  },
  set(value) {
    updateSelectedNode("config", value);
  },
});

const selectedHttpUrl = computed(() => String(selectedNode.value?.config?.url ?? ""));
const selectedHttpMethod = computed(() => {
  const method = String(selectedNode.value?.config?.method ?? "").trim();
  return method ? method.toUpperCase() : "GET";
});
const selectedHttpQueryParams = computed(() => normalizeHttpParamRows(selectedNode.value?.config?.queryParams));
const selectedHttpHeaders = computed(() => normalizeHttpParamRows(selectedNode.value?.config?.headers));
const selectedScriptMaxRows = computed(() => {
  const rawValue = selectedNode.value?.config?.maxRows;
  if (typeof rawValue === "number") {
    return rawValue;
  }
  if (typeof rawValue === "string" && rawValue.trim()) {
    return Number(rawValue);
  }
  return 100;
});

const selectedNodeFields = computed<MetadataFieldDefinition[]>(() => {
  if (!selectedNode.value?.nodeType) {
    return [];
  }
  if (selectedNode.value.nodeType === "HTTP") {
    return [
      { fieldKey: "url", fieldName: "URL", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "method", fieldName: "Method", scope: "TECHNICAL", componentType: "SELECT", valueType: "STRING", options: ["GET", "POST", "PUT", "DELETE"] },
      { fieldKey: "payload", fieldName: "Payload", scope: "TECHNICAL", componentType: "JSON_EDITOR", valueType: "JSON" },
    ];
  }
  if (selectedNode.value.nodeType === "SHELL") {
    return [
      { fieldKey: "command", fieldName: "Command", scope: "TECHNICAL", componentType: "CODE_EDITOR", valueType: "STRING", required: true },
      { fieldKey: "workingDirectory", fieldName: "Working Directory", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
      { fieldKey: "timeoutSeconds", fieldName: "Timeout Seconds", scope: "TECHNICAL", componentType: "NUMBER", valueType: "INTEGER" },
    ];
  }
  return [
    { fieldKey: "sourceDatasource", fieldName: "Source Datasource", scope: "TECHNICAL", componentType: "SELECT", valueType: "STRING", options: datasourceOptions.value },
    { fieldKey: "sourceModel", fieldName: "Source Model", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
    { fieldKey: "targetDatasource", fieldName: "Target Datasource", scope: "TECHNICAL", componentType: "SELECT", valueType: "STRING", options: datasourceOptions.value },
    { fieldKey: "targetModel", fieldName: "Target Model", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
    { fieldKey: "sourceFields", fieldName: "Source Fields", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING" },
    { fieldKey: "targetFields", fieldName: "Target Fields", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING" },
    { fieldKey: "ruleId", fieldName: "Rule ID", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
    { fieldKey: "compareFields", fieldName: "Compare Fields", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING" },
  ];
});

function buildSelectedCollectionTaskFallback(): CollectionTaskWorkflowOptionView | undefined {
  const config = selectedNode.value?.config;
  if (!config?.collectionTaskId) {
    return undefined;
  }
  const taskName = readSelectedNodeConfigText("collectionTaskName") ?? String(config.collectionTaskId);
  const sourceCountText = readSelectedNodeConfigText("collectionTaskSourceCount");
  const sourceCount = sourceCountText == null ? undefined : Number(sourceCountText);
  return {
    id: config.collectionTaskId as CollectionTaskWorkflowOptionView["id"],
    name: taskName,
    taskType: readSelectedNodeConfigText("collectionTaskType") as CollectionTaskWorkflowOptionView["taskType"],
    sourceCount: Number.isFinite(sourceCount) ? sourceCount : undefined,
  };
}

function buildSelectedQualityTaskFallback(): QualityTaskWorkflowOptionView | undefined {
  const config = selectedNode.value?.config;
  if (!config?.qualityTaskId) {
    return undefined;
  }
  const taskName = readSelectedNodeConfigText("qualityTaskName")
    ?? readSelectedNodeConfigText("qualityTaskCode")
    ?? String(config.qualityTaskId);
  return {
    id: config.qualityTaskId as QualityTaskWorkflowOptionView["id"],
    taskName,
    taskCode: readSelectedNodeConfigText("qualityTaskCode") ?? taskName,
    ruleId: config.ruleId as QualityTaskWorkflowOptionView["ruleId"],
    ruleName: readSelectedNodeConfigText("ruleName"),
    ruleDimension: config.ruleDimension as QualityTaskWorkflowOptionView["ruleDimension"],
    granularity: config.granularity as QualityTaskWorkflowOptionView["granularity"],
    datasourceId: config.datasourceId as QualityTaskWorkflowOptionView["datasourceId"],
    datasourceName: readSelectedNodeConfigText("datasourceName"),
    modelId: config.modelId as QualityTaskWorkflowOptionView["modelId"],
    modelName: readSelectedNodeConfigText("modelName"),
    columnName: readSelectedNodeConfigText("columnName"),
  };
}

function buildSelectedScriptFallback(scriptId = selectedScriptId.value): DataDevelopmentScriptListView | undefined {
  const config = selectedNode.value?.config;
  if (!scriptId || !config?.scriptId || String(config.scriptId) !== String(scriptId)) {
    return undefined;
  }
  const scriptType = readSelectedNodeConfigText("scriptType");
  if (!scriptType) {
    return undefined;
  }
  const fileName = readSelectedNodeConfigText("scriptName") ?? String(config.scriptId);
  return {
    id: config.scriptId as DataDevelopmentScriptListView["id"],
    fileName,
    scriptType: scriptType as DataDevelopmentScriptListView["scriptType"],
    datasourceId: config.datasourceId as DataDevelopmentScriptListView["datasourceId"],
    datasourceName: readSelectedNodeConfigText("datasourceName"),
  };
}

function readSelectedNodeConfigText(key: string) {
  const value = selectedNode.value?.config?.[key];
  return value == null || value === "" ? undefined : String(value);
}

function resetWorkflow() {
  form.definitionId = undefined;
  form.code = "";
  form.name = "";
  form.schedule = {
    cronExpression: "0 */30 * * * ?",
    enabled: false,
    timezone: "Asia/Shanghai",
  };
  form.nodes = [];
  form.edges = [];
  selectedNodeCode.value = null;
}

async function loadReferenceData() {
  try {
    const [datasourceData] = await Promise.all([
      studioApi.datasources.options(),
    ]);
    datasources.value = datasourceData;
    await refreshSelectedNodeCandidates();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  }
}

async function refreshSelectedNodeCandidates() {
  if (selectedNode.value?.nodeType === "COLLECTION_TASK") {
    await ensureCollectionTasksLoaded(true);
    return;
  }
  if (selectedNode.value?.nodeType === "QUALITY_TASK") {
    await ensureQualityTasksLoaded(true);
  }
}

async function ensureCollectionTasksLoaded(force = false) {
  if ((collectionTasksLoaded.value && !force) || collectionTasksLoading.value) {
    return;
  }
  await loadCollectionTasks();
}

async function loadCollectionTasks() {
  if (collectionTasksLoading.value) {
    collectionTaskReloadPending.value = true;
    return;
  }
  collectionTasksLoading.value = true;
  try {
    let page = await studioApi.collectionTasks.workflowOptions({
      pageNo: collectionTaskPagination.page,
      pageSize: collectionTaskPagination.pageSize,
      keyword: collectionTaskKeyword.value.trim() || undefined,
    });
    const maxPage = Math.max(1, Math.ceil(page.total / collectionTaskPagination.pageSize));
    if (collectionTaskPagination.page > maxPage) {
      collectionTaskPagination.page = maxPage;
      page = await studioApi.collectionTasks.workflowOptions({
        pageNo: collectionTaskPagination.page,
        pageSize: collectionTaskPagination.pageSize,
        keyword: collectionTaskKeyword.value.trim() || undefined,
      });
    }
    onlineCollectionTasks.value = page.items;
    collectionTaskTotal.value = page.total;
    collectionTasksLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  } finally {
    collectionTasksLoading.value = false;
    if (collectionTaskReloadPending.value) {
      collectionTaskReloadPending.value = false;
      await loadCollectionTasks();
    }
  }
}

async function ensureQualityTasksLoaded(force = false) {
  if ((qualityTasksLoaded.value && !force) || qualityTasksLoading.value) {
    return;
  }
  await loadQualityTasks();
}

async function loadQualityTasks() {
  if (qualityTasksLoading.value) {
    qualityTaskReloadPending.value = true;
    return;
  }
  qualityTasksLoading.value = true;
  try {
    let page = await studioApi.qualityTasks.workflowOptions({
      pageNo: qualityTaskPagination.page,
      pageSize: qualityTaskPagination.pageSize,
      keyword: qualityTaskKeyword.value.trim() || undefined,
    });
    const maxPage = Math.max(1, Math.ceil(page.total / qualityTaskPagination.pageSize));
    if (qualityTaskPagination.page > maxPage) {
      qualityTaskPagination.page = maxPage;
      page = await studioApi.qualityTasks.workflowOptions({
        pageNo: qualityTaskPagination.page,
        pageSize: qualityTaskPagination.pageSize,
        keyword: qualityTaskKeyword.value.trim() || undefined,
      });
    }
    onlineQualityTasks.value = page.items;
    qualityTaskTotal.value = page.total;
    qualityTasksLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  } finally {
    qualityTasksLoading.value = false;
    if (qualityTaskReloadPending.value) {
      qualityTaskReloadPending.value = false;
      await loadQualityTasks();
    }
  }
}

async function ensureScriptTreeLoaded(force = false) {
  if ((scriptTreeLoaded.value && !force) || scriptTreeLoading.value) {
    return;
  }
  scriptTreeLoading.value = true;
  try {
    scriptTreeData.value = await studioApi.dataDevelopment.tree();
    scriptTreeLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  } finally {
    scriptTreeLoading.value = false;
  }
}

async function openCollectionTaskDialog() {
  collectionTaskDialogVisible.value = true;
  pendingCollectionTaskId.value = selectedCollectionTaskId.value;
  collectionTaskPagination.page = 1;
  await loadCollectionTasks();
}

function confirmCollectionTaskSelection() {
  bindCollectionTask(pendingCollectionTaskId.value);
  collectionTaskDialogVisible.value = false;
}

function handleCollectionTaskKeywordInput() {
  collectionTaskPagination.page = 1;
  void loadCollectionTasks();
}

function handleCollectionTaskPageChange() {
  void loadCollectionTasks();
}

function handleCollectionTaskPageSizeChange() {
  collectionTaskPagination.page = 1;
  void loadCollectionTasks();
}

async function openQualityTaskDialog() {
  qualityTaskDialogVisible.value = true;
  pendingQualityTaskId.value = selectedQualityTaskId.value;
  qualityTaskPagination.page = 1;
  await loadQualityTasks();
}

function confirmQualityTaskSelection() {
  bindQualityTask(pendingQualityTaskId.value);
  qualityTaskDialogVisible.value = false;
}

function handleQualityTaskKeywordInput() {
  qualityTaskPagination.page = 1;
  void loadQualityTasks();
}

function handleQualityTaskPageChange() {
  void loadQualityTasks();
}

function handleQualityTaskPageSizeChange() {
  qualityTaskPagination.page = 1;
  void loadQualityTasks();
}

async function openScriptDialog() {
  scriptDialogVisible.value = true;
  pendingScriptId.value = selectedScriptId.value;
  previewScript.value = null;
  await ensureScriptTreeLoaded();
  if (pendingScriptId.value) {
    await loadScriptPreview(pendingScriptId.value);
  }
}

async function handleScriptTreeClick(node: DataDevelopmentTreeNode) {
  if (node.nodeType !== "SCRIPT" || !node.scriptId) {
    return;
  }
  pendingScriptId.value = String(node.scriptId);
  await loadScriptPreview(node.scriptId);
}

async function loadScriptPreview(scriptId: string | number) {
  scriptPreviewLoading.value = true;
  previewScript.value = null;
  try {
    const script = await studioApi.dataDevelopment.getScript(scriptId);
    previewScript.value = script;
    upsertScript(script);
    return true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
    return false;
  } finally {
    scriptPreviewLoading.value = false;
  }
}

async function confirmScriptSelection() {
  if (!pendingScriptId.value) {
    return;
  }
  if (!scripts.value.some((item) => String(item.id) === String(pendingScriptId.value))) {
    const loaded = await loadScriptPreview(pendingScriptId.value);
    if (!loaded) {
      return;
    }
  }
  bindScript(pendingScriptId.value);
  scriptDialogVisible.value = false;
}

function upsertScript(script: DataDevelopmentScript) {
  const index = scripts.value.findIndex((item) => String(item.id) === String(script.id));
  if (index >= 0) {
    scripts.value = scripts.value.map((item, itemIndex) => itemIndex === index ? script : item);
    return;
  }
  scripts.value = [script, ...scripts.value];
}

function findScriptTreeNode(scriptId: string | number, nodes: DataDevelopmentTreeNode[]): DataDevelopmentTreeNode | undefined {
  for (const node of nodes) {
    if (node.nodeType === "SCRIPT" && node.scriptId != null && String(node.scriptId) === String(scriptId)) {
      return node;
    }
    const matched = findScriptTreeNode(scriptId, node.children ?? []);
    if (matched) {
      return matched;
    }
  }
  return undefined;
}

function buildScriptListViewFromTreeNode(node?: DataDevelopmentTreeNode): DataDevelopmentScriptListView | undefined {
  if (!node?.scriptId || !node.scriptType) {
    return undefined;
  }
  return {
    id: node.scriptId,
    fileName: node.name,
    scriptType: node.scriptType,
    datasourceName: node.datasourceName,
    environmentId: node.environmentId,
    environmentName: node.environmentName,
  };
}

function resolveScriptForBinding(scriptId: string | number): DataDevelopmentScriptListView | undefined {
  return scripts.value.find((item) => String(item.id) === String(scriptId))
    ?? buildScriptListViewFromTreeNode(findScriptTreeNode(scriptId, scriptTreeData.value))
    ?? buildSelectedScriptFallback(String(scriptId));
}

async function loadWorkflow() {
  if (!workflowId.value) {
    detailLoadError.value = "";
    resetWorkflow();
    return;
  }
  try {
    detailLoadError.value = "";
    const workflow = await studioApi.workflows.get(workflowId.value);
    if (!workflow?.id) {
      throw new Error(`Workflow not found: ${workflowId.value}`);
    }
    applyWorkflow(workflow);
  } catch (error) {
    const message = resolveErrorMessage(error, t("web.workflows.loadFailed"));
    detailLoadError.value = message;
    ElMessage.error(message);
  }
}

async function refreshWorkflowEditor() {
  if (detailLoadError.value && workflowId.value) {
    await loadWorkflow();
    return;
  }
  await loadReferenceData();
}

function applyWorkflow(workflow: WorkflowDefinitionView) {
  const copied = cloneDeep(workflow);
  form.definitionId = copied.id;
  form.code = copied.code;
  form.name = copied.name;
  form.schedule = copied.schedule ?? {
    cronExpression: "0 */30 * * * ?",
    enabled: false,
    timezone: "Asia/Shanghai",
  };
  form.nodes = copied.nodes ?? [];
  form.edges = copied.edges ?? [];
  selectedNodeCode.value = form.nodes[0]?.nodeCode ?? null;
}

function updateSelectedNode(key: keyof WorkflowNodeDefinition, value: unknown) {
  form.nodes = form.nodes.map((node) =>
    node.nodeCode === selectedNodeCode.value
      ? {
          ...node,
          [key]: value,
        }
      : node,
  );
}

function updateSelectedNodeConfig(value: Record<string, unknown>) {
  selectedNodeConfig.value = value;
}

function bindCollectionTask(value?: string) {
  if (!value) {
    return;
  }
  const task = onlineCollectionTasks.value.find((item) => String(item.id) === String(value));
  if (!selectedNode.value || !task) {
    return;
  }
  const taskName = task.name ?? String(task.id ?? value);
  form.nodes = form.nodes.map((node) =>
    node.nodeCode === selectedNodeCode.value
      ? {
          ...node,
          nodeName: taskName,
          config: {
            ...(node.config ?? {}),
            collectionTaskId: task.id,
            collectionTaskName: taskName,
            collectionTaskType: task.taskType,
            collectionTaskSourceCount: task.sourceCount,
          },
        }
      : node,
  );
}

function bindQualityTask(value?: string) {
  if (!value) {
    return;
  }
  const task = onlineQualityTasks.value.find((item) => String(item.id) === String(value));
  if (!selectedNode.value || !task) {
    return;
  }
  form.nodes = form.nodes.map((node) =>
    node.nodeCode === selectedNodeCode.value
      ? {
          ...node,
          nodeName: task.taskName,
          config: {
            ...(node.config ?? {}),
            qualityTaskId: task.id,
            qualityTaskName: task.taskName,
            qualityTaskCode: task.taskCode,
            ruleId: task.ruleId,
            ruleName: task.ruleName,
            ruleDimension: task.ruleDimension,
            granularity: task.granularity,
            datasourceId: task.datasourceId,
            datasourceName: task.datasourceName,
            modelId: task.modelId,
            modelName: task.modelName,
            columnName: task.columnName,
          },
        }
      : node,
  );
}

function bindScript(value?: string) {
  if (!value) {
    return;
  }
  const script = resolveScriptForBinding(value);
  if (!selectedNode.value || !script) {
    return;
  }
  const nextConfig: Record<string, unknown> = {
    ...(selectedNode.value.config ?? {}),
    scriptId: script.id,
    scriptName: script.fileName,
    scriptType: script.scriptType,
  };
  if (script.datasourceId != null) {
    nextConfig.datasourceId = script.datasourceId;
  } else {
    delete nextConfig.datasourceId;
  }
  if (script.datasourceName != null) {
    nextConfig.datasourceName = script.datasourceName;
  } else {
    delete nextConfig.datasourceName;
  }
  if (dataScriptUsesArguments(script.scriptType)) {
    delete nextConfig.maxRows;
    if (nextConfig.arguments == null) {
      nextConfig.arguments = {};
    }
  } else {
    delete nextConfig.arguments;
    if (nextConfig.maxRows == null) {
      nextConfig.maxRows = 100;
    }
  }
  form.nodes = form.nodes.map((node) =>
    node.nodeCode === selectedNodeCode.value
      ? {
          ...node,
          nodeName: script.fileName,
          config: nextConfig,
        }
      : node,
  );
}

function updateSelectedDataScriptMaxRows(value: number | undefined) {
  updateSelectedNode("config", {
    ...(selectedNode.value?.config ?? {}),
    maxRows: value == null ? 100 : value,
  });
}

function applySelectedDataScriptArguments() {
  if (!selectedBoundScript.value || !dataScriptUsesArguments(selectedBoundScript.value.scriptType)) {
    return;
  }
  try {
    updateSelectedNode("config", {
      ...(selectedNode.value?.config ?? {}),
      arguments: parseDataScriptArguments(dataScriptArgumentsText.value, t("web.workflows.dataScriptArgumentsInvalid")),
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.dataScriptArgumentsInvalid"));
  }
}

function updateSelectedHttpConfig(patch: Record<string, unknown>) {
  if (!selectedNode.value || selectedNode.value.nodeType !== "HTTP") {
    return;
  }
  updateSelectedNode("config", {
    ...(selectedNode.value.config ?? {}),
    ...patch,
  });
}

function appendHttpParam(section: HttpParamSection) {
  const rows = normalizeHttpParamRows(selectedNode.value?.config?.[section]);
  rows.push({
    enabled: true,
    name: "",
    value: "",
  });
  updateSelectedHttpConfig({ [section]: rows });
}

function updateHttpParam(section: HttpParamSection, index: number, field: HttpParamField, value: unknown) {
  const rows = normalizeHttpParamRows(selectedNode.value?.config?.[section]);
  rows[index] = {
    ...(rows[index] ?? { enabled: true, name: "", value: "" }),
    [field]: field === "enabled" ? Boolean(value) : String(value ?? ""),
  };
  updateSelectedHttpConfig({ [section]: rows });
}

function removeHttpParam(section: HttpParamSection, index: number) {
  const rows = normalizeHttpParamRows(selectedNode.value?.config?.[section]);
  rows.splice(index, 1);
  updateSelectedHttpConfig({ [section]: rows });
}

function applySelectedHttpBody() {
  if (!selectedNode.value || selectedNode.value.nodeType !== "HTTP") {
    return;
  }
  try {
    updateSelectedHttpConfig({ body: parseHttpBody(httpBodyText.value, t("web.workflows.httpBodyInvalid")) });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.httpBodyInvalid"));
    throw error;
  }
}

function removeSelectedNode() {
  if (!selectedNodeCode.value) {
    return;
  }
  const removingCode = selectedNodeCode.value;
  form.nodes = form.nodes.filter((node) => node.nodeCode !== removingCode);
  form.edges = form.edges.filter((edge) => edge.fromNodeCode !== removingCode && edge.toNodeCode !== removingCode);
  selectedNodeCode.value = null;
}

function updateEdgeCondition(index: number, condition: string) {
  form.edges = form.edges.map((edge, edgeIndex) =>
    edgeIndex === index
      ? {
          ...edge,
          condition: condition as WorkflowEditor["edges"][number]["condition"],
        }
      : edge,
  );
}

async function saveWorkflow() {
  if (detailLoadError.value && workflowId.value) {
    ElMessage.error(detailLoadError.value);
    return;
  }
  saving.value = true;
  try {
    if (selectedNode.value?.nodeType === "HTTP") {
      applySelectedHttpBody();
    }
    const saved = await studioApi.workflows.save(buildWorkflowPayload());
    ElMessage.success(t("web.workflows.saveSuccess"));
    applyWorkflow(saved);
    await router.push("/workflows");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.saveFailed"));
  } finally {
    saving.value = false;
  }
}

watch(workflowId, async () => {
  await loadWorkflow();
}, { immediate: true });

watch(
  () => selectedNode.value?.nodeType,
  (nodeType) => {
    if (nodeType === "COLLECTION_TASK") {
      void ensureCollectionTasksLoaded();
      return;
    }
    if (nodeType === "QUALITY_TASK") {
      void ensureQualityTasksLoaded();
      return;
    }
  },
  { immediate: true },
);

watch(
  () => [selectedNodeCode.value, selectedBoundScript.value?.id, selectedNode.value?.config?.arguments] as const,
  () => {
    if (selectedBoundScript.value && dataScriptUsesArguments(selectedBoundScript.value.scriptType)) {
      dataScriptArgumentsText.value = prettyJson(selectedNode.value?.config?.arguments ?? {});
      return;
    }
    dataScriptArgumentsText.value = "{}";
  },
  { immediate: true },
);

watch(
  () => [selectedNodeCode.value, selectedNode.value?.nodeType, selectedNode.value?.config?.body, selectedNode.value?.config?.payload] as const,
  () => {
    if (selectedNode.value?.nodeType !== "HTTP") {
      httpBodyText.value = "";
      return;
    }
    const body = selectedNode.value.config?.body ?? selectedNode.value.config?.payload;
    httpBodyText.value = body == null || body === "" ? "" : prettyJson(body);
  },
  { immediate: true },
);

onMounted(loadReferenceData);

function buildWorkflowPayload() {
  const payload = cloneDeep(form);
  payload.nodes = payload.nodes.map((node) => {
    if (node.nodeType === "HTTP") {
      const config = { ...(node.config ?? {}) };
      const legacyBody = config.payload;
      if (config.body == null && legacyBody != null) {
        config.body = legacyBody;
      }
      delete config.payload;
      return {
        ...node,
        config: {
          ...config,
          method: String(config.method ?? "").trim() ? String(config.method).toUpperCase() : "GET",
          queryParams: normalizeHttpParamRows(config.queryParams).filter((item) => item.name.trim()),
          headers: normalizeHttpParamRows(config.headers).filter((item) => item.name.trim()),
        },
      };
    }
    if (node.nodeType !== "DATA_SCRIPT") {
      return node;
    }
    const scriptType = String(node.config?.scriptType ?? "").toUpperCase();
    if (dataScriptUsesArguments(scriptType)) {
      return {
        ...node,
        config: {
          ...(node.config ?? {}),
          arguments: parseDataScriptArguments(node.config?.arguments, t("web.workflows.dataScriptArgumentsInvalid")),
        },
      };
    }
    return {
      ...node,
      config: {
        ...(node.config ?? {}),
        arguments: undefined,
        maxRows: normalizeMaxRows(node.config?.maxRows),
      },
    };
  });
  return payload;
}

function dataScriptUsesArguments(scriptType?: string | null) {
  const normalized = String(scriptType ?? "").toUpperCase();
  return normalized === "JAVA" || normalized === "PYTHON";
}

const selectedNodePanelActions = {
  removeSelectedNode,
  updateSelectedNode,
  updateSelectedNodeConfig,
  openCollectionTaskDialog,
  openQualityTaskDialog,
  openScriptDialog,
  updateSelectedDataScriptMaxRows,
  applySelectedDataScriptArguments,
  updateSelectedHttpConfig,
  appendHttpParam,
  updateHttpParam,
  removeHttpParam,
  applySelectedHttpBody,
  formatQualityDimension: (value?: string | null) => formatQualityDimension(t, value),
  formatQualityGranularity: (value?: string | null) => formatQualityGranularity(t, value),
};

const resourceDialogActions = {
  ensureCollectionTasksLoaded,
  loadCollectionTasks,
  handleCollectionTaskKeywordInput,
  handleCollectionTaskPageChange,
  handleCollectionTaskPageSizeChange,
  getDialogRowIndex,
  confirmCollectionTaskSelection,
  handleQualityTaskKeywordInput,
  loadQualityTasks,
  handleQualityTaskPageChange,
  handleQualityTaskPageSizeChange,
  confirmQualityTaskSelection,
  ensureScriptTreeLoaded,
  handleScriptTreeClick,
  confirmScriptSelection,
  formatNullableText,
  formatQualityDimension: (value?: string | null) => formatQualityDimension(t, value),
  formatQualityGranularity: (value?: string | null) => formatQualityGranularity(t, value),
};
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

</style>
