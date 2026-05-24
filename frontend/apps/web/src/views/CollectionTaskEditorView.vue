<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ taskId ? t("web.collectionTasks.editTitle") : t("web.collectionTasks.createTitle") }}</h3>
        <p>{{ t("web.collectionTasks.editorDescription") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/collection-tasks')">{{ t("common.backToList") }}</el-button>
        <el-button plain @click="loadReferenceData">{{ t("common.refresh") }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveTask">{{ t("common.saveDraft") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.collectionTasks.stepsTitle')" :description="t('web.collectionTasks.stepsDescription')">
      <el-steps :active="activeStep - 1" finish-status="success">
        <el-step :title="t('web.collectionTasks.step1Title')" />
        <el-step :title="t('web.collectionTasks.step2Title')" />
        <el-step :title="t('web.collectionTasks.step3Title')" />
        <el-step :title="t('web.collectionTasks.step4Title')" />
      </el-steps>
    </SectionCard>

    <SectionCard v-if="activeStep === 1" :title="t('web.collectionTasks.bindingTitle')" :description="t('web.collectionTasks.bindingDescription')">
      <div class="studio-form-grid">
        <el-form-item :label="t('web.collectionTasks.name')">
          <el-input v-model="form.name" :placeholder="t('web.collectionTasks.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.type')">
          <el-input :model-value="taskTypeLabel" disabled />
        </el-form-item>
        <el-form-item v-if="collectionModeVisible" :label="t('web.collectionTasks.collectionMode')">
          <el-radio-group v-model="collectionMode">
            <el-radio-button label="FULL">{{ t("web.collectionTasks.collectionModeFull") }}</el-radio-button>
            <el-radio-button label="INCREMENTAL">{{ t("web.collectionTasks.collectionModeIncremental") }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </div>

      <div class="soft-panel">
        <div class="section-toolbar">
          <div>
            <strong>{{ t("web.collectionTasks.sourcesTitle") }}</strong>
            <p>{{ isFusionTask ? t("web.collectionTasks.sourcesDescription") : t("web.collectionTasks.singleSourceDescription") }}</p>
          </div>
          <el-button type="primary" plain @click="appendSourceBinding">{{ t("common.addRow") }}</el-button>
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
                @update:model-value="handleSourceDatasourceChange(row, $event)"
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
                @update:model-value="handleSourceModelChange(row, $event)"
              >
                <el-option
                  v-for="model in resolveModelsByDatasource(row.datasourceId)"
                  :key="model.id"
                  :label="model.name"
                  :value="String(model.id)"
                />
              </el-select>
            </template>
          </el-table-column>

            <el-table-column :label="t('fieldMapping.actions')" width="110" fixed="right">
            <template #default="{ $index }">
              <el-button link type="danger" :disabled="form.sourceBindings.length === 1" @click="removeSourceBinding($index)">{{ t("common.remove") }}</el-button>
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
              <p>{{ runtimeSchemaTitle("reader", source.datasourceId) }}</p>
            </div>
            <el-tag v-if="runtimeSchemaFor('reader', source.datasourceId)" :type="runtimeStatusType('reader', source.datasourceId)">
              {{ runtimeStatusLabel("reader", source.datasourceId) }}
            </el-tag>
          </div>

          <div v-if="source.incremental && sourceIncrementalVisible(source)" class="studio-form-grid incremental-grid">
            <el-form-item :label="t('web.collectionTasks.incrementalColumn')">
                  <el-select
                    v-model="source.incremental.incrColumn"
                    filterable
                    clearable
                    :placeholder="t('web.collectionTasks.incrementalColumn')"
                    @change="syncCurrentIncrementalCursor(source)"
                  >
                <el-option v-for="field in sourceFieldOptions(source)" :key="field" :label="field" :value="field" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('web.collectionTasks.incrementalModel')">
                  <el-select v-model="source.incremental.incrModel" @change="syncCurrentIncrementalCursor(source)">
                <el-option label=">" value=">" />
                <el-option label=">=" value=">=" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('web.collectionTasks.incrementalValue')">
              <div class="incremental-cursor">
                <div class="incremental-cursor__body">
                  <div
                    class="studio-mono incremental-cursor__value"
                    :class="{ 'incremental-cursor__value--empty': !hasIncrementalCursor(source) }"
                  >
                    {{ formatIncrementalCursorValue(source.incremental.pkValue) }}
                  </div>
                  <div class="incremental-cursor__meta">
                    <span>{{ t("web.collectionTasks.incrementalCursorLastRun") }}: {{ formatIncrementalCursorMeta(source.incremental.lastRunRecordId) }}</span>
                    <span>{{ t("web.collectionTasks.incrementalCursorUpdatedAt") }}: {{ formatIncrementalCursorMeta(source.incremental.lastUpdatedAt) }}</span>
                  </div>
                </div>
                <el-button
                  plain
                  type="warning"
                  size="small"
                  :disabled="!taskId || !hasIncrementalCursor(source)"
                  :loading="isIncrementalCursorResetting(source)"
                  @click="resetIncrementalCursor(source)"
                >
                  {{ t("web.collectionTasks.resetIncrementalCursor") }}
                </el-button>
              </div>
            </el-form-item>
          </div>

          <HttpReaderOptionsEditor
            v-if="readerAdvancedFields(source).length && isHttpReaderSource(source)"
            :fields="readerAdvancedFields(source)"
            :model-value="source.readerOptions ?? {}"
            :dynamic-function-fields="readerDynamicFunctionFields(source)"
            @update:model-value="updateSourceReaderOptions(source, $event)"
          />
          <MetaFormRenderer
            v-else-if="readerAdvancedFields(source).length"
            :fields="readerAdvancedFields(source)"
            :model-value="source.readerOptions ?? {}"
            :dynamic-function-fields="readerDynamicFunctionFields(source)"
            @update:model-value="updateSourceReaderOptions(source, $event)"
          />
          <el-alert
            v-else-if="runtimeSchemaFor('reader', source.datasourceId) && !runtimeSchemaFor('reader', source.datasourceId)?.runtimeSupported"
            type="warning"
            :closable="false"
            show-icon
            :title="t('web.collectionTasks.runtimeUnsupported')"
          />
          <el-alert
            v-else-if="runtimeSchemaFor('reader', source.datasourceId)"
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
              @update:model-value="handleTargetDatasourceChange"
            >
              <el-option v-for="datasource in datasources" :key="datasource.id" :label="datasource.name" :value="String(datasource.id)" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('web.collectionTasks.model')">
            <el-select
              :model-value="String(form.targetBinding.modelId ?? '')"
              filterable
              :placeholder="t('web.collectionTasks.modelPlaceholder')"
              @update:model-value="handleTargetModelChange"
            >
              <el-option
                v-for="model in resolveModelsByDatasource(form.targetBinding.datasourceId)"
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
              <p>{{ runtimeSchemaTitle("writer", form.targetBinding.datasourceId) }}</p>
            </div>
            <el-tag v-if="runtimeSchemaFor('writer', form.targetBinding.datasourceId)" :type="runtimeStatusType('writer', form.targetBinding.datasourceId)">
              {{ runtimeStatusLabel("writer", form.targetBinding.datasourceId) }}
            </el-tag>
          </div>
          <HttpReaderOptionsEditor
            v-if="writerAdvancedFields.length && isHttpWriterTarget()"
            :fields="writerAdvancedFields"
            :model-value="form.targetBinding.writerOptions ?? {}"
            :dynamic-function-fields="writerDynamicFunctionFields()"
            @update:model-value="form.targetBinding.writerOptions = $event"
          />
          <MetaFormRenderer
            v-else-if="writerAdvancedFields.length"
            :fields="writerAdvancedFields"
            :model-value="form.targetBinding.writerOptions ?? {}"
            :dynamic-function-fields="writerDynamicFunctionFields()"
            @update:model-value="form.targetBinding.writerOptions = $event"
          />
          <el-alert
            v-else-if="runtimeSchemaFor('writer', form.targetBinding.datasourceId) && !runtimeSchemaFor('writer', form.targetBinding.datasourceId)?.runtimeSupported"
            type="warning"
            :closable="false"
            show-icon
            :title="t('web.collectionTasks.runtimeUnsupported')"
          />
          <el-alert
            v-else-if="runtimeSchemaFor('writer', form.targetBinding.datasourceId)"
            type="info"
            :closable="false"
            show-icon
            :title="t('web.collectionTasks.runtimeSchemaMissing')"
          />
        </div>
      </div>
    </SectionCard>

    <SectionCard v-else-if="activeStep === 2" :title="t('web.collectionTasks.mappingTitle')" :description="t('web.collectionTasks.mappingDescription')">
      <div class="section-toolbar">
        <div>
          <strong>{{ t("web.collectionTasks.mappingToolbarTitle") }}</strong>
          <p>{{ t("web.collectionTasks.mappingToolbarDescription") }}</p>
        </div>
        <el-button type="primary" plain @click="initializeMappings">{{ t("web.collectionTasks.initializeMappings") }}</el-button>
      </div>

      <div v-if="isFusionTask" class="studio-form-grid fusion-options">
        <el-form-item :label="t('web.collectionTasks.joinKeys')">
          <el-select v-model="joinKeys" multiple filterable :placeholder="t('web.collectionTasks.joinKeysPlaceholder')">
            <el-option v-for="field in commonJoinKeyOptions" :key="field" :label="field" :value="field" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.joinType')">
          <el-select v-model="joinType">
            <el-option label="LEFT" value="LEFT" />
            <el-option label="INNER" value="INNER" />
            <el-option label="RIGHT" value="RIGHT" />
          </el-select>
        </el-form-item>
      </div>

      <div v-if="isFusionTask" class="runtime-option-block runtime-option-block--fusion">
        <div class="runtime-option-header">
          <div>
            <strong>{{ t("web.collectionTasks.fusionReaderAdvancedOptions") }}</strong>
            <p>{{ runtimeSchemaTitleByType("reader", "fusion") }}</p>
          </div>
          <el-tag v-if="runtimeSchemaForType('reader', 'fusion')" :type="runtimeStatusTypeByType('reader', 'fusion')">
            {{ runtimeStatusLabelByType("reader", "fusion") }}
          </el-tag>
        </div>
        <MetaFormRenderer
          v-if="fusionReaderAdvancedFields.length"
          :fields="fusionReaderAdvancedFields"
          :model-value="fusionReaderOptions"
          @update:model-value="updateFusionReaderOptions"
        />
        <el-alert
          v-else-if="runtimeSchemaForType('reader', 'fusion') && !runtimeSchemaForType('reader', 'fusion')?.runtimeSupported"
          type="warning"
          :closable="false"
          show-icon
          :title="t('web.collectionTasks.runtimeUnsupported')"
        />
        <el-alert
          v-else-if="runtimeSchemaForType('reader', 'fusion')"
          type="info"
          :closable="false"
          show-icon
          :title="t('web.collectionTasks.runtimeSchemaMissing')"
        />
      </div>

      <CollectionTaskFieldMappingEditor
        :model-value="form.fieldMappings"
        :source-aliases="sourceAliasOptions"
        :source-field-options-by-alias="sourceFieldOptionsByAlias"
        :target-fields="targetFieldOptions"
        :rule-options="fieldMappingRules"
        :show-source-alias="isFusionTask"
        :show-expression="isFusionTask"
        @update:model-value="form.fieldMappings = $event"
      />
    </SectionCard>

    <SectionCard v-else-if="activeStep === 3" :title="t('web.collectionTasks.scheduleTitle')" :description="t('web.collectionTasks.scheduleDescription')">
      <div class="studio-form-grid">
        <el-form-item :label="t('web.collectionTasks.scheduleEnabled')">
          <el-switch v-model="form.schedule.enabled" inline-prompt :active-text="t('common.on')" :inactive-text="t('common.off')" />
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.cronExpression')" class="cron-form-item">
          <CronExpressionPicker
            v-model="form.schedule.cronExpression"
            :label="t('web.collectionTasks.cronExpression')"
          />
        </el-form-item>
        <el-form-item :label="t('web.collectionTasks.timezone')">
          <el-input v-model="form.schedule.timezone" placeholder="Asia/Shanghai" />
        </el-form-item>
      </div>
    </SectionCard>

    <SectionCard v-else :title="t('web.collectionTasks.reviewTitle')" :description="t('web.collectionTasks.reviewDescription')">
      <div class="review-grid">
        <div class="soft-panel">
          <strong>{{ form.name || t("common.none") }}</strong>
          <p>{{ taskTypeLabel }}</p>
          <p>{{ t("web.collectionTasks.sourceCount") }}: {{ form.sourceBindings.length }}</p>
        </div>
        <div class="soft-panel">
          <strong>{{ t("web.collectionTasks.mappingTitle") }}</strong>
          <p>{{ t("common.fields", { count: form.fieldMappings.length }) }}</p>
          <p>{{ form.schedule.enabled ? form.schedule.cronExpression || t("common.on") : t("common.off") }}</p>
        </div>
      </div>

      <div class="soft-panel preview-panel">
        <div class="section-toolbar">
          <div>
            <strong>{{ t("web.collectionTasks.previewTitle") }}</strong>
            <p>{{ t("web.collectionTasks.previewDescription") }}</p>
          </div>
          <el-button plain :loading="previewLoading" :disabled="!canPreviewConfig" @click="loadPreviewConfig">
            {{ t("web.collectionTasks.refreshPreview") }}
          </el-button>
        </div>

        <pre v-if="previewConfig" class="json-block studio-mono preview-json">{{ prettyJson(previewConfig) }}</pre>
        <div v-else class="soft-panel">
          {{ t("web.collectionTasks.previewEmpty") }}
        </div>
      </div>
    </SectionCard>

    <div class="editor-footer">
      <el-button :disabled="activeStep === 1" @click="activeStep -= 1">{{ t("web.collectionTasks.previousStep") }}</el-button>
      <el-button v-if="activeStep < 4" type="primary" @click="activeStep += 1">{{ t("web.collectionTasks.nextStep") }}</el-button>
      <el-button v-else type="primary" :loading="saving" @click="saveTask">{{ t("common.saveDraft") }}</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  CollectionIncrementalDefinition,
  CollectionTaskDefinitionView,
  JobContainerConfig,
  CollectionTaskSaveRequest,
  CollectionTaskSourceBinding,
  CollectionTaskTargetBinding,
  DataModelDefinition,
  DataSourceDefinition,
  FieldMappingRuleView,
  MetadataFieldDefinition,
  PluginRuntimeOptionSchemaView,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import CollectionTaskFieldMappingEditor from "@web/components/CollectionTaskFieldMappingEditor.vue";
import CronExpressionPicker from "@web/components/CronExpressionPicker.vue";
import HttpReaderOptionsEditor from "@web/components/HttpReaderOptionsEditor.vue";
import { cloneDeep, prettyJson } from "@/utils/studio";

interface CollectionTaskEditorForm extends Omit<CollectionTaskSaveRequest, "schedule"> {
  schedule: NonNullable<CollectionTaskSaveRequest["schedule"]>;
}

type RuntimeOptionRole = "reader" | "writer";

const fileReaderDatasourceTypes = new Set(["ftp", "sftp", "minio"]);
const fileReaderDynamicFunctionFields = ["rootPath", "partition"];
const httpReaderDynamicFunctionFields = ["header", "params", "requestBody"];
const httpWriterDynamicFunctionFields = ["header", "params", "requestBody"];
const fileWriterDatasourceTypes = new Set(["ftp", "sftp", "minio"]);
const fileWriterDynamicFunctionFields = ["rootPath", "fileName", "efile.dataTime", "efile.planDate"];

const { t } = useI18n();
const route = useRoute();
const router = useRouter();

const taskId = computed(() => route.params.taskId as string | undefined);
const activeStep = ref(1);
const datasources = ref<DataSourceDefinition[]>([]);
const fieldMappingRules = ref<FieldMappingRuleView[]>([]);
const modelCache = ref<Record<string, DataModelDefinition[]>>({});
const runtimeSchemaCache = ref<Record<string, PluginRuntimeOptionSchemaView>>({});
const runtimeSchemaLoading = ref<Record<string, boolean>>({});
const saving = ref(false);
const previewLoading = ref(false);
const previewDirty = ref(true);
const previewConfig = ref<JobContainerConfig | null>(null);
const resettingIncrementalCursor = ref<Record<string, boolean>>({});
const customSqlFieldCache = ref<Record<string, {
  datasourceId: string;
  sql: string;
  fields: string[];
  loading: boolean;
  error?: string;
}>>({});
const customSqlResolveTimers = new Map<string, number>();

const form = reactive<CollectionTaskEditorForm>({
  name: "",
  sourceBindings: [
    {
      sourceAlias: "src1",
      datasourceId: "",
      modelId: "",
      readerOptions: {},
      incremental: {
        enabled: false,
        incrModel: ">",
      },
    },
  ],
  targetBinding: {
    datasourceId: "",
    modelId: "",
    writerOptions: {},
  },
  fieldMappings: [],
  executionOptions: {
    collectionMode: "FULL",
    joinKeys: [],
    joinType: "LEFT",
  },
  schedule: {
    enabled: false,
    cronExpression: "0 */30 * * * ?",
    timezone: "Asia/Shanghai",
  },
});

const isFusionTask = computed(() => form.sourceBindings.length > 1);
const taskTypeLabel = computed(() => (isFusionTask.value ? t("web.collectionTasks.typeFusion") : t("web.collectionTasks.typeSingle")));
const sourceAliasOptions = computed(() => form.sourceBindings.map((item) => item.sourceAlias).filter(Boolean));
const targetModel = computed(() => resolveModelById(form.targetBinding.modelId));
const targetFieldOptions = computed(() => resolveFieldsByModel(targetModel.value));
const targetPrimaryKeyFields = computed(() => resolvePrimaryKeyFieldsByModel(targetModel.value));
const canPreviewConfig = computed(() =>
  form.sourceBindings.length > 0
  && form.sourceBindings.every((item) =>
    Boolean(item.datasourceId)
    && Boolean(item.modelId)
    && (!isFusionTask.value || Boolean(item.sourceAlias?.trim())))
  && Boolean(form.targetBinding.datasourceId)
  && Boolean(form.targetBinding.modelId),
);
const sourceFieldOptionsByAlias = computed<Record<string, string[]>>(() => {
  const options: Record<string, string[]> = {};
  for (const source of form.sourceBindings) {
    if (source.sourceAlias) {
      options[source.sourceAlias] = sourceFieldOptions(source);
    }
  }
  return options;
});
const commonJoinKeyOptions = computed(() => {
  const sourceFields = form.sourceBindings
    .map((item) => sourceFieldOptions(item))
    .filter((item) => item.length > 0);
  if (!sourceFields.length) {
    return [];
  }
  return sourceFields.reduce((result, current) => result.filter((field) => current.includes(field)));
});
const collectionModeVisible = computed(() => form.sourceBindings.some((source) => sourceIncrementalSupported(source)));

const collectionMode = computed<string>({
  get() {
    return String(form.executionOptions.collectionMode ?? "FULL");
  },
  set(value) {
    form.executionOptions = {
      ...form.executionOptions,
      collectionMode: value,
    };
    syncIncrementalEnabled(value);
  },
});

const joinKeys = computed<string[]>({
  get() {
    const value = form.executionOptions.joinKeys;
    return Array.isArray(value) ? (value as string[]) : [];
  },
  set(value) {
    form.executionOptions = {
      ...form.executionOptions,
      joinKeys: value,
    };
  },
});

const joinType = computed<string>({
  get() {
    return String(form.executionOptions.joinType ?? "LEFT");
  },
  set(value) {
    form.executionOptions = {
      ...form.executionOptions,
      joinType: value,
    };
  },
});

const writerAdvancedFields = computed<MetadataFieldDefinition[]>(() =>
  (runtimeSchemaFor("writer", form.targetBinding.datasourceId)?.fields ?? []).map(enhanceWriterAdvancedField),
);

const fusionReaderAdvancedFields = computed<MetadataFieldDefinition[]>(() =>
  runtimeSchemaForType("reader", "fusion")?.fields ?? [],
);

const fusionReaderOptions = computed<Record<string, unknown>>(() => {
  const value = form.executionOptions.fusionReaderOptions;
  return isPlainRecord(value) ? value : {};
});

async function loadReferenceData() {
  try {
    const [datasourceData, fieldMappingRuleData] = await Promise.all([
      studioApi.datasources.list(),
      studioApi.fieldMappingRules.options(),
    ]);
    datasources.value = datasourceData;
    fieldMappingRules.value = fieldMappingRuleData;
    await Promise.all(form.sourceBindings.map((item) => ensureModels(item.datasourceId)));
    await ensureModels(form.targetBinding.datasourceId);
    await ensureRuntimeSchemas();
    await resolveCustomSqlFieldsForActiveStep();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.loadFailed"));
  }
}

async function loadTask() {
  if (!taskId.value) {
    return;
  }
  try {
    const task = await studioApi.collectionTasks.get(taskId.value);
    applyTask(task);
    await Promise.all(form.sourceBindings.map((item) => ensureModels(item.datasourceId)));
    await ensureModels(form.targetBinding.datasourceId);
    await ensureRuntimeSchemas();
    await resolveCustomSqlFieldsForActiveStep();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.loadFailed"));
  }
}

function applyTask(task: CollectionTaskDefinitionView) {
  const executionOptions = cloneDeep(task.executionOptions ?? {});
  const inferredCollectionMode = inferCollectionMode(task, executionOptions);
  const targetBinding = normalizeTargetBinding(cloneDeep(task.targetBinding ?? { datasourceId: "", modelId: "" }));
  migrateLegacyWriteMode(targetBinding, executionOptions);
  form.id = task.id;
  form.name = task.name;
  form.executionOptions = {
    collectionMode: inferredCollectionMode,
    joinKeys: [],
    joinType: "LEFT",
    ...executionOptions,
  };
  form.sourceBindings = cloneDeep(task.sourceBindings ?? []).map((item, index) =>
    normalizeSourceBinding(item, collectionMode.value, `src${index + 1}`));
  form.targetBinding = targetBinding;
  form.fieldMappings = cloneDeep(task.fieldMappings ?? []);
  form.schedule = cloneDeep(task.schedule ?? { enabled: false, cronExpression: "0 */30 * * * ?", timezone: "Asia/Shanghai" });
}

function appendSourceBinding() {
  form.sourceBindings.push(normalizeSourceBinding({
    sourceAlias: `src${form.sourceBindings.length + 1}`,
    datasourceId: "",
    modelId: "",
  }, collectionMode.value, `src${form.sourceBindings.length + 1}`));
}

function removeSourceBinding(index: number) {
  form.sourceBindings.splice(index, 1);
}

async function ensureModels(datasourceId: unknown) {
  const key = String(datasourceId ?? "");
  if (!key || key === "undefined" || key === "null" || modelCache.value[key]) {
    return;
  }
  modelCache.value[key] = await studioApi.models.listByDatasource(key);
}

function resolveModelsByDatasource(datasourceId: unknown) {
  return modelCache.value[String(datasourceId ?? "")] ?? [];
}

function resolveModelById(modelId: unknown) {
  if (!modelId) {
    return undefined;
  }
  const allModels: DataModelDefinition[] = [];
  Object.values(modelCache.value).forEach((items) => {
    allModels.push(...items);
  });
  return allModels.find((item) => String(item.id) === String(modelId));
}

function resolveFieldsByModelId(modelId: unknown) {
  return resolveFieldsByModel(resolveModelById(modelId));
}

function sourceFieldOptions(source: CollectionTaskSourceBinding) {
  const customFields = resolveCustomSqlCachedFields(source);
  return customFields.length ? customFields : resolveFieldsByModelId(source.modelId);
}

function resolveCustomSqlCachedFields(source: CollectionTaskSourceBinding) {
  const cacheKey = customSqlFieldCacheKey(source);
  if (!cacheKey) {
    return [];
  }
  const cache = customSqlFieldCache.value[cacheKey];
  return cache?.fields ?? [];
}

function resolveFieldsByModel(model: DataModelDefinition | undefined) {
  const columns = model?.technicalMetadata?.columns;
  if (!Array.isArray(columns)) {
    return [];
  }
  return columns
    .map((item) => (typeof item === "object" && item ? String((item as Record<string, unknown>).name ?? "") : ""))
    .filter(Boolean);
}

function resolvePrimaryKeyFieldsByModel(model: DataModelDefinition | undefined) {
  const columns = model?.technicalMetadata?.columns;
  if (!Array.isArray(columns)) {
    return [];
  }
  return columns
    .filter((item) => typeof item === "object" && item && isTruthyMetadataFlag((item as Record<string, unknown>).primaryKey))
    .map((item) => String((item as Record<string, unknown>).name ?? ""))
    .filter(Boolean);
}

function isTruthyMetadataFlag(value: unknown) {
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value === "number") {
    return value === 1;
  }
  return ["true", "1", "yes", "y"].includes(String(value ?? "").trim().toLowerCase());
}

function inferCollectionMode(task: CollectionTaskDefinitionView, executionOptions: Record<string, unknown>) {
  const explicitMode = String(executionOptions.collectionMode ?? "").trim();
  if (explicitMode) {
    return explicitMode;
  }
  return (task.sourceBindings ?? []).some((item) => item.incremental?.enabled) ? "INCREMENTAL" : "FULL";
}

function normalizeSourceBinding(binding: CollectionTaskSourceBinding, mode: string, defaultAlias = "src1"): CollectionTaskSourceBinding {
  return {
    ...binding,
    sourceAlias: binding.sourceAlias?.trim() || defaultAlias,
    readerOptions: { ...(binding.readerOptions ?? {}) },
    incremental: normalizeIncremental(binding.incremental, mode),
  };
}

function normalizeTargetBinding(binding: CollectionTaskTargetBinding): CollectionTaskTargetBinding {
  const writerOptions = { ...(binding.writerOptions ?? {}) };
  if (Array.isArray(writerOptions.pkColumn)) {
    const pkColumn = writerOptions.pkColumn.map((item) => String(item).trim()).filter(Boolean);
    if (pkColumn.length) {
      writerOptions.pkColumn = pkColumn;
    } else {
      delete writerOptions.pkColumn;
    }
  } else if (writerOptions.pkColumn !== undefined && writerOptions.pkColumn !== null) {
    delete writerOptions.pkColumn;
  }
  return {
    ...binding,
    writerOptions,
  };
}

function migrateLegacyWriteMode(targetBinding: CollectionTaskTargetBinding, executionOptions: Record<string, unknown>) {
  const legacyWriteMode = executionOptions.writeMode;
  delete executionOptions.writeMode;
  if (legacyWriteMode == null || String(legacyWriteMode).trim() === "") {
    return;
  }
  const writerOptions = { ...(targetBinding.writerOptions ?? {}) };
  if (writerOptions.writeMode == null || String(writerOptions.writeMode).trim() === "") {
    writerOptions.writeMode = legacyWriteMode;
  }
  targetBinding.writerOptions = writerOptions;
}

function normalizeIncremental(incremental: CollectionIncrementalDefinition | undefined, mode: string): CollectionIncrementalDefinition {
  return {
    incrModel: ">",
    ...(incremental ?? {}),
    enabled: mode === "INCREMENTAL",
  };
}

function syncIncrementalEnabled(mode: string) {
  form.sourceBindings.forEach((source) => {
    source.incremental = normalizeIncremental(source.incremental, sourceIncrementalSupported(source) ? mode : "FULL");
  });
}

function buildRequestPayload(): CollectionTaskSaveRequest {
  const payload = cloneDeep(form) as CollectionTaskSaveRequest;
  const mode = String(payload.executionOptions.collectionMode ?? "FULL");
  delete payload.executionOptions.writeMode;
  if (payload.sourceBindings.length <= 1) {
    delete payload.executionOptions.fusionReaderOptions;
  }
  payload.sourceBindings = payload.sourceBindings.map((source, index) => {
    const normalized = normalizeSourceBinding(source, mode, `src${index + 1}`);
    if (!sourceIncrementalSupported(normalized)) {
      normalized.incremental = normalizeIncremental(normalized.incremental, "FULL");
    }
    return normalized;
  });
  payload.sourceBindings.forEach((source) => {
    if (source.incremental) {
      stripSystemIncrementalCursor(source.incremental);
    }
  });
  payload.targetBinding = normalizeTargetBinding(payload.targetBinding);
  return payload;
}

function stripSystemIncrementalCursor(incremental: CollectionIncrementalDefinition) {
  delete incremental.pkValue;
  delete incremental.valueType;
  delete incremental.lastRunRecordId;
  delete incremental.lastUpdatedAt;
  delete incremental.cursorStates;
}

function syncCurrentIncrementalCursor(source: CollectionTaskSourceBinding) {
  const incremental = source.incremental;
  if (!incremental) {
    return;
  }
  const cursorState = findIncrementalCursorState(incremental);
  if (!cursorState) {
    delete incremental.pkValue;
    delete incremental.valueType;
    delete incremental.lastRunRecordId;
    delete incremental.lastUpdatedAt;
    return;
  }
  incremental.pkValue = cursorState.pkValue;
  incremental.valueType = cursorState.valueType;
  incremental.lastRunRecordId = cursorState.lastRunRecordId;
  incremental.lastUpdatedAt = cursorState.lastUpdatedAt;
}

function findIncrementalCursorState(incremental: CollectionIncrementalDefinition) {
  const incrColumn = normalizeCursorText(incremental.incrColumn);
  if (!incrColumn) {
    return undefined;
  }
  const incrModel = normalizeIncrModel(incremental.incrModel);
  return incremental.cursorStates?.find((state) => (
    normalizeCursorText(state.incrColumn) === incrColumn
    && normalizeIncrModel(state.incrModel) === incrModel
  ));
}

function normalizeCursorText(value: unknown) {
  return String(value ?? "").trim();
}

function normalizeIncrModel(value: unknown) {
  const text = normalizeCursorText(value);
  return text || ">";
}

async function ensureRuntimeSchemas() {
  const jobs = [
    ...form.sourceBindings.map((source) => ensureRuntimeSchemaForDatasource("reader", source.datasourceId)),
    ensureRuntimeSchemaForDatasource("writer", form.targetBinding.datasourceId),
  ];
  if (isFusionTask.value) {
    jobs.push(ensureRuntimeSchemaForType("reader", "fusion"));
  }
  await Promise.all(jobs);
  applyRuntimeDefaults();
}

async function ensureRuntimeSchemaForDatasource(role: RuntimeOptionRole, datasourceId: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return;
  }
  await ensureRuntimeSchemaForType(role, datasourceType);
}

async function ensureRuntimeSchemaForType(role: RuntimeOptionRole, datasourceType: string) {
  const key = runtimeSchemaKey(role, datasourceType);
  if (runtimeSchemaCache.value[key] || runtimeSchemaLoading.value[key]) {
    return;
  }
  runtimeSchemaLoading.value[key] = true;
  try {
    runtimeSchemaCache.value[key] = await studioApi.catalog.runtimeOptionSchema({ role, datasourceType });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.advancedLoadFailed"));
  } finally {
    runtimeSchemaLoading.value[key] = false;
  }
}

function runtimeSchemaFor(role: RuntimeOptionRole, datasourceId: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return undefined;
  }
  return runtimeSchemaForType(role, datasourceType);
}

function runtimeSchemaForType(role: RuntimeOptionRole, datasourceType: string) {
  return runtimeSchemaCache.value[runtimeSchemaKey(role, datasourceType)];
}

function sourceIncrementalSupported(source: CollectionTaskSourceBinding) {
  if (!source.datasourceId) {
    return true;
  }
  const schema = runtimeSchemaFor("reader", source.datasourceId);
  return schema ? schema.incrementalSupported === true : true;
}

function sourceIncrementalVisible(source: CollectionTaskSourceBinding) {
  return collectionMode.value === "INCREMENTAL"
    && sourceIncrementalSupported(source);
}

function readerAdvancedFields(source: CollectionTaskSourceBinding): MetadataFieldDefinition[] {
  return runtimeSchemaFor("reader", source.datasourceId)?.fields ?? [];
}

function updateSourceReaderOptions(source: CollectionTaskSourceBinding, value: Record<string, unknown>) {
  const previousSql = selectSqlText(source);
  source.readerOptions = value ?? {};
  const nextSql = selectSqlText(source);
  if (previousSql !== nextSql) {
    scheduleResolveCustomSqlFields(source);
  }
}

function readerDynamicFunctionFields(source: CollectionTaskSourceBinding) {
  const datasourceType = resolveDatasourceTypeCode(source.datasourceId);
  if (fileReaderDatasourceTypes.has(datasourceType)) {
    return fileReaderDynamicFunctionFields;
  }
  if (datasourceType === "http") {
    return httpReaderDynamicFunctionFields;
  }
  return [];
}

function isHttpReaderSource(source: CollectionTaskSourceBinding) {
  return resolveDatasourceTypeCode(source.datasourceId) === "http";
}

function writerDynamicFunctionFields() {
  const datasourceType = resolveDatasourceTypeCode(form.targetBinding.datasourceId);
  if (fileWriterDatasourceTypes.has(datasourceType)) {
    return fileWriterDynamicFunctionFields;
  }
  if (datasourceType === "http") {
    return httpWriterDynamicFunctionFields;
  }
  return [];
}

function isHttpWriterTarget() {
  return resolveDatasourceTypeCode(form.targetBinding.datasourceId) === "http";
}

function updateFusionReaderOptions(value: Record<string, unknown>) {
  form.executionOptions = {
    ...form.executionOptions,
    fusionReaderOptions: value,
  };
}

function runtimeSchemaTitle(role: RuntimeOptionRole, datasourceId: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return "";
  }
  return runtimeSchemaTitleByType(role, datasourceType);
}

