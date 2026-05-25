<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ workflowId ? t("web.workflows.editorTitle") : t("web.workflows.createTitle") }}</h3>
        <p>{{ t("web.workflows.editorDescription") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/workflows')">{{ t("common.backToList") }}</el-button>
        <el-button plain @click="loadReferenceData">{{ t("common.refresh") }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveWorkflow">{{ t("common.saveDraft") }}</el-button>
      </div>
    </div>

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
      :paged-collection-tasks="pagedCollectionTasks"
      :filtered-collection-tasks="filteredCollectionTasks"
      :collection-task-pagination="collectionTaskPagination"
      :online-quality-tasks="onlineQualityTasks"
      :quality-task-pagination="qualityTaskPagination"
      :quality-task-total="qualityTaskTotal"
      :filtered-script-tree-data="filteredScriptTreeData"
      :preview-script="previewScript"
      :actions="resourceDialogActions"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  CollectionTaskDefinitionView,
  DataDevelopmentScript,
  DataDevelopmentTreeNode,
  DataSourceDefinition,
  MetadataFieldDefinition,
  QualityTaskDefinitionView,
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
  cloneDeep,
  prettyJson,
} from "@/utils/studio";

interface WorkflowEditor extends WorkflowSaveRequest {
  definitionId?: string | number;
  schedule: {
    cronExpression?: string;
    enabled?: boolean;
    timezone?: string;
  };
}

interface DialogPagination {
  page: number;
  pageSize: number;
}

type HttpParamSection = "queryParams" | "headers";
type HttpParamField = "enabled" | "name" | "value";

interface HttpParamRow {
  enabled?: boolean;
  name: string;
  value: string;
}

const { t } = useI18n();
const route = useRoute();
const router = useRouter();

const workflowId = computed(() => route.params.workflowId as string | undefined);
const datasources = ref<DataSourceDefinition[]>([]);
const onlineCollectionTasks = ref<CollectionTaskDefinitionView[]>([]);
const onlineQualityTasks = ref<QualityTaskDefinitionView[]>([]);
const scripts = ref<DataDevelopmentScript[]>([]);
const selectedNodeCode = ref<string | null>(null);
const saving = ref(false);
const collectionTasksLoading = ref(false);
const qualityTasksLoading = ref(false);
const qualityTaskReloadPending = ref(false);
const scriptsLoading = ref(false);
const collectionTasksLoaded = ref(false);
const qualityTasksLoaded = ref(false);
const scriptsLoaded = ref(false);
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
const filteredCollectionTasks = computed(() =>
  onlineCollectionTasks.value.filter((task) =>
    matchesKeyword(collectionTaskKeyword.value, [task.name, task.taskType, task.status, task.id]),
  ),
);
const pagedCollectionTasks = computed(() => paginateItems(filteredCollectionTasks.value, collectionTaskPagination));
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
  onlineCollectionTasks.value.find((item) => String(item.id) === selectedCollectionTaskId.value),
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
  scripts.value.find((item) => String(item.id) === selectedScriptId.value),
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

function buildSelectedQualityTaskFallback(): QualityTaskDefinitionView | undefined {
  const config = selectedNode.value?.config;
  if (!config?.qualityTaskId) {
    return undefined;
  }
  const taskName = readSelectedNodeConfigText("qualityTaskName")
    ?? readSelectedNodeConfigText("qualityTaskCode")
    ?? String(config.qualityTaskId);
  return {
    id: config.qualityTaskId as QualityTaskDefinitionView["id"],
    taskName,
    taskCode: readSelectedNodeConfigText("qualityTaskCode") ?? taskName,
    status: "ONLINE",
    ruleId: config.ruleId as QualityTaskDefinitionView["ruleId"],
    ruleName: readSelectedNodeConfigText("ruleName"),
    ruleDimension: config.ruleDimension as QualityTaskDefinitionView["ruleDimension"],
    granularity: config.granularity as QualityTaskDefinitionView["granularity"],
    datasourceId: config.datasourceId as QualityTaskDefinitionView["datasourceId"],
    datasourceName: readSelectedNodeConfigText("datasourceName"),
    modelId: config.modelId as QualityTaskDefinitionView["modelId"],
    modelName: readSelectedNodeConfigText("modelName"),
    columnName: readSelectedNodeConfigText("columnName"),
    parameterBindings: [],
    outputParams: [],
    alertConfigs: [],
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
      studioApi.datasources.list(),
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
    return;
  }
  if (selectedNode.value?.nodeType === "DATA_SCRIPT") {
    await ensureScriptsLoaded(true);
  }
}

async function ensureCollectionTasksLoaded(force = false) {
  if ((collectionTasksLoaded.value && !force) || collectionTasksLoading.value) {
    return;
  }
  collectionTasksLoading.value = true;
  try {
    onlineCollectionTasks.value = await studioApi.collectionTasks.listOnline();
    collectionTasksLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  } finally {
    collectionTasksLoading.value = false;
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
    let page = await studioApi.qualityTasks.listPage({
      pageNo: qualityTaskPagination.page,
      pageSize: qualityTaskPagination.pageSize,
      keyword: qualityTaskKeyword.value.trim() || undefined,
      status: "ONLINE",
    });
    const maxPage = Math.max(1, Math.ceil(page.total / qualityTaskPagination.pageSize));
    if (qualityTaskPagination.page > maxPage) {
      qualityTaskPagination.page = maxPage;
      page = await studioApi.qualityTasks.listPage({
        pageNo: qualityTaskPagination.page,
        pageSize: qualityTaskPagination.pageSize,
        keyword: qualityTaskKeyword.value.trim() || undefined,
        status: "ONLINE",
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

async function ensureScriptsLoaded(force = false) {
  if ((scriptsLoaded.value && !force) || scriptsLoading.value) {
    return;
  }
  scriptsLoading.value = true;
  try {
    scripts.value = await studioApi.dataDevelopment.listScripts();
    scriptsLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  } finally {
    scriptsLoading.value = false;
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
  await ensureCollectionTasksLoaded();
}

function confirmCollectionTaskSelection() {
  bindCollectionTask(pendingCollectionTaskId.value);
  collectionTaskDialogVisible.value = false;
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
  await Promise.all([ensureScriptsLoaded(), ensureScriptTreeLoaded()]);
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
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  } finally {
    scriptPreviewLoading.value = false;
  }
}

function confirmScriptSelection() {
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

async function loadWorkflow() {
  if (!workflowId.value) {
    resetWorkflow();
    return;
  }
  try {
    const workflow = await studioApi.workflows.get(workflowId.value);
    applyWorkflow(workflow);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  }
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
  form.nodes = form.nodes.map((node) =>
    node.nodeCode === selectedNodeCode.value
      ? {
          ...node,
          nodeName: task.name,
          config: {
            ...(node.config ?? {}),
            collectionTaskId: task.id,
            collectionTaskName: task.name,
            collectionTaskType: task.taskType,
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
  const script = scripts.value.find((item) => String(item.id) === String(value));
  if (!selectedNode.value || !script) {
    return;
  }
  const nextConfig: Record<string, unknown> = {
    ...(selectedNode.value.config ?? {}),
    scriptId: script.id,
    scriptName: script.fileName,
    scriptType: script.scriptType,
    datasourceId: script.datasourceId,
    datasourceName: script.datasourceName,
  };
  if (script.scriptType !== "SQL") {
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
  if (!selectedBoundScript.value || selectedBoundScript.value.scriptType === "SQL") {
    return;
  }
  try {
    updateSelectedNode("config", {
      ...(selectedNode.value?.config ?? {}),
      arguments: parseDataScriptArguments(dataScriptArgumentsText.value),
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
    updateSelectedHttpConfig({ body: parseHttpBody(httpBodyText.value) });
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
    if (nodeType === "DATA_SCRIPT") {
      void ensureScriptsLoaded();
    }
  },
  { immediate: true },
);

watch(
  () => [selectedNodeCode.value, selectedBoundScript.value?.id, selectedNode.value?.config?.arguments] as const,
  () => {
    if (selectedBoundScript.value && selectedBoundScript.value.scriptType !== "SQL") {
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
    if (scriptType !== "SQL") {
      return {
        ...node,
        config: {
          ...(node.config ?? {}),
          arguments: parseDataScriptArguments(node.config?.arguments),
        },
      };
    }
    return {
      ...node,
      config: {
        ...(node.config ?? {}),
        maxRows: normalizeMaxRows(node.config?.maxRows),
      },
    };
  });
  return payload;
}

function parseDataScriptArguments(value: unknown) {
  if (value == null) {
    return {};
  }
  if (typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  const text = String(value).trim();
  if (!text) {
    return {};
  }
  const parsed = JSON.parse(text);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(t("web.workflows.dataScriptArgumentsInvalid"));
  }
  return parsed as Record<string, unknown>;
}

function normalizeMaxRows(value: unknown) {
  if (typeof value === "number") {
    return value;
  }
  if (typeof value === "string" && value.trim()) {
    return Number(value);
  }
  return 100;
}

function normalizeHttpParamRows(value: unknown): HttpParamRow[] {
  if (Array.isArray(value)) {
    return value
      .filter((item) => item != null)
      .map((item) => {
        if (typeof item === "object" && !Array.isArray(item)) {
          const record = item as Record<string, unknown>;
          return {
            enabled: record.enabled == null ? true : Boolean(record.enabled),
            name: String(record.name ?? record.key ?? ""),
            value: String(record.value ?? ""),
          };
        }
        return {
          enabled: true,
          name: "",
          value: String(item),
        };
      });
  }
  if (value && typeof value === "object") {
    return Object.entries(value as Record<string, unknown>).map(([name, itemValue]) => ({
      enabled: true,
      name,
      value: itemValue == null ? "" : String(itemValue),
    }));
  }
  return [];
}

function parseHttpBody(value: string) {
  const text = value.trim();
  if (!text) {
    return undefined;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new Error(t("web.workflows.httpBodyInvalid"));
  }
}

function matchesKeyword(keyword: string, values: unknown[]) {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) {
    return true;
  }
  return values
    .filter((value) => value != null)
    .some((value) => String(value).toLowerCase().includes(normalized));
}

function paginateItems<T>(items: T[], pagination: DialogPagination) {
  const start = (pagination.page - 1) * pagination.pageSize;
  return items.slice(start, start + pagination.pageSize);
}

function getDialogRowIndex(pagination: DialogPagination, index: number) {
  return (pagination.page - 1) * pagination.pageSize + index + 1;
}

function filterScriptTree(nodes: DataDevelopmentTreeNode[], keyword: string): DataDevelopmentTreeNode[] {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) {
    return nodes;
  }
  return nodes
    .map((node) => {
      const children = filterScriptTree(node.children ?? [], keyword);
      const selfMatched = [
        node.name,
        node.scriptType,
        node.datasourceName,
        node.permissionCode,
        node.scriptId,
        node.directoryId,
      ]
        .filter((value) => value != null)
        .some((value) => String(value).toLowerCase().includes(normalized));
      if (selfMatched || children.length > 0) {
        return {
          ...node,
          children,
        };
      }
      return null;
    })
    .filter((node): node is DataDevelopmentTreeNode => node != null);
}

function formatNullableText(value: unknown) {
  return value == null || value === "" ? "-" : String(value);
}

function formatQualityDimension(t: (key: string) => string, value?: string | null) {
  const mapping: Record<string, string> = {
    CONSISTENCY: "web.workflows.qualityDimensionConsistency",
    ACCURACY: "web.workflows.qualityDimensionAccuracy",
    UNIQUENESS: "web.workflows.qualityDimensionUniqueness",
    TIMELINESS: "web.workflows.qualityDimensionTimeliness",
    COMPLETENESS: "web.workflows.qualityDimensionCompleteness",
    VALIDITY: "web.workflows.qualityDimensionValidity",
  };
  return value && mapping[value] ? t(mapping[value]) : String(value ?? t("common.none"));
}

function formatQualityGranularity(t: (key: string) => string, value?: string | null) {
  if (value === "COLUMN") {
    return t("web.workflows.qualityGranularityColumn");
  }
  if (value === "TABLE") {
    return t("web.workflows.qualityGranularityTable");
  }
  return String(value ?? t("common.none"));
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
