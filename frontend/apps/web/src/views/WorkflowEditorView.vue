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
          <el-form-item :label="t('web.workflows.cronExpression')">
            <el-input v-model="form.schedule.cronExpression" :placeholder="t('web.workflows.placeholderCron')" />
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
          <el-table-column prop="taskType" :label="t('web.collectionTasks.type')" width="140" />
          <el-table-column prop="sourceCount" :label="t('web.collectionTasks.sourceCount')" width="120" />
          <el-table-column :label="t('web.collectionTasks.status')" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.status ?? t('common.unknown')" tone="success" />
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>
    </div>

    <SectionCard :title="t('web.workflows.availableScriptsTitle')" :description="t('web.workflows.availableScriptsDescription')">
      <el-table :data="sqlScripts" border size="small" max-height="220">
        <el-table-column prop="fileName" :label="t('web.dataDevelopment.scriptName')" min-width="220" />
        <el-table-column prop="datasourceName" :label="t('web.dataDevelopment.datasource')" min-width="180" />
        <el-table-column prop="scriptType" :label="t('web.dataDevelopment.scriptType')" width="120" />
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
              <p>{{ selectedNode.nodeType }}</p>
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
                  :label="`${task.name} (${task.taskType})`"
                  :value="String(task.id)"
                />
              </el-select>
            </el-form-item>
            <div class="soft-panel" v-if="selectedBoundTask">
              <strong>{{ selectedBoundTask.name }}</strong>
              <p>{{ selectedBoundTask.taskType }} · {{ selectedBoundTask.sourceCount }} {{ t("web.collectionTasks.sourceCountUnit") }}</p>
            </div>
          </template>

          <template v-else-if="selectedNode.nodeType === 'DATA_SCRIPT'">
            <el-form-item :label="t('web.workflows.dataScriptBinding')">
              <el-select :model-value="selectedScriptId" filterable @update:model-value="bindScript">
                <el-option
                  v-for="script in sqlScripts"
                  :key="script.id"
                  :label="`${script.fileName} (${script.datasourceName || t('common.none')})`"
                  :value="String(script.id)"
                />
              </el-select>
            </el-form-item>
            <div class="soft-panel" v-if="selectedBoundScript">
              <strong>{{ selectedBoundScript.fileName }}</strong>
              <p>{{ selectedBoundScript.datasourceName || t("common.none") }} · {{ selectedBoundScript.scriptType }}</p>
              <p>{{ t("web.workflows.dataScriptSummary") }}</p>
            </div>
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
import { cloneDeep, parseCommaSeparated } from "@/utils/studio";

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
const sqlScripts = ref<DataDevelopmentScript[]>([]);
const selectedNodeCode = ref<string | null>(null);
const saving = ref(false);
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
  sqlScripts.value.find((item) => String(item.id) === selectedScriptId.value),
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
      studioApi.dataDevelopment.listScripts("SQL"),
    ]);
    datasources.value = datasourceData;
    transformers.value = transformerData;
    onlineCollectionTasks.value = taskData;
    sqlScripts.value = scriptData;
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
  const script = sqlScripts.value.find((item) => String(item.id) === String(value));
  if (!selectedNode.value || !script) {
    return;
  }
  form.nodes = form.nodes.map((node) =>
    node.nodeCode === selectedNodeCode.value
      ? {
          ...node,
          nodeName: script.fileName,
          config: {
            ...(node.config ?? {}),
            scriptId: script.id,
            scriptName: script.fileName,
            scriptType: script.scriptType,
            datasourceId: script.datasourceId,
            datasourceName: script.datasourceName,
          },
        }
      : node,
  );
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
    const saved = await studioApi.workflows.save(cloneDeep(form));
    ElMessage.success(t("web.workflows.saveSuccess"));
    applyWorkflow(saved);
    if (!workflowId.value && saved.id) {
      await router.replace(`/workflows/${saved.id}/edit`);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.saveFailed"));
  } finally {
    saving.value = false;
  }
}

watch(workflowId, async () => {
  await loadWorkflow();
}, { immediate: true });

onMounted(loadReferenceData);
</script>

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
