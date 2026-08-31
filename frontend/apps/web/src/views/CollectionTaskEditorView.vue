<template>
  <div class="studio-page">
    <template v-if="detailLoadError && taskId">
      <div class="studio-toolbar">
        <div>
          <h3>{{ t("web.collectionTasks.editTitle") }}</h3>
          <p>{{ t("web.collectionTasks.unavailableDescription") }}</p>
        </div>
        <div class="studio-toolbar-actions">
          <el-button @click="router.push('/collection-tasks')">{{ t("common.backToList") }}</el-button>
          <el-button type="primary" plain @click="retryLoadTask">{{ t("common.refresh") }}</el-button>
        </div>
      </div>
      <SectionCard :title="t('web.collectionTasks.unavailableTitle')" :description="t('web.collectionTasks.unavailableDescription')">
        <el-result
          icon="warning"
          :title="t('web.collectionTasks.unavailableTitle')"
          :sub-title="detailLoadError"
        />
      </SectionCard>
    </template>

    <template v-else>
      <CollectionTaskEditorHeader
        :task-id="taskId"
        :saving="saving"
        :locked="streamingLocked"
        :actions="editorHeaderActions"
      />

      <el-alert
        v-if="runtimeValidation.invalid"
        type="error"
        show-icon
        :closable="false"
        :title="t('web.runtimeClusterSelection.invalid')"
        :description="runtimeValidation.message || t('web.runtimeClusterSelection.invalidFallback')"
      />

      <CollectionTaskStepIndicator v-model:active-step="activeStep" :execution-mode="form.executionMode" />

      <el-alert
        v-if="streamingLocked"
        type="warning"
        show-icon
        :closable="false"
        :title="t('web.collectionTasks.streamingEditLocked')"
      />

      <el-form :disabled="streamingLocked" class="collection-task-editor-form">
      <CollectionTaskBindingSection
        v-if="activeStep === 1"
        v-model:collection-mode="collectionMode"
        :form="form"
        :task-id="taskId"
        :task-type-label="taskTypeLabel"
        :collection-mode-visible="collectionModeVisible"
        :streaming-locked="streamingLocked"
        :is-fusion-task="isFusionTask"
        :datasources="datasources"
        :runtime-clusters="runtimeClusters"
        :runtime-clusters-loading="runtimeClustersLoading"
        :single-runtime-cluster="singleRuntimeCluster"
        :runtime-cluster-option-label="runtimeClusterOptionLabel"
        :runtime-cluster-option-disabled="runtimeClusterOptionDisabled"
        :on-runtime-cluster-change="handleRuntimeClusterChange"
        :writer-advanced-fields="writerAdvancedFields"
        :binding-actions="bindingSectionActions"
      />

      <CollectionTaskMappingSection
        v-else-if="activeStep === 2"
        v-model:join-keys="joinKeys"
        v-model:join-type="joinType"
        :form="form"
        :is-fusion-task="isFusionTask"
        :common-join-key-options="commonJoinKeyOptions"
        :fusion-reader-advanced-fields="fusionReaderAdvancedFields"
        :fusion-reader-options="fusionReaderOptions"
        :source-alias-options="sourceAliasOptions"
        :single-source-fields="singleSourceFields"
        :source-field-options-by-alias="sourceFieldOptionsByAlias"
        :target-field-options="targetFieldOptions"
        :field-mapping-rules="fieldMappingRules"
        :load-field-mapping-rule-detail="loadFieldMappingRuleDetail"
        :writer-soap-body-preview-fields="writerSoapBodyPreviewFields"
        :writer-options="form.targetBinding.writerOptions ?? {}"
        :mapping-actions="mappingSectionActions"
        :streaming-locked="streamingLocked"
      />

      <CollectionTaskScheduleSection
        v-else-if="activeStep === 3 && form.executionMode === 'BATCH'"
        :schedule="form.schedule"
        :runtime-invalid="runtimeValidation.invalid"
      />

      <CollectionTaskReviewSection
        v-else
        :form="form"
        :task-type-label="taskTypeLabel"
        :preview-loading="previewLoading"
        :can-preview-config="canPreviewConfig"
        :preview-config="previewConfig"
        :load-preview-config="loadPreviewConfig"
      />
      </el-form>

      <CollectionTaskEditorFooter
        v-model:active-step="activeStep"
        :saving="saving"
        :locked="streamingLocked"
        :max-step="form.executionMode === 'STREAMING' ? 3 : 4"
        :save-task="saveTask"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  CollectionTaskDefinitionView,
  JobContainerConfig,
  CollectionTaskSaveRequest,
  CollectionTaskSourceBinding,
  DataModelDefinition,
  DataModelDatasourceOptionView,
  DataSourceOptionView,
  EntityId,
  FieldMappingDefinition,
  FieldMappingRuleOptionView,
  FieldMappingRuleView,
  MetadataFieldDefinition,
  PluginRuntimeOptionSchemaView,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import CollectionTaskBindingSection from "@/components/collection-task/CollectionTaskBindingSection.vue";
import {
  createDefaultCollectionTaskForm,
  fileReaderDatasourceTypes,
  fileReaderDynamicFunctionFields,
  fileWriterDatasourceTypes,
  fileWriterDynamicFunctionFields,
  findIncrementalCursorState,
  formatIncrementalCursorMeta as formatIncrementalCursorMetaValue,
  formatIncrementalCursorValue as formatIncrementalCursorDisplayValue,
  hasConfiguredArrayValue,
  hasIncrementalCursor,
  httpReaderDynamicFunctionFields,
  httpWriterDynamicFunctionFields,
  incrementalCursorResetKey,
  inferCollectionMode,
  isPlainRecord,
  migrateLegacyStreamingReaderOptions,
  mergeRuntimeDefaults,
  migrateLegacyWriteMode,
  isStreamingTaskConfigurationLocked,
  normalizeIncremental,
  normalizeSourceBinding,
  normalizeTargetBinding,
  resolveFieldsByModel,
  resolvePrimaryKeyFieldsByModel,
  stripLegacyStreamingOptions,
  stripSystemIncrementalCursor,
  type RuntimeOptionRole,
} from "@/components/collection-task/collectionTaskEditorSupport";
import { buildAutomaticFieldMappings } from "@/components/collection-task/collectionTaskFieldAutoMapping";
import CollectionTaskEditorFooter from "@/components/collection-task/CollectionTaskEditorFooter.vue";
import CollectionTaskEditorHeader from "@/components/collection-task/CollectionTaskEditorHeader.vue";
import CollectionTaskMappingSection from "@/components/collection-task/CollectionTaskMappingSection.vue";
import CollectionTaskReviewSection from "@/components/collection-task/CollectionTaskReviewSection.vue";
import CollectionTaskScheduleSection from "@/components/collection-task/CollectionTaskScheduleSection.vue";
import CollectionTaskStepIndicator from "@/components/collection-task/CollectionTaskStepIndicator.vue";
import {
  buildSoapEnvelope,
  buildSoapFieldsXmlByParentPath,
  buildSoapRecordsRepeatXmlByPath,
  parseJsonObjectText,
  parseSoapEnvelope,
  resolveCommonSoapFieldParentPath,
  validateSoapArrayFieldParents,
  type SoapFieldSpec,
} from "@/components/open-service/openServiceDebugSupport";
import { cloneDeep } from "@/utils/studio";
import { resolveErrorMessage } from "@/composables/useAsyncAction";
import { useRuntimeClusterOptions } from "@/composables/useRuntimeClusterOptions";
import { useRuntimeClusterChangeGuard } from "@/composables/useRuntimeClusterChangeGuard";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();

