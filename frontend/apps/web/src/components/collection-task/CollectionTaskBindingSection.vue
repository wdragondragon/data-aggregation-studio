<template>
  <SectionCard :title="t('web.collectionTasks.bindingTitle')" :description="t('web.collectionTasks.bindingDescription')">
    <div class="studio-form-grid">
      <el-form-item :label="t('web.runtimeClusterSelection.runtimeCluster')" required>
        <el-select v-model="form.runtimeClusterId" :loading="runtimeClustersLoading" :disabled="streamingLocked || singleRuntimeCluster" :placeholder="t('web.runtimeClusterSelection.placeholder')" @change="onRuntimeClusterChange">
          <el-option v-for="cluster in runtimeClusters" :key="String(cluster.id)" :label="runtimeClusterOptionLabel(cluster)" :value="cluster.id" :disabled="runtimeClusterOptionDisabled(cluster)" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('web.collectionTasks.name')">
        <el-input v-model="form.name" :disabled="streamingLocked" :placeholder="t('web.collectionTasks.namePlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('web.collectionTasks.type')">
        <el-input :model-value="taskTypeLabel" disabled />
      </el-form-item>
      <el-form-item :label="t('web.collectionTasks.executionMode')">
        <el-radio-group v-model="form.executionMode" :disabled="streamingLocked">
          <el-radio-button value="BATCH">{{ t("web.collectionTasks.executionModeBatch") }}</el-radio-button>
          <el-radio-button value="STREAMING">{{ t("web.collectionTasks.executionModeStreaming") }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="collectionModeVisible" :label="t('web.collectionTasks.collectionMode')">
        <el-radio-group v-model="collectionModeModel" :disabled="streamingLocked">
          <el-radio-button value="FULL">{{ t("web.collectionTasks.collectionModeFull") }}</el-radio-button>
          <el-radio-button value="INCREMENTAL">{{ t("web.collectionTasks.collectionModeIncremental") }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </div>

    <div v-if="form.executionMode === 'STREAMING'" class="streaming-options-panel">
      <div class="runtime-option-header">
        <div>
          <strong>{{ t("web.collectionTasks.streamingOptionsTitle") }}</strong>
          <p>{{ t("web.collectionTasks.streamingOptionsDescription") }}</p>
        </div>
        <el-tag type="warning">{{ t("web.collectionTasks.atLeastOnce") }}</el-tag>
      </div>
      <div class="studio-form-grid streaming-options-grid">
        <el-form-item :label="t('web.collectionTasks.groupId')">
          <el-input v-model="form.streamingOptions.groupId" :disabled="streamingLocked" :placeholder="t('web.collectionTasks.groupIdPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.offsetReset')">
          <el-select v-model="form.streamingOptions.offsetReset" :disabled="streamingLocked">
            <el-option label="earliest" value="earliest" />
            <el-option label="latest" value="latest" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.resetOffset')">
          <el-switch v-model="form.streamingOptions.resetOffset" :disabled="streamingLocked" />
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.pollTimeoutMs')">
          <el-input-number v-model="form.streamingOptions.pollTimeoutMs" :disabled="streamingLocked" :min="100" :max="60000" :step="100" controls-position="right" />
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.maxBatchRecords')">
          <el-input-number v-model="form.streamingOptions.maxBatchRecords" :disabled="streamingLocked" :min="1" :max="100000" controls-position="right" />
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.maxBatchBytes')">
          <el-input-number v-model="form.streamingOptions.maxBatchBytes" :disabled="streamingLocked" :min="1024" :max="268435456" controls-position="right" />
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.batchRetryCount')">
          <el-input-number v-model="form.streamingOptions.batchRetryCount" :disabled="streamingLocked" :min="0" :max="10" controls-position="right" />
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.stopTimeoutMs')">
          <el-input-number v-model="form.streamingOptions.stopTimeoutMs" :disabled="streamingLocked" :min="1000" :max="600000" :step="1000" controls-position="right" />
        </el-form-item>
      </div>
    </div>

    <div class="soft-panel">
      <div class="section-toolbar">
        <div>
          <strong>{{ t("web.collectionTasks.sourcesTitle") }}</strong>
          <p>{{ isFusionTask ? t("web.collectionTasks.sourcesDescription") : t("web.collectionTasks.singleSourceDescription") }}</p>
        </div>
        <el-button type="primary" plain :disabled="streamingLocked" @click="bindingActions.appendSourceBinding">{{ t("common.addRow") }}</el-button>
      </div>

      <StudioTableShell min-width="720px">
      <el-table :data="form.sourceBindings" border>
        <el-table-column v-if="isFusionTask" :label="t('web.collectionTasks.sourceAlias')" min-width="150">
          <template #default="{ row }">
            <el-input v-model="row.sourceAlias" :disabled="streamingLocked" :placeholder="t('web.collectionTasks.sourceAliasPlaceholder')" />
          </template>
        </el-table-column>

        <el-table-column :label="t('web.collectionTasks.datasource')" min-width="220">
          <template #default="{ row }">
            <el-select
              :model-value="String(row.datasourceId ?? '')"
              filterable
              :disabled="streamingLocked || !form.runtimeClusterId"
              :placeholder="t('web.collectionTasks.datasourcePlaceholder')"
              @update:model-value="bindingActions.handleSourceDatasourceChange(row, $event)"
            >
              <el-option v-for="datasource in datasources" :key="datasource.id" :label="datasource.name" :value="String(datasource.id)" />
            </el-select>
          </template>
        </el-table-column>

        <el-table-column :label="t('web.collectionTasks.model')" min-width="220">
          <template #default="{ row }">
            <el-select
              :model-value="String(row.modelId ?? '')"
              filterable
              remote
              :disabled="streamingLocked || !form.runtimeClusterId || !row.datasourceId"
              :placeholder="t('web.collectionTasks.modelPlaceholder')"
              :remote-method="sourceModelRemoteMethod(row)"
              @visible-change="sourceModelVisibleChange(row)"
              @update:model-value="bindingActions.handleSourceModelChange(row, $event)"
            >
              <el-option
                v-for="model in bindingActions.resolveModelsByDatasource(row.datasourceId)"
                :key="model.id"
                :label="`${model.name}${model.physicalLocator ? ` / ${model.physicalLocator}` : ''}`"
                :value="String(model.id)"
              />
            </el-select>
          </template>
        </el-table-column>

        <el-table-column :label="t('fieldMapping.actions')" width="110" fixed="right">
          <template #default="{ $index }">
            <el-button link type="danger" :disabled="streamingLocked || form.sourceBindings.length === 1" @click="bindingActions.removeSourceBinding($index)">
              {{ t("common.remove") }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      </StudioTableShell>
    </div>

    <div v-if="form.sourceBindings.length" class="advanced-options">
      <div
        v-for="source in form.sourceBindings"
        :key="source.sourceAlias || String(source.datasourceId ?? '')"
        class="runtime-option-block"
      >
        <div class="runtime-option-header">
          <div>
            <strong>
              {{ isFusionTask
                ? `${t("web.collectionTasks.readerAdvancedOptions")} - ${source.sourceAlias || t("common.none")}`
                : t("web.collectionTasks.readerAdvancedOptions") }}
            </strong>
            <p>{{ bindingActions.runtimeSchemaTitle("reader", source.datasourceId, source.modelId) }}</p>
          </div>
          <el-tag v-if="bindingActions.runtimeSchemaFor('reader', source.datasourceId, source.modelId)" :type="bindingActions.runtimeStatusType('reader', source.datasourceId, source.modelId)">
            {{ bindingActions.runtimeStatusLabel("reader", source.datasourceId, source.modelId) }}
          </el-tag>
        </div>

        <div v-if="source.incremental && bindingActions.sourceIncrementalVisible(source)" class="studio-form-grid incremental-grid">
          <el-form-item :label="t('web.collectionTasks.incrementalColumn')">
            <el-select
              v-model="source.incremental.incrColumn"
              filterable
              clearable
              :disabled="streamingLocked"
              :placeholder="t('web.collectionTasks.incrementalColumn')"
              @change="bindingActions.syncCurrentIncrementalCursor(source)"
            >
              <el-option v-for="field in bindingActions.sourceFieldOptions(source)" :key="field" :label="field" :value="field" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('web.collectionTasks.incrementalModel')">
            <el-select v-model="source.incremental.incrModel" :disabled="streamingLocked" @change="bindingActions.syncCurrentIncrementalCursor(source)">
              <el-option label=">" value=">" />
              <el-option label=">=" value=">=" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('web.collectionTasks.incrementalValue')">
            <div class="incremental-cursor">
              <div class="incremental-cursor__body">
                <div
                  class="studio-mono incremental-cursor__value"
                  :class="{ 'incremental-cursor__value--empty': !bindingActions.hasIncrementalCursor(source) }"
                >
                  {{ bindingActions.formatIncrementalCursorValue(source.incremental.pkValue) }}
                </div>
                <div class="incremental-cursor__meta">
                  <span>{{ t("web.collectionTasks.incrementalCursorLastRun") }}: {{ bindingActions.formatIncrementalCursorMeta(source.incremental.lastRunRecordId) }}</span>
                  <span>{{ t("web.collectionTasks.incrementalCursorUpdatedAt") }}: {{ bindingActions.formatIncrementalCursorMeta(source.incremental.lastUpdatedAt) }}</span>
                </div>
              </div>
              <el-button
                plain
                type="warning"
                size="small"
                :disabled="streamingLocked || !taskId || !bindingActions.hasIncrementalCursor(source)"
                :loading="bindingActions.isIncrementalCursorResetting(source)"
                @click="bindingActions.resetIncrementalCursor(source)"
              >
                {{ t("web.collectionTasks.resetIncrementalCursor") }}
              </el-button>
            </div>
          </el-form-item>
        </div>

        <HttpWebServiceOptionsEditor
          v-if="bindingActions.readerAdvancedFields(source).length && bindingActions.isHttpSoapReaderSource(source)"
          :key="readerOptionsEditorKey(source, 'soap')"
          :fields="bindingActions.readerAdvancedFields(source)"
          :model-value="source.readerOptions ?? {}"
          :dynamic-function-fields="bindingActions.readerDynamicFunctionFields(source)"
          :soap-contract="bindingActions.readerSoapContract(source)"
          :soap-field-names="bindingActions.readerSoapFieldNames(source)"
          :disabled="streamingLocked"
          @update:model-value="bindingActions.updateSourceReaderOptions(source, $event)"
          @dirty-key="bindingActions.markSourceReaderOptionDirty(source, $event)"
        />
        <HttpRequestOptionsEditor
          v-else-if="bindingActions.readerAdvancedFields(source).length && bindingActions.isHttpReaderSource(source)"
          :key="readerOptionsEditorKey(source, 'http')"
          :fields="bindingActions.readerAdvancedFields(source)"
          :model-value="source.readerOptions ?? {}"
          :dynamic-function-fields="bindingActions.readerDynamicFunctionFields(source)"
          :disabled="streamingLocked"
          @update:model-value="bindingActions.updateSourceReaderOptions(source, $event)"
          @dirty-key="bindingActions.markSourceReaderOptionDirty(source, $event)"
        />
        <MetaFormRenderer
          v-else-if="bindingActions.readerAdvancedFields(source).length"
          :fields="bindingActions.readerAdvancedFields(source)"
          :model-value="source.readerOptions ?? {}"
          :dynamic-function-fields="bindingActions.readerDynamicFunctionFields(source)"
          :disabled="streamingLocked"
          @update:model-value="bindingActions.updateSourceReaderOptions(source, $event)"
        />
        <el-alert
          v-else-if="bindingActions.runtimeSchemaFor('reader', source.datasourceId, source.modelId) && !bindingActions.runtimeSchemaFor('reader', source.datasourceId, source.modelId)?.runtimeSupported"
          type="warning"
          :closable="false"
          show-icon
          :title="t('web.collectionTasks.runtimeUnsupported')"
        />
        <el-alert
          v-else-if="bindingActions.runtimeSchemaFor('reader', source.datasourceId, source.modelId)"
          type="info"
          :closable="false"
          show-icon
          :title="t('web.collectionTasks.runtimeSchemaMissing')"
        />
      </div>
    </div>

    <div class="soft-panel">
      <strong>{{ t("web.collectionTasks.targetTitle") }}</strong>
      <p>{{ t("web.collectionTasks.targetDescription") }}</p>
      <div class="studio-form-grid">
        <el-form-item :label="t('web.collectionTasks.datasource')">
          <el-select
            :model-value="String(form.targetBinding.datasourceId ?? '')"
            filterable
            :disabled="streamingLocked || !form.runtimeClusterId"
            :placeholder="t('web.collectionTasks.datasourcePlaceholder')"
            @update:model-value="bindingActions.handleTargetDatasourceChange"
          >
            <el-option v-for="datasource in datasources" :key="datasource.id" :label="datasource.name" :value="String(datasource.id)" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.model')">
          <el-select
            :model-value="String(form.targetBinding.modelId ?? '')"
            filterable
            remote
            :disabled="streamingLocked || !form.runtimeClusterId || !form.targetBinding.datasourceId"
            :placeholder="t('web.collectionTasks.modelPlaceholder')"
            :remote-method="targetModelRemoteMethod"
            @visible-change="targetModelVisibleChange"
            @update:model-value="bindingActions.handleTargetModelChange"
          >
            <el-option
              v-for="model in bindingActions.resolveModelsByDatasource(form.targetBinding.datasourceId)"
              :key="model.id"
              :label="`${model.name}${model.physicalLocator ? ` / ${model.physicalLocator}` : ''}`"
              :value="String(model.id)"
            />
          </el-select>
        </el-form-item>
      </div>

      <div class="runtime-option-block runtime-option-block--target">
        <div class="runtime-option-header">
          <div>
            <strong>{{ t("web.collectionTasks.writerAdvancedOptions") }}</strong>
            <p>{{ bindingActions.runtimeSchemaTitle("writer", form.targetBinding.datasourceId, form.targetBinding.modelId) }}</p>
          </div>
          <el-tag v-if="bindingActions.runtimeSchemaFor('writer', form.targetBinding.datasourceId, form.targetBinding.modelId)" :type="bindingActions.runtimeStatusType('writer', form.targetBinding.datasourceId, form.targetBinding.modelId)">
            {{ bindingActions.runtimeStatusLabel("writer", form.targetBinding.datasourceId, form.targetBinding.modelId) }}
          </el-tag>
        </div>
        <HttpWebServiceOptionsEditor
          v-if="writerAdvancedFields.length && bindingActions.isHttpSoapWriterTarget()"
          :key="targetOptionsEditorKey('soap')"
          :fields="writerAdvancedFields"
          :model-value="form.targetBinding.writerOptions ?? {}"
          :dynamic-function-fields="bindingActions.writerDynamicFunctionFields()"
          :soap-contract="bindingActions.writerSoapContract()"
          :soap-field-names="bindingActions.writerSoapFieldNames()"
          :soap-fields="bindingActions.writerSoapFields()"
          :body-section-visible="false"
          :body-form-visible="false"
          envelope-readonly
          soap-template-mode
          :disabled="streamingLocked"
          @update:model-value="bindingActions.updateTargetWriterOptions($event)"
        />
        <HttpRequestOptionsEditor
          v-else-if="writerAdvancedFields.length && bindingActions.isHttpWriterTarget()"
          :key="targetOptionsEditorKey('http')"
          :fields="writerAdvancedFields"
          :model-value="form.targetBinding.writerOptions ?? {}"
          :dynamic-function-fields="bindingActions.writerDynamicFunctionFields()"
          :disabled="streamingLocked"
          @update:model-value="bindingActions.updateTargetWriterOptions($event)"
        />
        <MetaFormRenderer
          v-else-if="writerAdvancedFields.length"
          :fields="writerAdvancedFields"
          :model-value="form.targetBinding.writerOptions ?? {}"
          :dynamic-function-fields="bindingActions.writerDynamicFunctionFields()"
          :disabled="streamingLocked"
          @update:model-value="bindingActions.updateTargetWriterOptions($event)"
        />
        <el-alert
          v-else-if="bindingActions.runtimeSchemaFor('writer', form.targetBinding.datasourceId, form.targetBinding.modelId) && !bindingActions.runtimeSchemaFor('writer', form.targetBinding.datasourceId, form.targetBinding.modelId)?.runtimeSupported"
          type="warning"
          :closable="false"
          show-icon
          :title="t('web.collectionTasks.runtimeUnsupported')"
        />
        <el-alert
          v-else-if="bindingActions.runtimeSchemaFor('writer', form.targetBinding.datasourceId, form.targetBinding.modelId)"
          type="info"
          :closable="false"
          show-icon
          :title="t('web.collectionTasks.runtimeSchemaMissing')"
        />
      </div>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type {
  CollectionTaskSourceBinding,
  CollectionTaskTargetBinding,
  CollectionTaskExecutionMode,
  CollectionTaskStreamingOptions,
  DataModelDatasourceOptionView,
  DataSourceOptionView,
  MetadataFieldDefinition,
  PluginRuntimeOptionSchemaView,
  RuntimeClusterView,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard, StudioTableShell } from "@studio/ui";
import HttpRequestOptionsEditor from "@web/components/HttpRequestOptionsEditor.vue";
import HttpWebServiceOptionsEditor from "@web/components/HttpWebServiceOptionsEditor.vue";

type RuntimeOptionRole = "reader" | "writer";

interface BindingSectionForm {
  name: string;
  runtimeClusterId: unknown;
  sourceBindings: CollectionTaskSourceBinding[];
  targetBinding: CollectionTaskTargetBinding;
  executionMode: CollectionTaskExecutionMode;
  streamingOptions: CollectionTaskStreamingOptions;
}

interface CollectionTaskBindingActions {
  appendSourceBinding: () => void;
  removeSourceBinding: (index: number) => void;
  handleSourceDatasourceChange: (row: CollectionTaskSourceBinding, value: string) => void | Promise<void>;
  handleSourceModelChange: (row: CollectionTaskSourceBinding, value: string) => void | Promise<void>;
  resolveModelsByDatasource: (datasourceId: unknown) => DataModelDatasourceOptionView[];
  searchModelsByDatasource: (datasourceId: unknown, keyword: string) => void | Promise<void>;
  handleModelDropdownVisible: (datasourceId: unknown, visible: boolean) => void;
  runtimeSchemaTitle: (role: RuntimeOptionRole, datasourceId: unknown, modelId?: unknown) => string;
  runtimeSchemaFor: (role: RuntimeOptionRole, datasourceId: unknown, modelId?: unknown) => PluginRuntimeOptionSchemaView | undefined;
  runtimeStatusType: (role: RuntimeOptionRole, datasourceId: unknown, modelId?: unknown) => string;
  runtimeStatusLabel: (role: RuntimeOptionRole, datasourceId: unknown, modelId?: unknown) => string;
  sourceIncrementalVisible: (source: CollectionTaskSourceBinding) => boolean;
  syncCurrentIncrementalCursor: (source: CollectionTaskSourceBinding) => void;
  sourceFieldOptions: (source: CollectionTaskSourceBinding) => string[];
  hasIncrementalCursor: (source: CollectionTaskSourceBinding) => boolean;
  formatIncrementalCursorValue: (value: unknown) => string;
  formatIncrementalCursorMeta: (value: unknown) => string;
  isIncrementalCursorResetting: (source: CollectionTaskSourceBinding) => boolean;
  resetIncrementalCursor: (source: CollectionTaskSourceBinding) => void | Promise<void>;
  readerAdvancedFields: (source: CollectionTaskSourceBinding) => MetadataFieldDefinition[];
  isHttpReaderSource: (source: CollectionTaskSourceBinding) => boolean;
  isHttpSoapReaderSource: (source: CollectionTaskSourceBinding) => boolean;
  readerDynamicFunctionFields: (source: CollectionTaskSourceBinding) => string[];
  readerSoapContract: (source: CollectionTaskSourceBinding) => Record<string, unknown>;
  readerSoapFieldNames: (source: CollectionTaskSourceBinding) => string[];
  updateSourceReaderOptions: (source: CollectionTaskSourceBinding, value: Record<string, unknown>) => void;
  markSourceReaderOptionDirty: (source: CollectionTaskSourceBinding, fieldKey: string) => void;
  handleTargetDatasourceChange: (value: string) => void | Promise<void>;
  handleTargetModelChange: (value: string) => void | Promise<void>;
  isHttpWriterTarget: () => boolean;
  isHttpSoapWriterTarget: () => boolean;
  writerDynamicFunctionFields: () => string[];
  writerSoapContract: () => Record<string, unknown>;
  writerSoapFieldNames: () => string[];
  writerSoapFields: () => Array<{ name: string; parentNode?: string }>;
  updateTargetWriterOptions: (value: Record<string, unknown>) => void;
}

const props = defineProps<{
  form: BindingSectionForm;
  taskId?: string;
  taskTypeLabel: string;
  collectionModeVisible: boolean;
  collectionMode: string;
  isFusionTask: boolean;
  datasources: DataSourceOptionView[];
  runtimeClusters: RuntimeClusterView[];
  runtimeClustersLoading: boolean;
  singleRuntimeCluster: boolean;
  runtimeClusterOptionLabel: (cluster: RuntimeClusterView) => string;
  runtimeClusterOptionDisabled: (cluster: RuntimeClusterView) => boolean;
  onRuntimeClusterChange: () => void | Promise<void>;
  writerAdvancedFields: MetadataFieldDefinition[];
  streamingLocked: boolean;
  bindingActions: CollectionTaskBindingActions;
}>();

const emit = defineEmits<{
  "update:collectionMode": [value: string];
}>();

const { t } = useI18n();

function sourceModelRemoteMethod(row: CollectionTaskSourceBinding) {
  return (keyword: string) => props.bindingActions.searchModelsByDatasource(row.datasourceId, keyword);
}

function sourceModelVisibleChange(row: CollectionTaskSourceBinding) {
  return (visible: boolean) => props.bindingActions.handleModelDropdownVisible(row.datasourceId, visible);
}

function targetModelRemoteMethod(keyword: string) {
  return props.bindingActions.searchModelsByDatasource(props.form.targetBinding.datasourceId, keyword);
}

function targetModelVisibleChange(visible: boolean) {
  props.bindingActions.handleModelDropdownVisible(props.form.targetBinding.datasourceId, visible);
}

function readerOptionsEditorKey(source: CollectionTaskSourceBinding, editorType: "http" | "soap") {
  return [
    "reader",
    editorType,
    String(source.datasourceId ?? ""),
    String(source.modelId ?? ""),
  ].join(":");
}

function targetOptionsEditorKey(editorType: "http" | "soap") {
  return [
    "writer",
    editorType,
    String(props.form.targetBinding.datasourceId ?? ""),
    String(props.form.targetBinding.modelId ?? ""),
  ].join(":");
}

const collectionModeModel = computed({
  get() {
    return props.collectionMode;
  },
  set(value: string) {
    emit("update:collectionMode", value);
  },
});
</script>

<style scoped>
p {
  margin: 0;
  color: var(--studio-text-soft);
}

.section-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.advanced-options {
  display: grid;
  gap: 14px;
  margin: 16px 0;
}

.runtime-option-block {
  display: grid;
  gap: 12px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  padding: 14px;
}

.runtime-option-block--target {
  margin-top: 16px;
}

.runtime-option-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.incremental-grid {
  margin-top: 4px;
}

.streaming-options-panel {
  display: grid;
  gap: 14px;
  margin: 16px 0;
  padding: 14px;
  border: 1px solid rgba(217, 119, 6, 0.28);
  border-radius: 8px;
  background: rgba(245, 158, 11, 0.06);
}

.streaming-options-grid {
  margin-top: 0;
}

.incremental-cursor {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  min-width: 0;
}

.incremental-cursor__body {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.incremental-cursor__value {
  max-width: 100%;
  overflow-wrap: anywhere;
  color: var(--studio-text);
  line-height: 1.4;
}

.incremental-cursor__value--empty {
  color: var(--studio-text-soft);
}

.incremental-cursor__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.3;
}

@media (max-width: 640px) {
  .section-toolbar,
  .runtime-option-header,
  .incremental-cursor {
    align-items: stretch;
    flex-direction: column;
  }

  .section-toolbar .el-button,
  .incremental-cursor .el-button {
    width: 100%;
  }
}
</style>
