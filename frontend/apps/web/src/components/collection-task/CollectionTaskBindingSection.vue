<template>
  <SectionCard :title="t('web.collectionTasks.bindingTitle')" :description="t('web.collectionTasks.bindingDescription')">
    <div class="studio-form-grid">
      <el-form-item :label="t('web.collectionTasks.name')">
        <el-input v-model="form.name" :placeholder="t('web.collectionTasks.namePlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('web.collectionTasks.type')">
        <el-input :model-value="taskTypeLabel" disabled />
      </el-form-item>
      <el-form-item v-if="collectionModeVisible" :label="t('web.collectionTasks.collectionMode')">
        <el-radio-group v-model="collectionModeModel">
          <el-radio-button value="FULL">{{ t("web.collectionTasks.collectionModeFull") }}</el-radio-button>
          <el-radio-button value="INCREMENTAL">{{ t("web.collectionTasks.collectionModeIncremental") }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </div>

    <div class="soft-panel">
      <div class="section-toolbar">
        <div>
          <strong>{{ t("web.collectionTasks.sourcesTitle") }}</strong>
          <p>{{ isFusionTask ? t("web.collectionTasks.sourcesDescription") : t("web.collectionTasks.singleSourceDescription") }}</p>
        </div>
        <el-button type="primary" plain @click="bindingActions.appendSourceBinding">{{ t("common.addRow") }}</el-button>
      </div>

      <el-table :data="form.sourceBindings" border>
        <el-table-column v-if="isFusionTask" :label="t('web.collectionTasks.sourceAlias')" min-width="150">
          <template #default="{ row }">
            <el-input v-model="row.sourceAlias" :placeholder="t('web.collectionTasks.sourceAliasPlaceholder')" />
          </template>
        </el-table-column>

        <el-table-column :label="t('web.collectionTasks.datasource')" min-width="220">
          <template #default="{ row }">
            <el-select
              :model-value="String(row.datasourceId ?? '')"
              filterable
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
              :placeholder="t('web.collectionTasks.modelPlaceholder')"
              @update:model-value="bindingActions.handleSourceModelChange(row, $event)"
            >
              <el-option
                v-for="model in bindingActions.resolveModelsByDatasource(row.datasourceId)"
                :key="model.id"
                :label="model.name"
                :value="String(model.id)"
              />
            </el-select>
          </template>
        </el-table-column>

        <el-table-column :label="t('fieldMapping.actions')" width="110" fixed="right">
          <template #default="{ $index }">
            <el-button link type="danger" :disabled="form.sourceBindings.length === 1" @click="bindingActions.removeSourceBinding($index)">
              {{ t("common.remove") }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
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
              :placeholder="t('web.collectionTasks.incrementalColumn')"
              @change="bindingActions.syncCurrentIncrementalCursor(source)"
            >
              <el-option v-for="field in bindingActions.sourceFieldOptions(source)" :key="field" :label="field" :value="field" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('web.collectionTasks.incrementalModel')">
            <el-select v-model="source.incremental.incrModel" @change="bindingActions.syncCurrentIncrementalCursor(source)">
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
                :disabled="!taskId || !bindingActions.hasIncrementalCursor(source)"
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
          :fields="bindingActions.readerAdvancedFields(source)"
          :model-value="source.readerOptions ?? {}"
          :dynamic-function-fields="bindingActions.readerDynamicFunctionFields(source)"
          :soap-contract="bindingActions.readerSoapContract(source)"
          :soap-field-names="bindingActions.readerSoapFieldNames(source)"
          @update:model-value="bindingActions.updateSourceReaderOptions(source, $event)"
        />
        <HttpRequestOptionsEditor
          v-else-if="bindingActions.readerAdvancedFields(source).length && bindingActions.isHttpReaderSource(source)"
          :fields="bindingActions.readerAdvancedFields(source)"
          :model-value="source.readerOptions ?? {}"
          :dynamic-function-fields="bindingActions.readerDynamicFunctionFields(source)"
          @update:model-value="bindingActions.updateSourceReaderOptions(source, $event)"
        />
        <MetaFormRenderer
          v-else-if="bindingActions.readerAdvancedFields(source).length"
          :fields="bindingActions.readerAdvancedFields(source)"
          :model-value="source.readerOptions ?? {}"
          :dynamic-function-fields="bindingActions.readerDynamicFunctionFields(source)"
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
            :placeholder="t('web.collectionTasks.modelPlaceholder')"
            @update:model-value="bindingActions.handleTargetModelChange"
          >
            <el-option
              v-for="model in bindingActions.resolveModelsByDatasource(form.targetBinding.datasourceId)"
              :key="model.id"
              :label="model.name"
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
          @update:model-value="bindingActions.updateTargetWriterOptions($event)"
        />
        <HttpRequestOptionsEditor
          v-else-if="writerAdvancedFields.length && bindingActions.isHttpWriterTarget()"
          :fields="writerAdvancedFields"
          :model-value="form.targetBinding.writerOptions ?? {}"
          :dynamic-function-fields="bindingActions.writerDynamicFunctionFields()"
          @update:model-value="bindingActions.updateTargetWriterOptions($event)"
        />
        <MetaFormRenderer
          v-else-if="writerAdvancedFields.length"
          :fields="writerAdvancedFields"
          :model-value="form.targetBinding.writerOptions ?? {}"
          :dynamic-function-fields="bindingActions.writerDynamicFunctionFields()"
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
  DataModelListView,
  DataSourceOptionView,
  MetadataFieldDefinition,
  PluginRuntimeOptionSchemaView,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard } from "@studio/ui";
import HttpRequestOptionsEditor from "@web/components/HttpRequestOptionsEditor.vue";
import HttpWebServiceOptionsEditor from "@web/components/HttpWebServiceOptionsEditor.vue";

type RuntimeOptionRole = "reader" | "writer";

interface BindingSectionForm {
  name: string;
  sourceBindings: CollectionTaskSourceBinding[];
  targetBinding: CollectionTaskTargetBinding;
}

interface CollectionTaskBindingActions {
  appendSourceBinding: () => void;
  removeSourceBinding: (index: number) => void;
  handleSourceDatasourceChange: (row: CollectionTaskSourceBinding, value: string) => void | Promise<void>;
  handleSourceModelChange: (row: CollectionTaskSourceBinding, value: string) => void | Promise<void>;
  resolveModelsByDatasource: (datasourceId: unknown) => DataModelListView[];
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
  writerAdvancedFields: MetadataFieldDefinition[];
  bindingActions: CollectionTaskBindingActions;
}>();

const emit = defineEmits<{
  "update:collectionMode": [value: string];
}>();

const { t } = useI18n();

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
</style>