const taskId = computed(() => route.params.taskId as string | undefined);
const {
  runtimeClusters,
  runtimeClustersLoading,
  singleRuntimeCluster,
  loadRuntimeClusters,
  resolveInitialRuntimeClusterId,
  ensureRuntimeClusterOption,
  runtimeClusterOptionLabel,
  runtimeClusterOptionDisabled,
} = useRuntimeClusterOptions();
const { acceptedRuntimeClusterId, acceptRuntimeCluster, confirmRuntimeClusterChange } = useRuntimeClusterChangeGuard();
const activeStep = ref(1);
const datasources = ref<DataSourceOptionView[]>([]);
const fieldMappingRules = ref<FieldMappingRuleOptionView[]>([]);
const MODEL_OPTION_PAGE_SIZE = 100;
const HTTP_READER_REMOVED_VALUE = "__STUDIO_HTTP_READER_REMOVED_VALUE__";
const HTTP_STRUCTURED_READER_KEYS = new Set(["header", "params", "requestBody"]);
const modelCache = ref<Record<string, DataModelDatasourceOptionView[]>>({});
const modelDetailCache = ref<Record<string, DataModelDefinition>>({});
const runtimeSchemaCache = ref<Record<string, PluginRuntimeOptionSchemaView>>({});
const runtimeSchemaLoading = ref<Record<string, boolean>>({});
const runtimeSchemaRequests = new Map<string, Promise<void>>();
const saving = ref(false);
const previewLoading = ref(false);
const previewDirty = ref(true);
const previewConfig = ref<JobContainerConfig | null>(null);
const detailLoadError = ref("");
const runtimeValidation = reactive({ invalid: false, message: "" });
const resettingIncrementalCursor = ref<Record<string, boolean>>({});
const customSqlFieldCache = ref<Record<string, { datasourceId: string; sql: string; fields: string[]; loading: boolean; error?: string }>>({});
const modelRequestSequences = new Map<string, number>();
let datasourceLoadSequence = 0;
let taskLoadSequence = 0;
const customSqlResolveTimers = new Map<string, number>();
const httpReaderDirtyKeys = new WeakMap<object, Set<string>>();

const form = reactive(createDefaultCollectionTaskForm());

const isFusionTask = computed(() => form.sourceBindings.length > 1);
const streamingLocked = computed(() => isStreamingTaskConfigurationLocked(
  form.executionMode,
  form.desiredState,
  form.observedState,
));
const taskTypeLabel = computed(() => (isFusionTask.value ? t("web.collectionTasks.typeFusion") : t("web.collectionTasks.typeSingle")));
const sourceAliasOptions = computed(() => form.sourceBindings.map((item) => item.sourceAlias).filter(Boolean));
const singleSourceFields = computed(() => {
  const source = form.sourceBindings[0];
  return source ? sourceFieldOptions(source) : [];
});
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
  && Boolean(form.targetBinding.modelId)
  && hasValidFieldMappings.value,
);
const hasValidFieldMappings = computed(() => !fieldMappingValidationMessage());
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
  (runtimeSchemaFor("writer", form.targetBinding.datasourceId, form.targetBinding.modelId)?.fields ?? []).map(enhanceWriterAdvancedField),
);

const writerSoapBodyPreviewFields = computed<MetadataFieldDefinition[]>(() =>
  writerAdvancedFields.value.filter((field) => field.fieldKey === "requestBody"),
);

const fusionReaderAdvancedFields = computed<MetadataFieldDefinition[]>(() =>
  runtimeSchemaForType("reader", "fusion")?.fields ?? [],
);

const fusionReaderOptions = computed<Record<string, unknown>>(() => {
  const value = form.executionOptions.fusionReaderOptions;
  return isPlainRecord(value) ? value : {};
});

const editorHeaderActions = {
  backToList: () => {
    void router.push("/collection-tasks");
  },
  refresh: loadReferenceData,
  saveTask,
};

const bindingSectionActions = {
  appendSourceBinding,
  removeSourceBinding,
  handleSourceDatasourceChange,
  handleSourceModelChange,
  resolveModelsByDatasource,
  searchModelsByDatasource,
  handleModelDropdownVisible,
  runtimeSchemaTitle,
  runtimeSchemaFor,
  runtimeStatusType,
  runtimeStatusLabel,
  sourceIncrementalVisible,
  syncCurrentIncrementalCursor,
  sourceFieldOptions,
  hasIncrementalCursor,
  formatIncrementalCursorValue: (value: unknown) => formatIncrementalCursorDisplayValue(value, t("web.collectionTasks.incrementalCursorEmpty")),
  formatIncrementalCursorMeta: (value: unknown) => formatIncrementalCursorMetaValue(value, t("common.none")),
  isIncrementalCursorResetting,
  resetIncrementalCursor,
  readerAdvancedFields,
  isHttpReaderSource,
  isHttpSoapReaderSource,
  readerSoapContract,
  readerSoapFieldNames,
  readerDynamicFunctionFields,
  updateSourceReaderOptions,
  markSourceReaderOptionDirty,
  handleTargetDatasourceChange,
  handleTargetModelChange,
  isHttpWriterTarget,
  isHttpSoapWriterTarget,
  writerSoapContract,
  writerSoapFieldNames,
  writerSoapFields,
  writerDynamicFunctionFields,
  updateTargetWriterOptions,
};

const mappingSectionActions = {
  initializeMappings,
  isHttpSoapWriterTarget,
  writerSoapContract,
  writerSoapFieldNames,
  writerSoapFields,
  runtimeSchemaTitleByType,
  runtimeSchemaForType,
  runtimeStatusTypeByType,
  runtimeStatusLabelByType,
  updateFusionReaderOptions,
  updateTargetWriterOptions,
  updateFieldMappings,
};

async function loadReferenceData() {
  try {
    const [, fieldMappingRuleData] = await Promise.all([
      loadRuntimeClusters(),
      studioApi.fieldMappingRules.optionSummaries(),
    ]);
    form.runtimeClusterId = resolveInitialRuntimeClusterId(form.runtimeClusterId) ?? "";
    acceptRuntimeCluster(form.runtimeClusterId);
    await loadDatasourcesForRuntimeCluster();
    fieldMappingRules.value = fieldMappingRuleData;
    await Promise.all(form.sourceBindings.map((item) => ensureModels(item.datasourceId)));
    await ensureModels(form.targetBinding.datasourceId);
    await ensureRuntimeSchemas();
    await resolveCustomSqlFieldsForActiveStep();
    syncHttpSoapWriterRequestBodyFromMappings();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.loadFailed"));
  }
}