function runtimeSchemaTitleByType(role: RuntimeOptionRole, datasourceType: string) {
  const schema = runtimeSchemaForType(role, datasourceType);
  return schema?.pluginType ? `${schema.pluginType}${role}` : datasourceType;
}

function runtimeStatusType(role: RuntimeOptionRole, datasourceId: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  return runtimeStatusTypeByType(role, datasourceType);
}

function runtimeStatusTypeByType(role: RuntimeOptionRole, datasourceType: string) {
  const schema = runtimeSchemaForType(role, datasourceType);
  if (!schema?.runtimeSupported) {
    return "warning";
  }
  return schema.fields?.length ? "success" : "info";
}

function runtimeStatusLabel(role: RuntimeOptionRole, datasourceId: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  return runtimeStatusLabelByType(role, datasourceType);
}

function runtimeStatusLabelByType(role: RuntimeOptionRole, datasourceType: string) {
  const schema = runtimeSchemaForType(role, datasourceType);
  if (!schema?.runtimeSupported) {
    return t("web.collectionTasks.runtimeUnsupported");
  }
  return schema.fields?.length
    ? t("web.collectionTasks.runtimeSupported")
    : t("web.collectionTasks.runtimeSchemaMissingShort");
}

function runtimeSchemaKey(role: RuntimeOptionRole, datasourceType: string) {
  return `${role}:${datasourceType}`;
}

