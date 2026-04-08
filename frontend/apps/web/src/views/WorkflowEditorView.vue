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

    <div class="studio-grid columns-2">
      <SectionCard :title="t('web.workflows.basicsTitle')" :description="t('web.workflows.basicsDescription')">
        <div class="studio-form-grid">
          <el-form-item :label="t('web.workflows.workflowCode')">
            <el-input v-model="form.code" :placeholder="t('web.workflows.placeholderWorkflowCode')" />
          </el-form-item>
          <el-form-item :label="t('web.workflows.workflowName')">
            <el-input v-model="form.name" :placeholder="t('web.workflows.placeholderWorkflowName')" />
          </el-form-item>
          <el-form-item :label="t('web.workflows.cronExpression')" class="cron-form-item">
            <CronExpressionPicker
              v-model="form.schedule.cronExpression"
              :label="t('web.workflows.cronExpression')"
            />
          </el-form-item>
          <el-form-item :label="t('web.workflows.timezone')">
            <el-input v-model="form.schedule.timezone" placeholder="Asia/Shanghai" />
          </el-form-item>
          <el-form-item :label="t('web.workflows.scheduleEnabled')">
            <el-switch v-model="form.schedule.enabled" inline-prompt :active-text="t('common.on')" :inactive-text="t('common.off')" />
          </el-form-item>
        </div>
      </SectionCard>

      <SectionCard :title="t('web.workflows.availableTasksTitle')" :description="t('web.workflows.availableTasksDescription')">
        <el-table :data="onlineCollectionTasks" border height="240">
          <el-table-column prop="name" :label="t('web.collectionTasks.name')" min-width="180" />
          <el-table-column :label="t('web.collectionTasks.type')" width="140" align="center" header-align="center">
            <template #default="{ row }">
              {{ formatCollectionTaskType(t, row.taskType) }}
            </template>
          </el-table-column>
          <el-table-column prop="sourceCount" :label="t('web.collectionTasks.sourceCount')" width="120" align="center" header-align="center" />
          <el-table-column :label="t('web.collectionTasks.status')" width="120" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill :label="formatStatusLabel(t, row.status)" tone="success" />
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>
    </div>

    <SectionCard :title="t('web.workflows.availableScriptsTitle')" :description="t('web.workflows.availableScriptsDescription')">
      <el-table :data="scripts" border size="small" max-height="220">
        <el-table-column prop="fileName" :label="t('web.dataDevelopment.scriptName')" min-width="220" />
        <el-table-column prop="datasourceName" :label="t('web.dataDevelopment.datasource')" min-width="180" />
        <el-table-column :label="t('web.dataDevelopment.scriptType')" width="120" align="center" header-align="center">
          <template #default="{ row }">
            {{ formatScriptType(t, row.scriptType) }}
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>

    <SectionCard :title="t('web.workflows.canvasTitle')" :description="t('web.workflows.canvasDescription')">
      <WorkflowCanvas
        :nodes="form.nodes"
        :edges="form.edges"
        :palette-types="['COLLECTION_TASK', 'DATA_SCRIPT', 'CONSISTENCY', 'HTTP', 'SHELL']"
        @update:nodes="form.nodes = $event"
        @update:edges="form.edges = $event"
        @select-node="selectedNodeCode = $event"
      />
    </SectionCard>

    <div class="studio-grid columns-2">
      <SectionCard :title="t('web.workflows.selectedNodeTitle')" :description="t('web.workflows.selectedNodeDescription')">
        <template v-if="selectedNode">
          <div class="soft-panel node-header">
            <div>
              <strong>{{ selectedNode.nodeName }}</strong>
              <p>{{ formatNodeType(t, selectedNode.nodeType) }}</p>
            </div>
            <el-button type="danger" plain @click="removeSelectedNode">{{ t("web.workflows.removeNode") }}</el-button>
          </div>

          <el-form-item :label="t('web.workflows.nodeName')">
            <el-input :model-value="selectedNode.nodeName" @update:model-value="updateSelectedNode('nodeName', $event)" />
          </el-form-item>

          <template v-if="selectedNode.nodeType === 'COLLECTION_TASK'">
            <el-form-item :label="t('web.workflows.collectionTaskBinding')">
              <el-select :model-value="selectedCollectionTaskId" filterable @update:model-value="bindCollectionTask">
                <el-option
                  v-for="task in onlineCollectionTasks"
                  :key="task.id"
                  :label="`${task.name} (${formatCollectionTaskType(t, task.taskType)})`"
                  :value="String(task.id)"
                />
              </el-select>
            </el-form-item>
            <div class="soft-panel" v-if="selectedBoundTask">
              <strong>{{ selectedBoundTask.name }}</strong>
              <p>{{ formatCollectionTaskType(t, selectedBoundTask.taskType) }} · {{ selectedBoundTask.sourceCount }} {{ t("web.collectionTasks.sourceCountUnit") }}</p>
            </div>
          </template>

            <template v-else-if="selectedNode.nodeType === 'DATA_SCRIPT'">
              <el-form-item :label="t('web.workflows.dataScriptBinding')">
                <el-select :model-value="selectedScriptId" filterable @update:model-value="bindScript">
                  <el-option
                    v-for="script in scripts"
                    :key="script.id"
                    :label="`${script.fileName} (${formatScriptType(t, script.scriptType)})`"
                    :value="String(script.id)"
                  />
                </el-select>
              </el-form-item>
            <div class="soft-panel" v-if="selectedBoundScript">
              <strong>{{ selectedBoundScript.fileName }}</strong>
              <p>{{ selectedBoundScript.datasourceName || t("common.none") }} · {{ formatScriptType(t, selectedBoundScript.scriptType) }}</p>
              <p>{{ t("web.workflows.dataScriptSummary") }}</p>
            </div>
            <el-form-item v-if="selectedBoundScript && selectedBoundScript.scriptType !== 'SQL'" :label="t('web.workflows.dataScriptArguments')">
              <el-input
                v-model="dataScriptArgumentsText"
                type="textarea"
                :rows="6"
                :placeholder="t('web.workflows.dataScriptArgumentsPlaceholder')"
                @blur="applySelectedDataScriptArguments"
              />
            </el-form-item>
            <el-form-item v-else-if="selectedBoundScript" :label="t('web.workflows.dataScriptMaxRows')">
              <el-input-number
                :model-value="selectedScriptMaxRows"
                :min="1"
                controls-position="right"
                :placeholder="t('web.workflows.dataScriptMaxRowsPlaceholder')"
                @update:model-value="updateSelectedDataScriptMaxRows"
              />
            </el-form-item>
          </template>

          <template v-else>
            <MetaFormRenderer
              :fields="selectedNodeFields"
              :model-value="selectedNodeConfig"
              @update:model-value="selectedNodeConfig = $event"
            />
            <FieldMappingEditor
              v-if="selectedNode.nodeType === 'CONSISTENCY'"
              :model-value="selectedNode.fieldMappings"
              :source-fields="sourceFieldOptions"
              :target-fields="targetFieldOptions"
              :transformer-options="transformerOptions"
              @update:model-value="updateSelectedNode('fieldMappings', $event)"
            />
          </template>
        </template>

        <div v-else class="soft-panel">
          {{ t("web.workflows.emptySelectedNode") }}
        </div>
      </SectionCard>

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
  DataSourceDefinition,
  MetadataFieldDefinition,
  PluginCatalogEntry,
  WorkflowDefinitionView,
  WorkflowNodeDefinition,
  WorkflowSaveRequest,
} from "@studio/api-sdk";
import { FieldMappingEditor, WorkflowCanvas } from "@studio/workflow-designer";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import CronExpressionPicker from "@web/components/CronExpressionPicker.vue";
import {
  cloneDeep,
  formatCollectionTaskType,
  formatNodeType,
  formatScriptType,
  formatStatusLabel,
  parseCommaSeparated,
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

const { t } = useI18n();
const route = useRoute();
const router = useRouter();

const workflowId = computed(() => route.params.workflowId as string | undefined);
const datasources = ref<DataSourceDefinition[]>([]);
const transformers = ref<PluginCatalogEntry[]>([]);
const onlineCollectionTasks = ref<CollectionTaskDefinitionView[]>([]);
const scripts = ref<DataDevelopmentScript[]>([]);
const selectedNodeCode = ref<string | null>(null);
const saving = ref(false);
const dataScriptArgumentsText = ref("{}");
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

const transformerOptions = computed(() => Array.from(new Set(transformers.value.map((item) => item.pluginName))));
const datasourceOptions = computed(() => datasources.value.map((item) => item.name));
const selectedNode = computed(() => form.nodes.find((node) => node.nodeCode === selectedNodeCode.value));
const selectedCollectionTaskId = computed(() => {
  if (!selectedNode.value?.config?.collectionTaskId) {
    return undefined;
  }
  return String(selectedNode.value.config.collectionTaskId);
});
const selectedBoundTask = computed(() =>
  onlineCollectionTasks.value.find((item) => String(item.id) === selectedCollectionTaskId.value),
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

const sourceFieldOptions = computed(() => parseCommaSeparated(selectedNodeConfig.value.sourceFields));
const targetFieldOptions = computed(() => parseCommaSeparated(selectedNodeConfig.value.targetFields));
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
    const [datasourceData, transformerData, taskData, scriptData] = await Promise.all([
      studioApi.datasources.list(),
      studioApi.catalog.plugins("TRANSFORMER"),
      studioApi.collectionTasks.listOnline(),
      studioApi.dataDevelopment.listScripts(),
    ]);
    datasources.value = datasourceData;
    transformers.value = transformerData;
    onlineCollectionTasks.value = taskData;
    scripts.value = scriptData;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  }
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

function bindCollectionTask(value: string) {
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

function bindScript(value: string) {
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

onMounted(loadReferenceData);

function buildWorkflowPayload() {
  const payload = cloneDeep(form);
  payload.nodes = payload.nodes.map((node) => {
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
</script>

<style scoped>
.cron-form-item {
  grid-column: 1 / -1;
}
</style>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
</style>