async function loadTask() {
  const sequence = ++taskLoadSequence;
  const loadingTaskId = taskId.value;
  if (!taskId.value) {
    detailLoadError.value = "";
    runtimeValidation.invalid = false;
    runtimeValidation.message = "";
    return;
  }
  try {
    detailLoadError.value = "";
    const task = await studioApi.collectionTasks.get(taskId.value);
    if (sequence !== taskLoadSequence || taskId.value !== loadingTaskId) {
      return;
    }
    applyTask(task);
    await loadDatasourcesForRuntimeCluster();
    await Promise.all(form.sourceBindings.map((item) => ensureModels(item.datasourceId)));
    await ensureModels(form.targetBinding.datasourceId);
    await Promise.all([
      ...form.sourceBindings.map((item) => ensureModelDetail(item.modelId, item.datasourceId)),
      ensureModelDetail(form.targetBinding.modelId, form.targetBinding.datasourceId),
    ]);
    await ensureRuntimeSchemas();
    await resolveCustomSqlFieldsForActiveStep();
    syncHttpSoapWriterRequestBodyFromMappings();
  } catch (error) {
    if (sequence !== taskLoadSequence || taskId.value !== loadingTaskId) {
      return;
    }
    const message = resolveErrorMessage(error, t("web.collectionTasks.loadFailed"));
    detailLoadError.value = message;
    ElMessage.error(message);
  }
}

async function retryLoadTask() {
  await loadTask();
}

function loadFieldMappingRuleDetail(id: EntityId) {
  return studioApi.fieldMappingRules.get(id);
}

function applyTask(task: CollectionTaskDefinitionView) {
  const executionOptions = cloneDeep(task.executionOptions ?? {});
  const inferredCollectionMode = inferCollectionMode(task, executionOptions);
  const targetBinding = normalizeTargetBinding(cloneDeep(task.targetBinding ?? { datasourceId: "", modelId: "" }));
  migrateLegacyWriteMode(targetBinding, executionOptions);
  runtimeValidation.invalid = task.runtimeValid === false;
  runtimeValidation.message = task.runtimeValidationMessage ?? "";
  form.id = task.id;
  form.name = task.name;
  ensureRuntimeClusterOption(task.runtimeClusterId, task.runtimeClusterName);
  form.runtimeClusterId = task.runtimeClusterId ?? resolveInitialRuntimeClusterId() ?? "";
  acceptRuntimeCluster(form.runtimeClusterId);
  form.executionOptions = {
    collectionMode: inferredCollectionMode,
    joinKeys: [],
    joinType: "LEFT",
    ...executionOptions,
  };
  form.executionMode = task.executionMode ?? "BATCH";
  form.desiredState = task.desiredState;
  form.observedState = task.observedState;
  form.sourceBindings = cloneDeep(task.sourceBindings ?? []).map((item, index) =>
    normalizeSourceBinding(item, collectionMode.value, `src${index + 1}`));
  const legacyStreamingOptions = cloneDeep(task.streamingOptions ?? {});
  migrateLegacyStreamingReaderOptions(form.sourceBindings, legacyStreamingOptions);
  form.streamingOptions = {
    ...createDefaultCollectionTaskForm().streamingOptions,
    ...stripLegacyStreamingOptions(legacyStreamingOptions),
  };
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

async function ensureModels(datasourceId: unknown, keyword = "", force = false) {
  const key = String(datasourceId ?? "");
  const normalizedKeyword = keyword.trim();
  if (!key || key === "undefined" || key === "null" || (!normalizedKeyword && !force && modelCache.value[key])) {
    return;
  }
  const requestSequence = (modelRequestSequences.get(key) ?? 0) + 1;
  modelRequestSequences.set(key, requestSequence);
  const page = await studioApi.models.listDatasourceOptions(key, {
    keyword: normalizedKeyword || undefined,
    pageNo: 1,
    pageSize: MODEL_OPTION_PAGE_SIZE,
  });
  if (modelRequestSequences.get(key) !== requestSequence) {
    return;
  }
  modelCache.value[key] = page.items;
  ensureSelectedModelOptionsForDatasource(key);
}

async function loadDatasourcesForRuntimeCluster() {
  const clusterId = form.runtimeClusterId;
  const sequence = ++datasourceLoadSequence;
  if (!clusterId) {
    datasources.value = [];
    return;
  }
  const options = await studioApi.datasources.optionsByRuntimeCluster(clusterId);
  if (sequence === datasourceLoadSequence && String(form.runtimeClusterId) === String(clusterId)) {
    datasources.value = options;
  }
}

async function handleRuntimeClusterChange() {
  const nextRuntimeClusterId = form.runtimeClusterId;
  if (!await confirmRuntimeClusterChange(nextRuntimeClusterId)) {
    form.runtimeClusterId = acceptedRuntimeClusterId.value ?? "";
    return;
  }
  form.runtimeClusterId = acceptedRuntimeClusterId.value ?? nextRuntimeClusterId ?? "";
  form.sourceBindings = [normalizeSourceBinding({ sourceAlias: "src1", datasourceId: "", modelId: "" }, collectionMode.value, "src1")];
  form.targetBinding = normalizeTargetBinding({ datasourceId: "", modelId: "" });
  form.fieldMappings = [];
  form.executionOptions = { ...form.executionOptions, fusionReaderOptions: undefined };
  datasources.value = [];
  modelCache.value = {};
  modelRequestSequences.clear();
  modelDetailCache.value = {};
  runtimeSchemaCache.value = {};
  customSqlFieldCache.value = {};
  await loadDatasourcesForRuntimeCluster();
}

function resolveModelsByDatasource(datasourceId: unknown) {
  return modelCache.value[String(datasourceId ?? "")] ?? [];
}

async function searchModelsByDatasource(datasourceId: unknown, keyword: string) {
  await ensureModels(datasourceId, keyword, true);
}

function handleModelDropdownVisible(datasourceId: unknown, visible: boolean) {
  if (visible) {
    void ensureModels(datasourceId, "", true);
  }
}

function resolveModelById(modelId: unknown) {
  if (!modelId) {
    return undefined;
  }
  return modelDetailCache.value[String(modelId)] ?? undefined;
}

async function ensureModelDetail(modelId: unknown, datasourceId?: unknown) {
  const id = String(modelId ?? "");
  if (!id || id === "undefined" || id === "null" || modelDetailCache.value[id]) {
    return;
  }
  try {
    const detail = await studioApi.models.get(id);
    modelDetailCache.value = {
      ...modelDetailCache.value,
      [id]: detail,
    };
    ensureSelectedModelOption(datasourceId ?? detail.datasourceId, detail);
  } catch (error) {
    ElMessage.warning(resolveErrorMessage(error, t("web.collectionTasks.modelUnavailable")));
  }
}

function ensureSelectedModelOption(datasourceId: unknown, detail: DataModelDefinition) {
  const key = String(datasourceId ?? detail.datasourceId ?? "");
  if (!key || key === "undefined" || key === "null" || String(detail.datasourceId) !== key) {
    return;
  }
  const models = modelCache.value[key] ?? [];
  if (models.some((item) => String(item.id ?? "") === String(detail.id ?? ""))) {
    return;
  }
  modelCache.value = {
    ...modelCache.value,
    [key]: [
      ...models,
      toModelListView(detail),
    ],
  };
}

function ensureSelectedModelOptionsForDatasource(datasourceId: unknown) {
  const key = String(datasourceId ?? "");
  Object.values(modelDetailCache.value)
    .filter((detail) => String(detail.datasourceId ?? "") === key)
    .forEach((detail) => ensureSelectedModelOption(key, detail));
}

function toModelListView(model: DataModelDefinition): DataModelDatasourceOptionView {
  return {
    id: model.id,
    datasourceId: model.datasourceId,
    name: model.name,
    modelKind: model.modelKind,
    physicalLocator: model.physicalLocator,
  };
}

function resolveFieldsByModelId(modelId: unknown) {
  return resolveFieldsByModel(resolveModelById(modelId));
}

function sourceFieldOptions(source: CollectionTaskSourceBinding) {
  const localCustomSqlFields = resolveLocalCustomSqlFields(source);
  if (localCustomSqlFields.length) {
    return localCustomSqlFields;
  }
  if (supportsCustomSqlFields(source)) {
    return resolveCustomSqlCachedFields(source);
  }
  return resolveFieldsByModelId(source.modelId);
}

function resolveLocalCustomSqlFields(source: CollectionTaskSourceBinding) {
  if (resolveDatasourceTypeCode(source.datasourceId) !== "odps") {
    return [];
  }
  const parsedFields = parseSelectFieldNames(selectSqlText(source));
  return parsedFields.length ? parsedFields : resolveFieldsByModelId(source.modelId);
}

function resolveCustomSqlCachedFields(source: CollectionTaskSourceBinding) {
  const cacheKey = customSqlFieldCacheKey(source);
  if (!cacheKey) {
    return [];
  }
  const cache = customSqlFieldCache.value[cacheKey];
  return cache?.fields ?? [];
}

function syncIncrementalEnabled(mode: string) {
  form.sourceBindings.forEach((source) => {
    source.incremental = normalizeIncremental(source.incremental, sourceIncrementalSupported(source) ? mode : "FULL");
  });
}

function buildRequestPayload(): CollectionTaskSaveRequest {
  syncHttpSoapWriterRequestBodyFromMappings();
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
    normalized.readerOptions = removeUnmodifiedInheritedReaderOptions(
      normalized,
      httpReaderDirtyKeys.get(form.sourceBindings[index]) ?? new Set<string>(),
    );
    return normalized;
  });
  if (form.executionMode === "STREAMING") {
    migrateLegacyStreamingReaderOptions(payload.sourceBindings, payload.streamingOptions);
  }
  payload.sourceBindings.forEach((source) => {
    if (source.incremental) {
      stripSystemIncrementalCursor(source.incremental);
    }
  });
  payload.targetBinding = normalizeTargetBinding(payload.targetBinding);
  payload.executionMode = form.executionMode;
  payload.streamingOptions = form.executionMode === "STREAMING"
    ? stripLegacyStreamingOptions(cloneDeep(form.streamingOptions))
    : undefined;
  if (form.executionMode === "STREAMING") {
    payload.schedule = undefined;
  }
  return payload;
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