function resolveDatasourceTypeCode(datasourceId: unknown) {
  const datasource = datasources.value.find((item) => String(item.id) === String(datasourceId ?? ""));
  return datasource?.typeCode ?? "";
}

async function handleSourceDatasourceChange(row: CollectionTaskSourceBinding, value: string) {
  row.datasourceId = value;
  row.modelId = "";
  row.readerOptions = {};
  row.incremental = normalizeIncremental(row.incremental, collectionMode.value);
  await ensureModels(value);
  await ensureRuntimeSchemaForDatasource("reader", value);
  row.incremental = normalizeIncremental(row.incremental, sourceIncrementalSupported(row) ? collectionMode.value : "FULL");
  applyRuntimeDefaultsForSource(row);
}

function handleSourceModelChange(row: CollectionTaskSourceBinding, value: string) {
  row.modelId = value;
}

function selectSqlText(source: CollectionTaskSourceBinding) {
  const value = source.readerOptions?.selectSql;
  return typeof value === "string" ? value.trim() : "";
}

function supportsCustomSqlFields(source: CollectionTaskSourceBinding) {
  return Boolean(source.datasourceId)
    && Boolean(selectSqlText(source))
    && readerAdvancedFields(source).some((field) => field.fieldKey === "selectSql");
}

function customSqlFieldCacheKey(source: CollectionTaskSourceBinding) {
  const datasourceId = String(source.datasourceId ?? "").trim();
  const sql = selectSqlText(source);
  if (!datasourceId || !sql) {
    return "";
  }
  return `${datasourceId}:${sql.length}:${hashText(sql)}`;
}

