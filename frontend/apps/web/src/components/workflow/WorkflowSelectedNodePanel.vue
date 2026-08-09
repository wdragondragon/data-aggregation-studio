<template>
  <SectionCard :title="t('web.workflows.selectedNodeTitle')" :description="t('web.workflows.selectedNodeDescription')">
    <template v-if="selectedNode">
      <div class="soft-panel node-header">
        <div>
          <strong>{{ selectedNode.nodeName }}</strong>
          <p>{{ formatNodeType(t, selectedNode.nodeType) }}</p>
        </div>
        <el-button type="danger" plain @click="nodeActions.removeSelectedNode">{{ t("web.workflows.removeNode") }}</el-button>
      </div>

      <el-form-item :label="t('web.workflows.nodeName')">
        <el-input :model-value="selectedNode.nodeName" @update:model-value="nodeActions.updateSelectedNode('nodeName', $event)" />
      </el-form-item>

      <template v-if="selectedNode.nodeType === 'COLLECTION_TASK'">
        <el-form-item :label="t('web.workflows.collectionTaskBinding')">
          <el-button type="primary" plain :loading="collectionTasksLoading" :disabled="!runtimeClusterSelected" @click="nodeActions.openCollectionTaskDialog">
            {{ selectedBoundTask ? t("web.workflows.reselectCollectionTask") : t("web.workflows.selectCollectionTask") }}
          </el-button>
        </el-form-item>
        <div class="soft-panel" v-if="selectedBoundTask">
          <strong>{{ selectedBoundTask.name }}</strong>
          <p>{{ formatCollectionTaskType(t, selectedBoundTask.taskType) }} · {{ selectedBoundTask.sourceCount }} {{ t("web.collectionTasks.sourceCountUnit") }}</p>
        </div>
      </template>

      <template v-else-if="selectedNode.nodeType === 'QUALITY_TASK'">
        <el-form-item :label="t('web.workflows.qualityTaskBinding')">
          <el-button type="primary" plain :loading="qualityTasksLoading" :disabled="!runtimeClusterSelected" @click="nodeActions.openQualityTaskDialog">
            {{ selectedBoundQualityTask ? t("web.workflows.reselectQualityTask") : t("web.workflows.selectQualityTask") }}
          </el-button>
        </el-form-item>
        <div class="soft-panel" v-if="selectedBoundQualityTask">
          <strong>{{ selectedBoundQualityTask.taskName }}</strong>
          <p>
            {{ selectedBoundQualityTask.ruleName || t("common.none") }}
            · {{ nodeActions.formatQualityDimension(selectedBoundQualityTask.ruleDimension) }}
            · {{ nodeActions.formatQualityGranularity(selectedBoundQualityTask.granularity) }}
          </p>
          <p>
            {{ selectedBoundQualityTask.datasourceName || t("common.none") }}
            · {{ selectedBoundQualityTask.modelName || t("common.none") }}
            · {{ selectedBoundQualityTask.columnName || (selectedBoundQualityTask.granularity === "COLUMN" ? t("common.none") : t("web.workflows.qualityTaskWholeTable")) }}
          </p>
        </div>
      </template>

      <template v-else-if="selectedNode.nodeType === 'FILE_TRANSFER'">
        <el-form-item :label="t('web.workflows.fileTransferTaskBinding')">
          <el-select
            :model-value="selectedBoundFileTransferTask?.id"
            filterable
            :loading="fileTransferTasksLoading"
            :disabled="!runtimeClusterSelected"
            :placeholder="t('web.workflows.selectFileTransferTask')"
            @update:model-value="nodeActions.bindFileTransferTask"
          >
            <el-option
              v-for="task in fileTransferTasks"
              :key="String(task.id)"
              :label="`${task.name} (${task.code})`"
              :value="task.id"
            />
          </el-select>
        </el-form-item>
        <div v-if="selectedBoundFileTransferTask" class="soft-panel">
          <strong>{{ selectedBoundFileTransferTask.name }}</strong>
          <p>
            {{ selectedBoundFileTransferTask.sourceDatasourceName || `#${selectedBoundFileTransferTask.sourceDatasourceId}` }}
            → {{ selectedBoundFileTransferTask.targetDatasourceName || `#${selectedBoundFileTransferTask.targetDatasourceId}` }}
          </p>
          <p>v{{ selectedBoundFileTransferTask.publishedVersion || selectedBoundFileTransferTask.version || 1 }}</p>
        </div>
      </template>

      <template v-else-if="selectedNode.nodeType === 'DATA_SCRIPT'">
        <el-form-item :label="t('web.workflows.dataScriptBinding')">
          <el-button type="primary" plain :loading="scriptsLoading || scriptTreeLoading" :disabled="!runtimeClusterSelected" @click="nodeActions.openScriptDialog">
            {{ selectedBoundScript ? t("web.workflows.reselectDataScript") : t("web.workflows.selectDataScript") }}
          </el-button>
        </el-form-item>
        <div class="soft-panel" v-if="selectedBoundScript">
          <strong>{{ selectedBoundScript.fileName }}</strong>
          <p>{{ selectedBoundScript.datasourceName || t("common.none") }} · {{ formatScriptType(t, selectedBoundScript.scriptType) }}</p>
          <p>{{ t("web.workflows.dataScriptSummary") }}</p>
        </div>
        <el-form-item v-if="selectedBoundScript && dataScriptUsesArguments(selectedBoundScript.scriptType)" :label="t('web.workflows.dataScriptArguments')">
          <el-input
            v-model="dataScriptArgumentsTextModel"
            type="textarea"
            :rows="6"
            :placeholder="t('web.workflows.dataScriptArgumentsPlaceholder')"
            @blur="nodeActions.applySelectedDataScriptArguments"
          />
        </el-form-item>
        <el-form-item v-else-if="selectedBoundScript" :label="t('web.workflows.dataScriptMaxRows')">
          <el-input-number
            :model-value="selectedScriptMaxRows"
            :min="1"
            controls-position="right"
            :placeholder="t('web.workflows.dataScriptMaxRowsPlaceholder')"
            @update:model-value="nodeActions.updateSelectedDataScriptMaxRows"
          />
        </el-form-item>
      </template>

      <template v-else-if="selectedNode.nodeType === 'HTTP'">
        <div class="http-node-editor">
          <div class="http-node-editor__grid">
            <el-form-item :label="t('web.workflows.httpUrl')">
              <el-input
                :model-value="selectedHttpUrl"
                :placeholder="t('web.workflows.httpUrlPlaceholder')"
                @update:model-value="nodeActions.updateSelectedHttpConfig({ url: $event })"
              />
            </el-form-item>
            <el-form-item :label="t('web.workflows.httpMethod')">
              <el-select
                :model-value="selectedHttpMethod"
                :placeholder="t('web.workflows.httpMethodPlaceholder')"
                @update:model-value="nodeActions.updateSelectedHttpConfig({ method: $event })"
              >
                <el-option label="GET" value="GET" />
                <el-option label="POST" value="POST" />
                <el-option label="PUT" value="PUT" />
                <el-option label="PATCH" value="PATCH" />
                <el-option label="DELETE" value="DELETE" />
              </el-select>
            </el-form-item>
          </div>

          <div class="http-param-section">
            <div class="http-param-section__header">
              <div>
                <strong>{{ t("web.workflows.httpQueryParams") }}</strong>
                <p>{{ t("web.workflows.httpQueryParamsDescription") }}</p>
              </div>
              <el-button size="small" plain @click="nodeActions.appendHttpParam('queryParams')">
                {{ t("web.workflows.addHttpParam") }}
              </el-button>
            </div>
            <el-table :data="selectedHttpQueryParams" border size="small" table-layout="fixed" class="http-param-table">
              <el-table-column :label="t('web.workflows.httpParamEnabled')" width="88" align="center">
                <template #default="{ row, $index }">
                  <el-switch
                    :model-value="row.enabled !== false"
                    @update:model-value="nodeActions.updateHttpParam('queryParams', $index, 'enabled', $event)"
                  />
                </template>
              </el-table-column>
              <el-table-column :label="t('web.workflows.httpParamName')" min-width="150">
                <template #default="{ row, $index }">
                  <el-input
                    :model-value="row.name"
                    :placeholder="t('web.workflows.httpParamNamePlaceholder')"
                    @update:model-value="nodeActions.updateHttpParam('queryParams', $index, 'name', $event)"
                  />
                </template>
              </el-table-column>
              <el-table-column :label="t('web.workflows.httpParamValue')" min-width="180">
                <template #default="{ row, $index }">
                  <el-input
                    :model-value="row.value"
                    :placeholder="t('web.workflows.httpParamValuePlaceholder')"
                    @update:model-value="nodeActions.updateHttpParam('queryParams', $index, 'value', $event)"
                  />
                </template>
              </el-table-column>
              <el-table-column :label="t('common.actions')" width="88" fixed="right" align="center">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="nodeActions.removeHttpParam('queryParams', $index)">
                    {{ t("common.delete") }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="http-param-section">
            <div class="http-param-section__header">
              <div>
                <strong>{{ t("web.workflows.httpHeaders") }}</strong>
                <p>{{ t("web.workflows.httpHeadersDescription") }}</p>
              </div>
              <el-button size="small" plain @click="nodeActions.appendHttpParam('headers')">
                {{ t("web.workflows.addHttpHeader") }}
              </el-button>
            </div>
            <el-table :data="selectedHttpHeaders" border size="small" table-layout="fixed" class="http-param-table">
              <el-table-column :label="t('web.workflows.httpParamEnabled')" width="88" align="center">
                <template #default="{ row, $index }">
                  <el-switch
                    :model-value="row.enabled !== false"
                    @update:model-value="nodeActions.updateHttpParam('headers', $index, 'enabled', $event)"
                  />
                </template>
              </el-table-column>
              <el-table-column :label="t('web.workflows.httpParamName')" min-width="150">
                <template #default="{ row, $index }">
                  <el-input
                    :model-value="row.name"
                    placeholder="Content-Type"
                    @update:model-value="nodeActions.updateHttpParam('headers', $index, 'name', $event)"
                  />
                </template>
              </el-table-column>
              <el-table-column :label="t('web.workflows.httpParamValue')" min-width="180">
                <template #default="{ row, $index }">
                  <el-input
                    :model-value="row.value"
                    placeholder="application/json"
                    @update:model-value="nodeActions.updateHttpParam('headers', $index, 'value', $event)"
                  />
                </template>
              </el-table-column>
              <el-table-column :label="t('common.actions')" width="88" fixed="right" align="center">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="nodeActions.removeHttpParam('headers', $index)">
                    {{ t("common.delete") }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <el-form-item :label="t('web.workflows.httpBody')">
            <el-input
              v-model="httpBodyTextModel"
              type="textarea"
              :rows="8"
              :placeholder="t('web.workflows.httpBodyPlaceholder')"
              @blur="nodeActions.applySelectedHttpBody"
            />
          </el-form-item>
        </div>
      </template>

      <template v-else>
        <MetaFormRenderer
          :fields="selectedNodeFields"
          :model-value="selectedNodeConfig"
          @update:model-value="nodeActions.updateSelectedNodeConfig"
        />
      </template>
    </template>

    <div v-else class="soft-panel">
      {{ t("web.workflows.emptySelectedNode") }}
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type {
  CollectionTaskWorkflowOptionView,
  DataDevelopmentScriptListView,
  FileTransferTaskDefinitionView,
  MetadataFieldDefinition,
  QualityTaskWorkflowOptionView,
  WorkflowNodeDefinition,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard } from "@studio/ui";
import {
  formatCollectionTaskType,
  formatNodeType,
  formatScriptType,
} from "@/utils/studio";

type HttpParamSection = "queryParams" | "headers";
type HttpParamField = "enabled" | "name" | "value";

interface HttpParamRow {
  enabled?: boolean;
  name: string;
  value: string;
}

interface WorkflowSelectedNodeActions {
  removeSelectedNode: () => void;
  updateSelectedNode: (key: keyof WorkflowNodeDefinition, value: unknown) => void;
  updateSelectedNodeConfig: (value: Record<string, unknown>) => void;
  openCollectionTaskDialog: () => void | Promise<void>;
  openQualityTaskDialog: () => void | Promise<void>;
  bindFileTransferTask: (value?: string | number) => void;
  openScriptDialog: () => void | Promise<void>;
  updateSelectedDataScriptMaxRows: (value: number | undefined) => void;
  applySelectedDataScriptArguments: () => void;
  updateSelectedHttpConfig: (patch: Record<string, unknown>) => void;
  appendHttpParam: (section: HttpParamSection) => void;
  updateHttpParam: (section: HttpParamSection, index: number, field: HttpParamField, value: unknown) => void;
  removeHttpParam: (section: HttpParamSection, index: number) => void;
  applySelectedHttpBody: () => void;
  formatQualityDimension: (value?: string | null) => string;
  formatQualityGranularity: (value?: string | null) => string;
}

const props = defineProps<{
  selectedNode?: WorkflowNodeDefinition;
  selectedBoundTask?: CollectionTaskWorkflowOptionView;
  selectedBoundQualityTask?: QualityTaskWorkflowOptionView;
  selectedBoundFileTransferTask?: FileTransferTaskDefinitionView;
  selectedBoundScript?: DataDevelopmentScriptListView;
  fileTransferTasks: FileTransferTaskDefinitionView[];
  collectionTasksLoading: boolean;
  qualityTasksLoading: boolean;
  fileTransferTasksLoading: boolean;
  scriptsLoading: boolean;
  scriptTreeLoading: boolean;
  dataScriptArgumentsText: string;
  selectedScriptMaxRows: number;
  selectedHttpUrl: string;
  selectedHttpMethod: string;
  selectedHttpQueryParams: HttpParamRow[];
  selectedHttpHeaders: HttpParamRow[];
  httpBodyText: string;
  selectedNodeFields: MetadataFieldDefinition[];
  selectedNodeConfig: Record<string, unknown>;
  runtimeClusterSelected: boolean;
  nodeActions: WorkflowSelectedNodeActions;
}>();

const emit = defineEmits<{
  "update:dataScriptArgumentsText": [value: string];
  "update:httpBodyText": [value: string];
}>();

const { t } = useI18n();

const dataScriptArgumentsTextModel = computed({
  get() {
    return props.dataScriptArgumentsText;
  },
  set(value: string) {
    emit("update:dataScriptArgumentsText", value);
  },
});

const httpBodyTextModel = computed({
  get() {
    return props.httpBodyText;
  },
  set(value: string) {
    emit("update:httpBodyText", value);
  },
});

function dataScriptUsesArguments(scriptType?: string | null) {
  const normalized = String(scriptType ?? "").toUpperCase();
  return normalized === "JAVA" || normalized === "PYTHON";
}
</script>

<style scoped>
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

.http-node-editor {
  display: grid;
  gap: 18px;
}

.http-node-editor__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 160px;
  gap: 14px;
}

.http-param-section {
  display: grid;
  gap: 10px;
}

.http-param-section__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.http-param-section__header strong {
  display: block;
  margin-bottom: 3px;
}

.http-param-table :deep(.cell) {
  overflow: visible;
}

@media (max-width: 1080px) {
  .http-node-editor__grid {
    grid-template-columns: 1fr;
  }
}
</style>
