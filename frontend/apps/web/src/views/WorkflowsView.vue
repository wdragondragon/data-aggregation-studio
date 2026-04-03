<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.workflows.heading") }}</h3>
        <p>{{ t("web.workflows.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="resetWorkflow">{{ t("common.newWorkflow") }}</el-button>
        <el-button plain @click="loadPage">{{ t("common.refresh") }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveWorkflow">{{ t("common.saveDraft") }}</el-button>
      </div>
    </div>

    <div class="studio-grid columns-2">
      <SectionCard :title="t('web.workflows.registryTitle')" :description="t('web.workflows.registryDescription')">
        <el-table :data="workflows" border>
          <el-table-column prop="code" :label="t('web.workflows.code')" min-width="130" />
          <el-table-column prop="name" :label="t('web.workflows.name')" min-width="180" />
          <el-table-column :label="t('web.workflows.status')" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.published ? t('common.published') : t('common.draft')" :tone="row.published ? 'success' : 'warning'" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.actions')" width="300">
            <template #default="{ row }">
              <el-button link type="primary" @click="editWorkflow(row)">{{ t("common.edit") }}</el-button>
              <el-button link type="success" :disabled="!row.id" @click="publishWorkflow(row)">{{ t("common.publish") }}</el-button>
              <el-button link type="warning" :disabled="!row.id" @click="triggerWorkflow(row)">{{ t("common.trigger") }}</el-button>
              <el-button link type="danger" :disabled="!row.id" @click="deleteWorkflow(row)">{{ t("common.delete") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>

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
    </div>

    <SectionCard :title="t('web.workflows.canvasTitle')" :description="t('web.workflows.canvasDescription')">
      <WorkflowCanvas
        :nodes="form.nodes"
        :edges="form.edges"
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

          <MetaFormRenderer
            :fields="selectedNodeFields"
            :model-value="selectedNodeConfig"
            @update:model-value="selectedNodeConfig = $event"
          />

          <FieldMappingEditor
            :model-value="selectedNode.fieldMappings"
            :source-fields="sourceFieldOptions"
            :target-fields="targetFieldOptions"
            :transformer-options="transformerOptions"
            @update:model-value="updateSelectedNode('fieldMappings', $event)"
          />
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
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  DataSourceDefinition,
  MetadataFieldDefinition,
  PluginCatalogEntry,
  WorkflowDefinitionView,
  WorkflowNodeDefinition,
  WorkflowSaveRequest,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { FieldMappingEditor, WorkflowCanvas } from "@studio/workflow-designer";
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

const workflows = ref<WorkflowDefinitionView[]>([]);
const datasources = ref<DataSourceDefinition[]>([]);
const transformers = ref<PluginCatalogEntry[]>([]);
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
const selectedNode = computed(() => form.nodes.find((node) => node.nodeCode === selectedNodeCode.value));

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

const datasourceOptions = computed(() => datasources.value.map((item) => item.name));

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
    { fieldKey: "sourceFields", fieldName: "Source Fields", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING", placeholder: "id,name,updated_at" },
    { fieldKey: "targetFields", fieldName: "Target Fields", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING", placeholder: "id,name,updated_at" },
    { fieldKey: "reader", fieldName: "Reader Config JSON", scope: "TECHNICAL", componentType: "JSON_EDITOR", valueType: "JSON" },
    { fieldKey: "writer", fieldName: "Writer Config JSON", scope: "TECHNICAL", componentType: "JSON_EDITOR", valueType: "JSON" },
    { fieldKey: "transformer", fieldName: "Transformer Config JSON", scope: "TECHNICAL", componentType: "JSON_EDITOR", valueType: "JSON" },
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

async function loadPage() {
  try {
    const [workflowData, datasourceData, transformerData] = await Promise.all([
      studioApi.workflows.list(),
      studioApi.datasources.list(),
      studioApi.catalog.plugins("TRANSFORMER"),
    ]);
    workflows.value = workflowData;
    datasources.value = datasourceData;
    transformers.value = transformerData;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  }
}

function editWorkflow(workflow: WorkflowDefinitionView) {
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
    editWorkflow(saved);
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.saveFailed"));
  } finally {
    saving.value = false;
  }
}

async function publishWorkflow(workflow: WorkflowDefinitionView) {
  if (!workflow.id) {
    return;
  }
  try {
    await studioApi.workflows.publish(workflow.id);
    ElMessage.success(t("web.workflows.publishSuccess"));
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.publishFailed"));
  }
}

async function triggerWorkflow(workflow: WorkflowDefinitionView) {
  if (!workflow.id) {
    return;
  }
  try {
    await studioApi.workflows.trigger(workflow.id);
    ElMessage.success(t("web.workflows.triggerSuccess"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.triggerFailed"));
  }
}

async function deleteWorkflow(workflow: WorkflowDefinitionView) {
  if (!workflow.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.workflows.deleteConfirmMessage", { name: workflow.name }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.workflows.delete(workflow.id);
    if (form.definitionId === workflow.id) {
      resetWorkflow();
    }
    ElMessage.success(t("web.workflows.deleteSuccess"));
    await loadPage();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.workflows.deleteFailed"));
    }
  }
}

onMounted(loadPage);
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