function customSqlSourceKey(source: CollectionTaskSourceBinding) {
  const index = form.sourceBindings.indexOf(source);
  return `${source.sourceAlias?.trim() || "source"}:${index}`;
}

function hashText(value: string) {
  let hash = 0;
  for (let index = 0; index < value.length; index++) {
    hash = (hash * 31 + value.charCodeAt(index)) | 0;
  }
  return String(hash >>> 0);
}

function scheduleResolveCustomSqlFields(source: CollectionTaskSourceBinding) {
  const timerKey = customSqlSourceKey(source);
  const existingTimer = customSqlResolveTimers.get(timerKey);
  if (existingTimer) {
    window.clearTimeout(existingTimer);
  }
  customSqlResolveTimers.set(timerKey, window.setTimeout(() => {
    customSqlResolveTimers.delete(timerKey);
    void resolveCustomSqlFields(source);
  }, 600));
}

async function resolveAllCustomSqlFields(showError = false) {
  await Promise.all(form.sourceBindings.map((source) => resolveCustomSqlFields(source, showError)));
}

async function resolveCustomSqlFieldsForActiveStep(showError = false) {
  if (activeStep.value === 2) {
    await resolveAllCustomSqlFields(showError);
  }
}

async function resolveCustomSqlFields(source: CollectionTaskSourceBinding, showError = false) {
  if (!supportsCustomSqlFields(source)) {
    return;
  }
  const datasourceId = String(source.datasourceId ?? "").trim();
  const sql = selectSqlText(source);
  const cacheKey = customSqlFieldCacheKey(source);
  if (!cacheKey) {
    return;
  }
  const current = customSqlFieldCache.value[cacheKey];
  if (current?.loading || (current?.sql === sql && current.fields.length)) {
    return;
  }
  customSqlFieldCache.value = {
    ...customSqlFieldCache.value,
    [cacheKey]: {
      datasourceId,
      sql,
      fields: current?.fields ?? [],
      loading: true,
    },
  };
  try {
    const result = await studioApi.dataDevelopment.executeSql({
      datasourceId,
      scriptType: "SQL",
      content: sql,
      maxRows: 1,
    });
    customSqlFieldCache.value = {
      ...customSqlFieldCache.value,
      [cacheKey]: {
        datasourceId,
        sql,
        fields: uniqueFieldNames(result.columns),
        loading: false,
      },
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : t("web.collectionTasks.loadFailed");
    customSqlFieldCache.value = {
      ...customSqlFieldCache.value,
      [cacheKey]: {
        datasourceId,
        sql,
        fields: current?.fields ?? [],
        loading: false,
        error: message,
      },
    };
    if (showError) {
      ElMessage.error(message);
    }
  }
}

function uniqueFieldNames(fields: string[] | undefined) {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const field of fields ?? []) {
    const name = String(field ?? "").trim();
    if (!name || seen.has(name)) {
      continue;
    }
    seen.add(name);
    result.push(name);
  }
  return result;
}

