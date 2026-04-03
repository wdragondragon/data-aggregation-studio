<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>Workflow studio</h3>
        <p>Use the canvas to compose execution graphs, then refine node config and field mappings in the side panels below.</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="resetWorkflow">New Workflow</el-button>
        <el-button plain @click="loadPage">Refresh</el-button>
        <el-button type="primary" :loading="saving" @click="saveWorkflow">Save Draft</el-button>
      </div>
    </div>

    <div class="studio-grid columns-2">
      <SectionCard title="Workflow Registry" description="Select a saved workflow or start fresh on the right-hand designer.">
        <el-table :data="workflows" border>
          <el-table-column prop="code" label="Code" min-width="130" />
          <el-table-column prop="name" label="Name" min-width="180" />
          <el-table-column label="Status" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.published ? 'Published' : 'Draft'" :tone="row.published ? 'success' : 'warning'" />
            </template>
          </el-table-column>
          <el-table-column label="Actions" width="220">
            <template #default="{ row }">
              <el-button link type="primary" @click="editWorkflow(row)">Edit</el-button>
              <el-button link type="success" :disabled="!row.id" @click="publishWorkflow(row)">Publish</el-button>
              <el-button link type="warning" :disabled="!row.id" @click="triggerWorkflow(row)">Trigger</el-button>
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>

      <SectionCard title="Workflow Basics" description="Versioned graph metadata and schedule controls.">
        <div class="studio-form-grid">
          <el-form-item label="Workflow Code">
            <el-input v-model="form.code" placeholder="orders_sync" />
          </el-form-item>
          <el-form-item label="Workflow Name">
            <el-input v-model="form.name" placeholder="Orders Sync" />
          </el-form-item>
          <el-form-item label="Cron Expression">
            <el-input v-model="form.schedule.cronExpression" placeholder="0 */30 * * * ?" />
          </el-form-item>
          <el-form-item label="Timezone">
            <el-input v-model="form.schedule.timezone" placeholder="Asia/Shanghai" />
          </el-form-item>
          <el-form-item label="Schedule Enabled">
            <el-switch v-model="form.schedule.enabled" inline-prompt active-text="On" inactive-text="Off" />
          </el-form-item>
        </div>
      </SectionCard>
    </div>

    <SectionCard title="Workflow Canvas" description="Drag nodes from the palette, connect them, then select a node to edit its runtime metadata.">
      <WorkflowCanvas
        :nodes="form.nodes"
        :edges="form.edges"
        @update:nodes="form.nodes = $event"
        @update:edges="form.edges = $event"
        @select-node="selectedNodeCode = $event"
      />
    </SectionCard>

    <div class="studio-grid columns-2">
      <SectionCard title="Selected Node" description="Node forms are kept generic so the platform stays loosely coupled to the execution engine.">
        <template v-if="selectedNode">
          <div class="soft-panel node-header">
            <div>
              <strong>{{ selectedNode.nodeName }}</strong>
              <p>{{ selectedNode.nodeType }}</p>
            </div>
            <el-button type="danger" plain @click="removeSelectedNode">Remove Node</el-button>
          </div>

          <el-form-item label="Node Name">
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
          Select a node on the canvas to edit runtime configuration and field mapping behavior.
        </div>
      </SectionCard>

      <SectionCard title="Edge Routing" description="Every edge keeps a simple condition for first-version graph routing.">
        <el-table :data="form.edges" border>
          <el-table-column prop="fromNodeCode" label="From" min-width="120" />
          <el-table-column prop="toNodeCode" label="To" min-width="120" />
          <el-table-column label="Condition" min-width="160">
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
import { ElMessage } from "element-plus";
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
  definitionId?: number;
  schedule: {
    cronExpression?: string;
    enabled?: boolean;
    timezone?: string;
  };
}

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
    ElMessage.error(error instanceof Error ? error.message : "Failed to load workflow studio");
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
    ElMessage.success("Workflow draft saved");
    editWorkflow(saved);
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to save workflow");
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
    ElMessage.success("Workflow published");
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to publish workflow");
  }
}

async function triggerWorkflow(workflow: WorkflowDefinitionView) {
  if (!workflow.id) {
    return;
  }
  try {
    await studioApi.workflows.trigger(workflow.id);
    ElMessage.success("Workflow trigger submitted");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to trigger workflow");
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