async function ensureRuntimeSchemas() {
  const jobs = [
    ...form.sourceBindings.map((source) => ensureRuntimeSchemaForDatasource("reader", source.datasourceId, source.modelId)),
    ensureRuntimeSchemaForDatasource("writer", form.targetBinding.datasourceId, form.targetBinding.modelId),
  ];
  if (isFusionTask.value) {
    jobs.push(ensureRuntimeSchemaForType("reader", "fusion"));
  }
  await Promise.all(jobs);
  applyRuntimeDefaults();
}

async function ensureRuntimeSchemaForDatasource(role: RuntimeOptionRole, datasourceId: unknown, modelId?: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return;
  }
  await ensureRuntimeSchemaForType(role, datasourceType, runtimeProtocolMode(datasourceType, modelId));
}

async function ensureRuntimeSchemaForType(role: RuntimeOptionRole, datasourceType: string, protocolMode?: string) {
  const key = runtimeSchemaKey(role, datasourceType, protocolMode);
  if (runtimeSchemaCache.value[key]) {
    return;
  }
  const pending = runtimeSchemaRequests.get(key);
  if (pending) {
    try {
      await pending;
    } catch {
      // 首个请求会负责展示错误；等待方保持调用链可继续恢复。
    }
    return;
  }
  runtimeSchemaLoading.value[key] = true;
  const request = (async () => {
    runtimeSchemaCache.value[key] = await studioApi.catalog.runtimeOptionSchema({
      role,
      datasourceType,
      protocolMode,
    });
  })();
  runtimeSchemaRequests.set(key, request);
  try {
    await request;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.advancedLoadFailed"));
  } finally {
    runtimeSchemaLoading.value[key] = false;
    runtimeSchemaRequests.delete(key);
  }
}

function runtimeSchemaFor(role: RuntimeOptionRole, datasourceId: unknown, modelId?: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return undefined;
  }
  return runtimeSchemaForType(role, datasourceType, runtimeProtocolMode(datasourceType, modelId));
}

function runtimeSchemaForType(role: RuntimeOptionRole, datasourceType: string, protocolMode?: string) {
  return runtimeSchemaCache.value[runtimeSchemaKey(role, datasourceType, protocolMode)];
}

function sourceIncrementalSupported(source: CollectionTaskSourceBinding) {
  if (!source.datasourceId) {
    return true;
  }
  const schema = runtimeSchemaFor("reader", source.datasourceId, source.modelId);
  return schema ? schema.incrementalSupported === true : true;
}

function sourceIncrementalVisible(source: CollectionTaskSourceBinding) {
  return collectionMode.value === "INCREMENTAL"
    && sourceIncrementalSupported(source);
}