async function handleTargetDatasourceChange(value: string) {
  form.targetBinding.datasourceId = value;
  form.targetBinding.modelId = "";
  form.targetBinding.writerOptions = {};
  await ensureModels(value);
  await ensureRuntimeSchemaForDatasource("writer", value);
  applyRuntimeDefaultsForTarget();
}

function applyRuntimeDefaults() {
  form.sourceBindings.forEach((source) => applyRuntimeDefaultsForSource(source));
  applyRuntimeDefaultsForTarget();
  applyRuntimeDefaultsForFusion();
}

function applyRuntimeDefaultsForSource(source: CollectionTaskSourceBinding) {
  const fields = readerAdvancedFields(source);
  if (!fields.length) {
    return;
  }
  source.readerOptions = mergeRuntimeDefaults(source.readerOptions, fields);
}

function applyRuntimeDefaultsForTarget() {
  if (!writerAdvancedFields.value.length) {
    return;
  }
  form.targetBinding.writerOptions = mergeTargetPrimaryKeyDefault(
    mergeRuntimeDefaults(form.targetBinding.writerOptions, writerAdvancedFields.value),
  );
}

function mergeTargetPrimaryKeyDefault(writerOptions: Record<string, unknown>) {
  if (hasConfiguredArrayValue(writerOptions.pkColumn) || !targetPrimaryKeyFields.value.length) {
    return writerOptions;
  }
  return {
    ...writerOptions,
    pkColumn: [...targetPrimaryKeyFields.value],
  };
}

function hasConfiguredArrayValue(value: unknown) {
  return Array.isArray(value) && value.some((item) => String(item).trim().length > 0);
}

function applyRuntimeDefaultsForFusion() {
  if (!isFusionTask.value || !fusionReaderAdvancedFields.value.length) {
    return;
  }
  updateFusionReaderOptions(mergeRuntimeDefaults(fusionReaderOptions.value, fusionReaderAdvancedFields.value));
}

function mergeRuntimeDefaults(current: Record<string, unknown> | undefined, fields: MetadataFieldDefinition[]) {
  const next = { ...(current ?? {}) };
  for (const field of fields) {
    if (!field.fieldKey || field.defaultValue === undefined || field.defaultValue === null || field.defaultValue === "") {
      continue;
    }
    if (next[field.fieldKey] !== undefined && next[field.fieldKey] !== null) {
      continue;
    }
    next[field.fieldKey] = parseDefaultValue(field);
  }
  return next;
}

function parseDefaultValue(field: MetadataFieldDefinition) {
  const rawValue = field.defaultValue;
  if (rawValue === undefined || rawValue === null) {
    return undefined;
  }
  if (field.valueType === "BOOLEAN") {
    return rawValue === "true";
  }
  if (field.valueType === "INTEGER" || field.valueType === "LONG" || field.valueType === "DECIMAL") {
    const numberValue = Number(rawValue);
    return Number.isNaN(numberValue) ? rawValue : numberValue;
  }
  if (field.valueType === "JSON" || field.valueType === "OBJECT" || field.valueType === "ARRAY") {
    try {
      return JSON.parse(rawValue);
    } catch (error) {
      return rawValue;
    }
  }
  return rawValue;
}