function readerAdvancedFields(source: CollectionTaskSourceBinding): MetadataFieldDefinition[] {
  return runtimeSchemaFor("reader", source.datasourceId, source.modelId)?.fields ?? [];
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

function markSourceReaderOptionDirty(source: CollectionTaskSourceBinding, fieldKey: string) {
  const normalizedKey = String(fieldKey ?? "").trim();
  if (!normalizedKey || !isHttpReaderSource(source)) {
    return;
  }
  const keys = httpReaderDirtyKeys.get(source) ?? new Set<string>();
  keys.add(normalizedKey);
  httpReaderDirtyKeys.set(source, keys);
}

function isHttpSoapReaderSource(source: CollectionTaskSourceBinding) {
  return isHttpReaderSource(source) && runtimeProtocolMode("http", source.modelId) === "SOAP";
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

function isHttpSoapWriterTarget() {
  return isHttpWriterTarget() && runtimeProtocolMode("http", form.targetBinding.modelId) === "SOAP";
}

function readerSoapContract(source: CollectionTaskSourceBinding) {
  return soapContractForModel(source.modelId);
}

function readerSoapFieldNames(_source: CollectionTaskSourceBinding) {
  return [];
}

function writerSoapContract() {
  return soapContractForModel(form.targetBinding.modelId);
}

function writerSoapFieldNames() {
  return uniqueFieldNames(effectiveWriterSoapMappedFieldNames());
}

function writerSoapFields() {
  const metadata = resolveModelColumnMetadata(targetModel.value);
  return effectiveWriterSoapMappings().map((mapping) => {
    const name = mapping.targetField?.trim() || "";
    const field = metadata.get(name);
    const parentNode = String(field?.parentNode ?? "").trim();
    return {
      name,
      parentNode: parentNode || undefined,
    };
  }).filter((field) => Boolean(field.name));
}

function syncHttpSoapWriterRequestBodyFromMappings() {
  if (!isHttpSoapWriterTarget()) {
    return;
  }
  const writerOptions = form.targetBinding.writerOptions ?? {};
  const soapFields = writerSoapFields();
  const bodyFields: SoapFieldSpec[] = soapFields.map((field) => ({
    key: field.name,
    elementName: field.name,
    value: `{{${field.name}}}`,
    parentNode: field.parentNode,
  }));
  const payloadMode = String(writerOptions.payloadMode ?? "object").trim().toLowerCase();
  const arrayMode = payloadMode === "array";
  let dataNodePath = String(writerOptions.dataNodePath ?? "").trim();
  if (arrayMode && !dataNodePath) {
    dataNodePath = resolveCommonSoapFieldParentPath(soapFields);
  }
  if (arrayMode && !dataNodePath) {
    return;
  }
  if (arrayMode && validateSoapArrayFieldParents(bodyFields, dataNodePath)) {
    return;
  }
  const contract = writerSoapContract();
  const requestBody = buildSoapEnvelope({
    soapVersion: String(writerOptions.soapVersion || contract.soapVersion || "SOAP_11"),
    namespaceUri: contract.namespaceUri || "urn:studio",
    requestRootName: contract.requestRootName || contract.operationName || "Operation",
    includeToken: false,
    headerFields: [],
    fields: [],
    bodyInnerXml: arrayMode
      ? buildSoapRecordsRepeatXmlByPath(bodyFields, dataNodePath)
      : buildSoapFieldsXmlByParentPath(bodyFields),
  });
  const nextOptions: Record<string, unknown> = {
    ...writerOptions,
    requestBody,
  };
  if (arrayMode && dataNodePath) {
    nextOptions.dataNodePath = dataNodePath;
  }
  if (
    String(writerOptions.requestBody ?? "") === requestBody
    && (!arrayMode || String(writerOptions.dataNodePath ?? "").trim() === dataNodePath)
  ) {
    return;
  }
  form.targetBinding.writerOptions = nextOptions;
}

function effectiveWriterSoapMappedFieldNames() {
  return effectiveWriterSoapMappings()
    .map((mapping) => mapping.targetField?.trim())
    .filter((field): field is string => Boolean(field));
}

function effectiveWriterSoapMappings() {
  return form.fieldMappings.filter(isEffectiveWriterMapping);
}

function isEffectiveWriterMapping(mapping: FieldMappingDefinition) {
  return Boolean(
    mapping.targetField?.trim()
    && (
      mapping.sourceField?.trim()
      || mapping.expression?.trim()
    ),
  );
}

function soapContractForModel(modelId: unknown) {
  const metadata = resolveModelById(modelId)?.technicalMetadata ?? {};
  return {
    soapVersion: String(metadata.soapVersion ?? "").trim() || undefined,
    namespaceUri: String(metadata.namespaceUri ?? "").trim() || undefined,
    operationName: String(metadata.operationName ?? "").trim() || undefined,
    requestRootName: String(metadata.requestRootName ?? metadata.operationName ?? "").trim() || undefined,
  };
}

function resolveModelColumnMetadata(model: DataModelDefinition | undefined) {
  const columns = model?.technicalMetadata?.columns;
  const result = new Map<string, Record<string, unknown>>();
  if (!Array.isArray(columns)) {
    return result;
  }
  for (const item of columns) {
    if (!item || typeof item !== "object") {
      continue;
    }
    const record = item as Record<string, unknown>;
    const name = String(record.name ?? "").trim();
    if (name) {
      result.set(name, record);
    }
  }
  return result;
}

function updateTargetWriterOptions(value: Record<string, unknown>) {
  form.targetBinding.writerOptions = value ?? {};
  syncHttpSoapWriterRequestBodyFromMappings();
}

function updateFusionReaderOptions(value: Record<string, unknown>) {
  form.executionOptions = {
    ...form.executionOptions,
    fusionReaderOptions: value,
  };
}

function runtimeSchemaTitle(role: RuntimeOptionRole, datasourceId: unknown, modelId?: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return "";
  }
  return runtimeSchemaTitleByType(role, datasourceType, runtimeProtocolMode(datasourceType, modelId));
}

function runtimeSchemaTitleByType(role: RuntimeOptionRole, datasourceType: string, protocolMode?: string) {
  const schema = runtimeSchemaForType(role, datasourceType, protocolMode);
  return schema?.pluginType ? `${schema.pluginType}${role}` : datasourceType;
}

function runtimeStatusType(role: RuntimeOptionRole, datasourceId: unknown, modelId?: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  return runtimeStatusTypeByType(role, datasourceType, runtimeProtocolMode(datasourceType, modelId));
}

function runtimeStatusTypeByType(role: RuntimeOptionRole, datasourceType: string, protocolMode?: string) {
  const schema = runtimeSchemaForType(role, datasourceType, protocolMode);
  if (!schema?.runtimeSupported) {
    return "warning";
  }
  return schema.fields?.length ? "success" : "info";
}

function runtimeStatusLabel(role: RuntimeOptionRole, datasourceId: unknown, modelId?: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  return runtimeStatusLabelByType(role, datasourceType, runtimeProtocolMode(datasourceType, modelId));
}

function runtimeStatusLabelByType(role: RuntimeOptionRole, datasourceType: string, protocolMode?: string) {
  const schema = runtimeSchemaForType(role, datasourceType, protocolMode);
  if (!schema?.runtimeSupported) {
    return t("web.collectionTasks.runtimeUnsupported");
  }
  return schema.fields?.length
    ? t("web.collectionTasks.runtimeSupported")
    : t("web.collectionTasks.runtimeSchemaMissingShort");
}

function runtimeSchemaKey(role: RuntimeOptionRole, datasourceType: string, protocolMode?: string) {
  const mode = protocolMode === "SOAP" ? ":SOAP" : "";
  return `${role}:${datasourceType}${mode}`;
}

function runtimeProtocolMode(datasourceType: string, modelId?: unknown) {
  if (datasourceType !== "http") {
    return undefined;
  }
  const metadata = resolveModelById(modelId)?.technicalMetadata ?? {};
  const mode = String(metadata.protocolMode ?? "").trim().toUpperCase();
  if (mode === "SOAP") {
    return "SOAP";
  }
  const resultType = String(metadata.resultType ?? "").trim().toLowerCase();
  return resultType === "soap" ? "SOAP" : undefined;
}

function resolveDatasourceTypeCode(datasourceId: unknown) {
  const datasource = datasources.value.find((item) => String(item.id) === String(datasourceId ?? ""));
  return datasource?.typeCode ?? "";
}

async function handleSourceDatasourceChange(row: CollectionTaskSourceBinding, value: string) {
  httpReaderDirtyKeys.delete(row);
  row.datasourceId = value;
  row.modelId = "";
  row.readerOptions = {};
  row.incremental = normalizeIncremental(row.incremental, collectionMode.value);
  await ensureModels(value);
  await ensureRuntimeSchemaForDatasource("reader", value, row.modelId);
  row.incremental = normalizeIncremental(row.incremental, sourceIncrementalSupported(row) ? collectionMode.value : "FULL");
  applyRuntimeDefaultsForSource(row);
}

async function handleSourceModelChange(row: CollectionTaskSourceBinding, value: string) {
  httpReaderDirtyKeys.delete(row);
  row.modelId = value;
  row.readerOptions = {};
  await ensureModelDetail(value, row.datasourceId);
  await ensureRuntimeSchemaForDatasource("reader", row.datasourceId, value);
  applyRuntimeDefaultsForSource(row);
}

function selectSqlText(source: CollectionTaskSourceBinding) {
  const value = source.readerOptions?.selectSql;
  return typeof value === "string" ? value.trim() : "";
}

function supportsCustomSqlFields(source: CollectionTaskSourceBinding) {
  return Boolean(source.datasourceId)
    && resolveDatasourceTypeCode(source.datasourceId) !== "odps"
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
  const results = await Promise.all(form.sourceBindings.map((source) => resolveCustomSqlFields(source, showError)));
  return results.every(Boolean);
}

async function resolveCustomSqlFieldsForActiveStep(showError = false) {
  if (activeStep.value === 2) {
    await resolveAllCustomSqlFields(showError);
  }
}

async function resolveCustomSqlFields(source: CollectionTaskSourceBinding, showError = false) {
  if (!supportsCustomSqlFields(source)) {
    return true;
  }
  const datasourceId = String(source.datasourceId ?? "").trim();
  const sql = selectSqlText(source);
  const cacheKey = customSqlFieldCacheKey(source);
  if (!cacheKey) {
    return true;
  }
  const current = customSqlFieldCache.value[cacheKey];
  if (current?.loading) {
    return false;
  }
  if (current?.sql === sql && current.fields.length && !current.error) {
    return true;
  }
  customSqlFieldCache.value = {
    ...customSqlFieldCache.value,
    [cacheKey]: {
      datasourceId,
      sql,
      fields: [],
      loading: true,
    },
  };
  try {
    const result = await studioApi.dataDevelopment.executeSql({
      datasourceId,
      runtimeClusterId: form.runtimeClusterId,
      scriptType: "SQL",
      content: sql,
      maxRows: 1,
    });
    const fields = uniqueFieldNames(result.columns);
    if (!fields.length) {
      throw new Error(t("web.collectionTasks.customSqlNoFields"));
    }
    customSqlFieldCache.value = {
      ...customSqlFieldCache.value,
      [cacheKey]: {
        datasourceId,
        sql,
        fields,
        loading: false,
      },
    };
    return true;
  } catch (error) {
    const message = error instanceof Error ? error.message : t("web.collectionTasks.loadFailed");
    customSqlFieldCache.value = {
      ...customSqlFieldCache.value,
      [cacheKey]: {
        datasourceId,
        sql,
        fields: [],
        loading: false,
        error: message,
      },
    };
    if (showError) {
      ElMessage.error(message);
    }
    return false;
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

function parseSelectFieldNames(sql: string) {
  const normalized = sql.trim().replace(/;+\s*$/, "");
  if (!/^select\s+/i.test(normalized)) {
    return [];
  }
  const fromIndex = findTopLevelKeyword(normalized, "from", 6);
  if (fromIndex <= 0) {
    return [];
  }
  const selectList = normalized.slice(6, fromIndex).trim();
  if (!selectList || selectList.includes("*")) {
    return [];
  }
  return uniqueFieldNames(splitTopLevelComma(selectList)
    .map((item) => resolveSelectItemName(item))
    .filter((item): item is string => Boolean(item)));
}

function findTopLevelKeyword(text: string, keyword: string, startIndex = 0) {
  const lower = text.toLowerCase();
  let depth = 0;
  let quote: string | undefined;
  for (let index = startIndex; index <= text.length - keyword.length; index++) {
    const char = text[index];
    if (quote) {
      if (char === quote) {
        quote = undefined;
      }
      continue;
    }
    if (char === "'" || char === "\"" || char === "`") {
      quote = char;
      continue;
    }
    if (char === "(") {
      depth++;
      continue;
    }
    if (char === ")") {
      depth = Math.max(0, depth - 1);
      continue;
    }
    if (depth === 0
      && lower.slice(index, index + keyword.length) === keyword
      && isSqlWordBoundary(text[index - 1])
      && isSqlWordBoundary(text[index + keyword.length])) {
      return index;
    }
  }
  return -1;
}

function splitTopLevelComma(text: string) {
  const result: string[] = [];
  let current = "";
  let depth = 0;
  let quote: string | undefined;
  for (const char of text) {
    if (quote) {
      current += char;
      if (char === quote) {
        quote = undefined;
      }
      continue;
    }
    if (char === "'" || char === "\"" || char === "`") {
      quote = char;
      current += char;
      continue;
    }
    if (char === "(") {
      depth++;
    } else if (char === ")") {
      depth = Math.max(0, depth - 1);
    }
    if (char === "," && depth === 0) {
      result.push(current.trim());
      current = "";
      continue;
    }
    current += char;
  }
  if (current.trim()) {
    result.push(current.trim());
  }
  return result;
}

function resolveSelectItemName(item: string) {
  const normalized = item.trim();
  if (!normalized || normalized === "*") {
    return "";
  }
  const asMatch = normalized.match(/\s+as\s+([`"']?[\w\u4e00-\u9fa5]+[`"']?)$/i);
  if (asMatch?.[1]) {
    return stripSqlIdentifierQuote(asMatch[1]);
  }
  const trailingAlias = normalized.match(/\s+([`"']?[\w\u4e00-\u9fa5]+[`"']?)$/);
  if (trailingAlias && !/^[`"']?[\w\u4e00-\u9fa5]+[`"']?(\.[`"']?[\w\u4e00-\u9fa5]+[`"']?)*$/.test(normalized)) {
    return stripSqlIdentifierQuote(trailingAlias[1]);
  }
  const identifierMatch = normalized.match(/(?:^|\.)([`"']?[\w\u4e00-\u9fa5]+[`"']?)$/);
  return identifierMatch?.[1] ? stripSqlIdentifierQuote(identifierMatch[1]) : "";
}

function stripSqlIdentifierQuote(value: string) {
  return value.replace(/^[`"']|[`"']$/g, "");
}

function isSqlWordBoundary(char: string | undefined) {
  return !char || !/[\w\u4e00-\u9fa5]/.test(char);
}

async function handleTargetDatasourceChange(value: string) {
  form.targetBinding.datasourceId = value;
  form.targetBinding.modelId = "";
  form.targetBinding.writerOptions = {};
  await ensureModels(value);
  await ensureRuntimeSchemaForDatasource("writer", value, form.targetBinding.modelId);
  applyRuntimeDefaultsForTarget();
  syncHttpSoapWriterRequestBodyFromMappings();
}

function applyRuntimeDefaults() {
  form.sourceBindings.forEach((source) => applyRuntimeDefaultsForSource(source));
  applyRuntimeDefaultsForTarget();
  applyRuntimeDefaultsForFusion();
}

function applyRuntimeDefaultsForSource(source: CollectionTaskSourceBinding) {
  const fields = readerAdvancedFields(source);
  if (!isHttpReaderSource(source)) {
    source.readerOptions = mergeRuntimeDefaults(source.readerOptions, fields);
    return;
  }

  if (!httpReaderDirtyKeys.has(source)) {
    httpReaderDirtyKeys.set(source, new Set<string>());
  }
  const effectiveDefaults = {
    ...mergeRuntimeDefaults({}, fields),
    ...modelReaderOptions(source),
  };
  source.readerOptions = mergeHttpReaderEditorOptions(
    effectiveDefaults,
    source.readerOptions ?? {},
  );
}

function mergeHttpReaderEditorOptions(
  defaults: Record<string, unknown>,
  current: Record<string, unknown>,
) {
  return {
    ...defaults,
    ...current,
  };
}

function modelReaderOptions(source: CollectionTaskSourceBinding) {
  if (String(resolveDatasourceTypeCode(source.datasourceId)).trim().toLowerCase() !== "http") {
    return {};
  }
  const value = resolveModelById(source.modelId)?.technicalMetadata?.readerOptions;
  if (!isPlainRecord(value)) {
    return {};
  }
  const options = { ...value };
  delete options.soapVersion;
  delete options.soapAction;
  return options;
}

function removeUnmodifiedInheritedReaderOptions(
  source: CollectionTaskSourceBinding,
  dirtyKeys: ReadonlySet<string> = new Set<string>(),
) {
  const overrides = { ...(source.readerOptions ?? {}) };
  if (!isHttpReaderSource(source)) {
    return overrides;
  }
  const inherited = modelReaderOptions(source);
  const runtimeDefaults = mergeRuntimeDefaults({}, readerAdvancedFields(source));
  const effectiveDefaults = {
    ...runtimeDefaults,
    ...inherited,
  };
  Object.entries(overrides).forEach(([key, value]) => {
    if (
      Object.prototype.hasOwnProperty.call(effectiveDefaults, key)
      && optionValuesEqual(value, effectiveDefaults[key])
      && !dirtyKeys.has(key)
    ) {
      delete overrides[key];
      return;
    }
    if (Object.prototype.hasOwnProperty.call(inherited, key) && HTTP_STRUCTURED_READER_KEYS.has(key)) {
      overrides[key] = addHttpReaderRemovalMarkers(value, inherited[key]);
    }
  });
  return overrides;
}

function addHttpReaderRemovalMarkers(value: unknown, inheritedValue: unknown): unknown {
  const current = parseHttpReaderObject(value);
  const inherited = parseHttpReaderObject(inheritedValue);
  if (!current || !inherited) {
    return value;
  }
  const marked = addObjectRemovalMarkers(current, inherited);
  return typeof value === "string" ? JSON.stringify(marked) : marked;
}

function addObjectRemovalMarkers(
  current: Record<string, unknown>,
  inherited: Record<string, unknown>,
): Record<string, unknown> {
  const result: Record<string, unknown> = { ...current };
  Object.entries(inherited).forEach(([key, inheritedValue]) => {
    if (!Object.prototype.hasOwnProperty.call(current, key)) {
      result[key] = HTTP_READER_REMOVED_VALUE;
      return;
    }
    if (isPlainRecord(current[key]) && isPlainRecord(inheritedValue)) {
      result[key] = addObjectRemovalMarkers(current[key], inheritedValue);
    }
  });
  return result;
}

function parseHttpReaderObject(value: unknown): Record<string, unknown> | undefined {
  if (isPlainRecord(value)) {
    return { ...value };
  }
  if (typeof value !== "string" || !value.trim().startsWith("{")) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(value);
    return isPlainRecord(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

function optionValuesEqual(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) {
    return true;
  }
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left)
      && Array.isArray(right)
      && left.length === right.length
      && left.every((item, index) => optionValuesEqual(item, right[index]));
  }
  if (isPlainRecord(left) || isPlainRecord(right)) {
    if (!isPlainRecord(left) || !isPlainRecord(right)) {
      return false;
    }
    const leftKeys = Object.keys(left);
    const rightKeys = Object.keys(right);
    return leftKeys.length === rightKeys.length
      && leftKeys.every((key) => Object.prototype.hasOwnProperty.call(right, key)
        && optionValuesEqual(left[key], right[key]));
  }
  return false;
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

function applyRuntimeDefaultsForFusion() {
  if (!isFusionTask.value || !fusionReaderAdvancedFields.value.length) {
    return;
  }
  updateFusionReaderOptions(mergeRuntimeDefaults(fusionReaderOptions.value, fusionReaderAdvancedFields.value));
}

function enhanceWriterAdvancedField(field: MetadataFieldDefinition): MetadataFieldDefinition {
  if (field.fieldKey !== "pkColumn" && field.fieldKey !== "partitionColumns") {
    return field;
  }
  return {
    ...field,
    valueType: "ARRAY",
    componentType: "SELECT",
    options: [...targetFieldOptions.value],
  };
}

async function handleTargetModelChange(value: string) {
  form.targetBinding.modelId = value;
  form.targetBinding.writerOptions = {};
  await ensureModelDetail(value, form.targetBinding.datasourceId);
  await ensureRuntimeSchemaForDatasource("writer", form.targetBinding.datasourceId, value);
  applyRuntimeDefaultsForTarget();
  if (!form.fieldMappings.length) {
    initializeMappings();
  }
  syncHttpSoapWriterRequestBodyFromMappings();
}

function initializeMappings() {
  const targetFields = resolveFieldsByModelId(form.targetBinding.modelId);
  const sources = form.sourceBindings.map((source) => ({
    sourceAlias: source.sourceAlias ?? "",
    fields: sourceFieldOptions(source),
  }));
  form.fieldMappings = buildAutomaticFieldMappings(targetFields, sources);
  syncHttpSoapWriterRequestBodyFromMappings();
}

function updateFieldMappings(value: FieldMappingDefinition[]) {
  form.fieldMappings = value;
  syncHttpSoapWriterRequestBodyFromMappings();
}

async function loadPreviewConfig() {
  if (!canPreviewConfig.value) {
    previewConfig.value = null;
    return;
  }
  if (!(await resolveAllCustomSqlFields(true))) {
    previewConfig.value = null;
    return;
  }
  if (!validateFieldMappings()) {
    return;
  }
  if (!validateHttpSoapRuntimeOptions()) {
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
  if (detailLoadError.value && taskId.value) {
    ElMessage.error(detailLoadError.value);
    return;
  }
  if (streamingLocked.value) {
    ElMessage.warning(t("web.collectionTasks.streamingEditLocked"));
    return;
  }
  if (!form.runtimeClusterId) {
    ElMessage.warning(t("web.runtimeClusterSelection.selectFirst"));
    return;
  }
  if (!(await resolveAllCustomSqlFields(true))) {
    return;
  }
  if (!validateFieldMappings()) {
    return;
  }
  if (!validateHttpSoapRuntimeOptions()) {
    return;
  }
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

function validateHttpSoapRuntimeOptions() {
  syncHttpSoapWriterRequestBodyFromMappings();
  try {
    form.sourceBindings.forEach((source, index) => {
      if (isHttpSoapReaderSource(source)) {
        validateHttpSoapOptions(source.readerOptions ?? {}, `第 ${index + 1} 个 SOAP Reader`, false);
      }
    });
    if (isHttpSoapWriterTarget()) {
      const writerOptions = form.targetBinding.writerOptions ?? {};
      validateHttpSoapOptions(writerOptions, "SOAP Writer", isArrayPayloadMode(writerOptions));
    }
    return true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.saveFailed"));
    return false;
  }
}

function validateFieldMappings() {
  const message = fieldMappingValidationMessage();
  if (!message) {
    return true;
  }
  ElMessage.warning(message);
  return false;
}

function fieldMappingValidationMessage() {
  const targetFields = new Set<string>();
  for (let index = 0; index < form.fieldMappings.length; index += 1) {
    const mapping = form.fieldMappings[index];
    const displayIndex = index + 1;
    const targetField = mapping.targetField?.trim() ?? "";
    const sourceField = mapping.sourceField?.trim() ?? "";
    const sourceAlias = mapping.sourceAlias?.trim() ?? "";
    const expression = mapping.expression?.trim() ?? "";
    if (!targetField) {
      return t("web.collectionTasks.fieldMappingTargetRequired", { index: displayIndex });
    }
    const normalizedTarget = targetField.toLocaleLowerCase();
    if (targetFields.has(normalizedTarget)) {
      return t("web.collectionTasks.fieldMappingTargetDuplicate", { targetField });
    }
    targetFields.add(normalizedTarget);
    if (!sourceField && !expression) {
      return t("web.collectionTasks.fieldMappingSourceOrExpressionRequired", { index: displayIndex });
    }
    if (sourceField && !sourceAlias) {
      return t("web.collectionTasks.fieldMappingSourceAliasRequired", { index: displayIndex });
    }
  }
  return "";
}

function validateHttpSoapOptions(options: Record<string, unknown>, label: string, requireRecordsRepeat: boolean) {
  const requestBody = String(options.requestBody ?? "");
  if (!requestBody.trim()) {
    throw new Error(`${label} 的 SOAP Envelope 不能为空`);
  }
  const parsedEnvelope = parseSoapEnvelope(requestBody);
  if (!parsedEnvelope.ok) {
    throw new Error(`${label} ${parsedEnvelope.error}`);
  }
  if (requireRecordsRepeat) {
    const repeatError = validateRecordsRepeatBlock(requestBody);
    if (repeatError) {
      throw new Error(`${label} ${repeatError}`);
    }
  }
  const headers = stringifyJsonObjectOption(options.header, "{}");
  const parsedHeaders = parseJsonObjectText(headers, `${label} HTTP Headers`);
  if (!parsedHeaders.ok) {
    throw new Error(parsedHeaders.error);
  }
}

function isArrayPayloadMode(options: Record<string, unknown>) {
  return String(options.payloadMode ?? "object").trim().toLowerCase() === "array";
}

function validateRecordsRepeatBlock(value: string) {
  const startToken = "{{#records}}";
  const endToken = "{{/records}}";
  const start = value.indexOf(startToken);
  const end = value.indexOf(endToken);
  if (start < 0 || end < 0) {
    return "array 模式必须包含 {{#records}}...{{/records}} repeat 块";
  }
  if (start !== value.lastIndexOf(startToken) || end !== value.lastIndexOf(endToken)) {
    return "array 模式只能包含一个 {{#records}}...{{/records}} repeat 块";
  }
  if (!value.slice(start + startToken.length, end).trim()) {
    return "array 模式 repeat 块不能为空";
  }
  return "";
}

function stringifyJsonObjectOption(value: unknown, fallback: string) {
  if (value == null || value === "") {
    return fallback;
  }
  if (typeof value === "string") {
    return value.trim() ? value : fallback;
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
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
    const maxStep = form.executionMode === "STREAMING" ? 3 : 4;
    activeStep.value = Number.isFinite(parsed) && parsed >= 1 && parsed <= maxStep ? parsed : 1;
  },
  { immediate: true },
);

watch(taskId, async () => {
  await loadReferenceData();
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
  syncStepQuery(value);
  if (value === 2) {
    await resolveCustomSqlFieldsForActiveStep(true);
  }
  if (value === 4 && previewDirty.value) {
    await loadPreviewConfig();
  }
}, { immediate: true });

function syncStepQuery(value: number) {
  const nextStep = String(value);
  if (route.query.step === nextStep) {
    return;
  }
  void router.replace({
    query: {
      ...route.query,
      step: nextStep,
    },
  });
}

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

watch(() => form.executionMode, (mode) => {
  if (mode === "STREAMING" && activeStep.value === 3) {
    activeStep.value = 3;
  }
  if (mode === "BATCH" && activeStep.value > 4) {
    activeStep.value = 4;
  }
});

onBeforeUnmount(() => {
  customSqlResolveTimers.forEach((timer) => window.clearTimeout(timer));
  customSqlResolveTimers.clear();
});
</script>