function enhanceWriterAdvancedField(field: MetadataFieldDefinition): MetadataFieldDefinition {
  if (field.fieldKey !== "pkColumn") {
    return field;
  }
  return {
    ...field,
    valueType: "ARRAY",
    componentType: "SELECT",
    options: [...targetFieldOptions.value],
  };
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function handleTargetModelChange(value: string) {
  form.targetBinding.modelId = value;
  applyRuntimeDefaultsForTarget();
  if (!form.fieldMappings.length) {
    initializeMappings();
  }
}

function initializeMappings() {
  const targetFields = resolveFieldsByModelId(form.targetBinding.modelId);
  form.fieldMappings = targetFields.map((field) => ({
    sourceAlias: sourceAliasOptions.value[0] ?? "",
    sourceField: "",
    targetField: field,
    expression: "",
    transformers: [],
  }));
}

async function loadPreviewConfig() {
  if (!canPreviewConfig.value) {
    previewConfig.value = null;
    return;
  }
  previewLoading.value = true;
  try {
    previewConfig.value = await studioApi.collectionTasks.preview(buildRequestPayload());
    previewDirty.value = false;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.previewFailed"));
  } finally {
    previewLoading.value = false;
  }
}

async function saveTask() {
  saving.value = true;
  try {
    const saved = await studioApi.collectionTasks.save(buildRequestPayload());
    ElMessage.success(t("web.collectionTasks.saveSuccess"));
    applyTask(saved);
    await router.push("/collection-tasks");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.saveFailed"));
  } finally {
    saving.value = false;
  }
}

function hasIncrementalCursor(source: CollectionTaskSourceBinding) {
  const incremental = source.incremental;
  if (!incremental) {
    return false;
  }
  return hasCursorValue(incremental.pkValue)
    || hasCursorValue(incremental.valueType)
    || hasCursorValue(incremental.lastRunRecordId)
    || hasCursorValue(incremental.lastUpdatedAt);
}

function hasCursorValue(value: unknown) {
  return value !== undefined && value !== null && String(value).trim() !== "";
}

function formatIncrementalCursorValue(value: unknown) {
  if (!hasCursorValue(value)) {
    return t("web.collectionTasks.incrementalCursorEmpty");
  }
  if (typeof value === "object") {
    try {
      return JSON.stringify(value);
    } catch (error) {
      return String(value);
    }
  }
  return String(value);
}

function formatIncrementalCursorMeta(value: unknown) {
  return hasCursorValue(value) ? String(value) : t("common.none");
}

function incrementalCursorResetKey(source: CollectionTaskSourceBinding) {
  return source.sourceAlias?.trim() || "src1";
}

function isIncrementalCursorResetting(source: CollectionTaskSourceBinding) {
  return Boolean(resettingIncrementalCursor.value[incrementalCursorResetKey(source)]);
}

async function resetIncrementalCursor(source: CollectionTaskSourceBinding) {
  if (!taskId.value || !hasIncrementalCursor(source)) {
    return;
  }
  const sourceAlias = incrementalCursorResetKey(source);
  try {
    await ElMessageBox.confirm(
      t("web.collectionTasks.resetIncrementalCursorConfirm"),
      t("common.confirm"),
      { type: "warning" },
    );
    resettingIncrementalCursor.value = {
      ...resettingIncrementalCursor.value,
      [sourceAlias]: true,
    };
    const updated = await studioApi.collectionTasks.resetIncrementalCursor(taskId.value, sourceAlias, {
      incrColumn: source.incremental?.incrColumn,
      incrModel: source.incremental?.incrModel,
    });
    applyTask(updated);
    await ensureRuntimeSchemas();
    ElMessage.success(t("web.collectionTasks.resetIncrementalCursorSuccess"));
  } catch (error) {
    if (error !== "cancel" && error !== "close") {
      ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.resetIncrementalCursorFailed"));
    }
  } finally {
    resettingIncrementalCursor.value = {
      ...resettingIncrementalCursor.value,
      [sourceAlias]: false,
    };
  }
}

watch(
  () => route.query.step,
  (value) => {
    const parsed = Number(value);
    activeStep.value = Number.isFinite(parsed) && parsed >= 1 && parsed <= 4 ? parsed : 1;
  },
  { immediate: true },
);

watch(taskId, async () => {
  await loadTask();
}, { immediate: true });

watch(
  () => [
    form.sourceBindings,
    form.targetBinding,
    form.fieldMappings,
    form.executionOptions,
  ],
  () => {
    previewDirty.value = true;
  },
  { deep: true },
);

watch(activeStep, async (value) => {
  if (value === 2) {
    await resolveCustomSqlFieldsForActiveStep(true);
  }
  if (value === 4 && previewDirty.value) {
    await loadPreviewConfig();
  }
}, { immediate: true });

watch(isFusionTask, async (value) => {
  if (!value) {
    return;
  }
  await ensureRuntimeSchemaForType("reader", "fusion");
  applyRuntimeDefaultsForFusion();
});

watch(collectionModeVisible, (visible) => {
  if (!visible && collectionMode.value !== "FULL") {
    collectionMode.value = "FULL";
  }
});

onMounted(loadReferenceData);
onBeforeUnmount(() => {
  customSqlResolveTimers.forEach((timer) => window.clearTimeout(timer));
  customSqlResolveTimers.clear();
});
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

.section-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.fusion-options {
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

.review-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.preview-panel {
  display: grid;
  gap: 12px;
}

.preview-json {
  max-height: 420px;
}

.editor-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 12px;
}

@media (max-width: 1100px) {
  .review-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
