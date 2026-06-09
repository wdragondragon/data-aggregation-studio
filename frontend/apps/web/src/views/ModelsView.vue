<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.models.heading") }}</h3>
        <p>{{ t("web.models.description") }}</p>
      </div>
      <ModelIndexQueueCard
        v-if="!isDetailPage"
        :busy="indexQueueBusy"
        :pending-rebuild-count="indexQueuePendingRebuildCount"
        :queued-command-count="indexQueueQueuedCommandCount"
      />
    </div>

    <template v-if="!isDetailPage">
      <el-tabs v-model="activeListTab" class="models-tabs">
        <el-tab-pane label="模型列表" name="models">
          <ModelListPanel
            v-model:datasource-type="selectedDatasourceType"
            v-model:datasource-id="selectedDatasourceId"
            :query-datasource-types="queryDatasourceTypes"
            :datasource-options="filteredDatasourceOptions"
            :current-project-id="authStore.currentProjectId"
            :query-groups="queryGroups"
            :active-query-datasource-type="activeQueryDatasourceType"
            :query-schema-options="querySchemaOptions"
            :dynamic-filter-actions="dynamicFilterActions"
            :models="models"
            :pagination="modelPagination"
            :sort-state="modelSortState"
            :empty-text="modelTableEmptyText"
            :actions="modelListActions"
          />
        </el-tab-pane>

        <el-tab-pane label="模型同步任务" name="sync-tasks">
          <ModelSyncTaskSection
            :datasource-types="queryDatasourceTypes"
            :datasource-options="syncTaskFilterDatasourceOptions"
            :status-options="syncTaskStatusOptions"
            :filters="syncTaskFilters"
            :tasks="syncTasks"
            :loading="loadingSyncTasksPage"
            :pagination="syncTaskPagination"
            :total="syncTaskTotal"
            :current-project-id="authStore.currentProjectId"
            :actions="syncTaskSectionActions"
          />
        </el-tab-pane>
      </el-tabs>
    </template>

    <ModelDetailShell
      v-else
      v-model:active-tab="activeDetailTab"
      :model-id="detailModelId"
      :model="selectedModel"
      :shared="isSharedSelectedModel"
      :sections="previewSections"
      :preview-rows="previewRows"
      :preview-columns="previewColumns"
      :preview-loading="loadingPreviewRows"
      :resolve-project-label="resolveProjectLabel"
      :preview-section-rows="previewSectionRows"
      :section-value="sectionValue"
      :format-display-value="formatDisplayValue"
      :actions="modelDetailActions"
    />

    <ModelSyncDialogs
      v-model:sync-dialog-open="syncDialogOpen"
      v-model:sync-task-dialog-open="syncTaskDialogOpen"
      v-model:selected-locators="syncSelectedLocators"
      :sync-form="syncForm"
      :sync-task-form="syncTaskForm"
      :syncing="syncing"
      :creating-sync-task="creatingSyncTask"
      :database-datasource-types="databaseDatasourceTypes"
      :sync-datasource-options="syncDatasourceOptions"
      :sync-task-datasource-options="syncTaskDatasourceOptions"
      :actions="syncDialogActions"
    />

    <ModelEditorDrawer
      v-model="editorOpen"
      :is-editing-model="isEditingModel"
      :panel-title="editorPanelTitle"
      :panel-description="editorPanelDescription"
      :form="modelForm"
      :saving="saving"
      :datasource-options="manualDatasourceOptions"
      :model-schemas="availableModelSchemas"
      :has-selected-model-schema="Boolean(selectedModelSchema)"
      :show-manual-datasource-hint="showManualDatasourceHint"
      :model-name-label="modelNameEditorLabel"
      :model-name-placeholder="modelNameEditorPlaceholder"
      :physical-locator-label="physicalLocatorEditorLabel"
      :physical-locator-placeholder="physicalLocatorEditorPlaceholder"
      :sections="editorSections"
      :dynamic-function-fields="fileModelDynamicFunctionFields"
      :actions="modelEditorActions"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import type {
  DataModelDefinition,
  DataModelIndexQueueStatusView,
  DataModelSaveRequest,
  DataSourceDefinition,
  EntityId,
  MetadataSchemaDefinition,
  ModelSyncTaskView,
} from "@studio/api-sdk";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import ModelDetailShell from "@/components/models/ModelDetailShell.vue";
import ModelEditorDrawer from "@/components/models/ModelEditorDrawer.vue";
import ModelIndexQueueCard from "@/components/models/ModelIndexQueueCard.vue";
import ModelListPanel from "@/components/models/ModelListPanel.vue";
import { isDatabaseDatasourceType, normalizeModelPagePayload, normalizeTypeCode, sameId, useModelMetadataSupport } from "@/components/models/modelMetadataSupport";
import { canDeleteSyncTask, canStopSyncTask, formatSyncTaskDurationMs, modelSyncTaskStatusOptions, normalizeModelListTab } from "@/components/models/modelSyncTaskSupport";
import ModelSyncDialogs from "@/components/models/ModelSyncDialogs.vue";
import type { MetaSectionBinding, ModelDynamicFilterActions, ModelFormState, ModelMetaSection, ModelQueryConditionState, ModelQueryGroupState, ModelSyncFormState, ModelSyncTaskFormState } from "@/components/models/modelViewTypes";
import ModelSyncTaskSection from "@/components/models/ModelSyncTaskSection.vue";
import { cloneDeep, isSharedFromAnotherProject, resolveProjectName } from "@/utils/studio";

const route = useRoute();
const router = useRouter();
const { t } = useI18n();
const authStore = useAuthStore();
const datasources = ref<DataSourceDefinition[]>([]);
const schemas = ref<MetadataSchemaDefinition[]>([]);
const models = ref<DataModelDefinition[]>([]);
const modelPagination = reactive({
  page: 1,
  pageSize: 20,
  total: 0,
});
const modelSortState = reactive({
  prop: "updatedAt",
  order: "descending" as "ascending" | "descending",
});
const previewRows = ref<Record<string, unknown>[]>([]);
const loadingPreviewRows = ref(false);
const queryGroups = ref<ModelQueryGroupState[]>([]);
const selectedDatasourceType = ref("");
const selectedDatasourceId = ref<EntityId>();
const selectedModel = ref<DataModelDefinition>();
const activeListTab = ref("models");
const activeDetailTab = ref("overview");
const editorOpen = ref(false);
const saving = ref(false);
const syncDialogOpen = ref(false);
const syncing = ref(false);
const syncSelectedLocators = ref<string[]>([]);
const syncTaskDialogOpen = ref(false);
const creatingSyncTask = ref(false);
const syncTasks = ref<ModelSyncTaskView[]>([]);
const loadingSyncTasksPage = ref(false);
const syncTaskTotal = ref(0);
const indexQueueStatus = ref<DataModelIndexQueueStatusView>();
const syncTaskPagination = reactive({
  page: 1,
  pageSize: 20,
});
const syncTaskFilters = reactive({
  datasourceType: "",
  datasourceId: undefined as EntityId | undefined,
  status: "",
});

const modelForm = reactive<ModelFormState>({
  name: "",
  physicalLocator: "",
  technicalMetadata: {},
  businessMetadata: {},
});

const syncForm = reactive<ModelSyncFormState>({
  datasourceType: "",
});

const syncTaskForm = reactive<ModelSyncTaskFormState>({
  datasourceType: "",
  selectedLocators: [],
});
const fileModelDynamicFunctionFields = ["rootPath", "partition", "fileName", "efile.dataTime", "efile.planDate"];

const detailModelId = computed(() => {
  const value = route.params.modelId;
  if (typeof value === "string" || typeof value === "number") {
    return value;
  }
  return undefined;
});
const isDetailPage = computed(() => detailModelId.value != null);
const previewColumns = computed(() => Object.keys(previewRows.value[0] ?? {}));
const modelTableEmptyText = computed(() =>
  selectedDatasourceId.value ? undefined : t("web.models.allModelsEmpty"),
);
const isEditingModel = computed(() => Boolean(modelForm.id));
const editorPanelTitle = computed(() => (isEditingModel.value ? t("web.models.editDialogTitle") : t("web.models.addDialogTitle")));
const editorPanelDescription = computed(() =>
  isEditingModel.value ? t("web.models.editDialogDescription") : t("web.models.addDialogDescription"),
);
const queryDatasourceTypes = computed(() =>
  Array.from(new Set(datasources.value.map((item) => item.typeCode).filter(Boolean))).sort(),
);
const filteredDatasourceOptions = computed(() =>
  datasources.value.filter((item) => !selectedDatasourceType.value || normalizeTypeCode(item.typeCode) === normalizeTypeCode(selectedDatasourceType.value)),
);
const selectedDatasource = computed(() => findDatasourceById(selectedDatasourceId.value));
const activeQueryDatasourceType = computed(() => selectedDatasource.value?.typeCode ?? selectedDatasourceType.value);
const editorDatasource = computed(() => findDatasourceById(modelForm.datasourceId));
const databaseDatasourceTypes = computed(() =>
  Array.from(new Set(datasources.value.filter((item) => isDatabaseDatasourceType(item.typeCode)).map((item) => item.typeCode))).sort(),
);
const syncDatasourceOptions = computed(() =>
  datasources.value.filter(
    (item) => isDatabaseDatasourceType(item.typeCode) && (!syncForm.datasourceType || item.typeCode === syncForm.datasourceType),
  ),
);
const syncTaskDatasourceOptions = computed(() =>
  datasources.value.filter(
    (item) => isDatabaseDatasourceType(item.typeCode)
      && (!syncTaskForm.datasourceType || item.typeCode === syncTaskForm.datasourceType),
  ),
);
const syncTaskFilterDatasourceOptions = computed(() =>
  datasources.value.filter(
    (item) => !syncTaskFilters.datasourceType || item.typeCode === syncTaskFilters.datasourceType,
  ),
);
const syncTaskStatusOptions = modelSyncTaskStatusOptions;
const indexQueueBusy = computed(() => Boolean(indexQueueStatus.value?.busy));
const indexQueuePendingRebuildCount = computed(() => Number(indexQueueStatus.value?.pendingRebuildCount ?? 0));
const indexQueueQueuedCommandCount = computed(() => Number(indexQueueStatus.value?.queuedCommandCount ?? 0));
const manualDatasourceOptions = computed(() =>
  datasources.value.filter((item) => {
    if (isEditingModel.value && sameId(item.id, modelForm.datasourceId)) {
      return true;
    }
    return !isDatabaseDatasourceType(item.typeCode);
  }),
);
const {
  availableModelSchemas,
  querySchemaOptions,
  selectedModelSchema,
  editorSections,
  previewSections,
  showManualDatasourceHint,
  modelNameEditorLabel,
  modelNameEditorPlaceholder,
  physicalLocatorEditorLabel,
  physicalLocatorEditorPlaceholder,
  filterModelSchemas,
  querySchemaLabel,
  querySchemaFields,
  queryConditionField,
  queryConditionOperators,
  queryConditionInputValue,
  queryConditionBooleanValue,
  setQueryConditionValue,
  setQueryConditionValueTo,
  isMultipleQuerySchema,
  isNumericQueryField,
  normalizeQueryGroupsForDatasource,
  appendQueryGroup,
  removeQueryGroup,
  handleQuerySchemaChange,
  appendQueryCondition,
  removeQueryCondition,
  handleQueryFieldChange,
  buildModelQueryRequest,
  appendSectionRow,
  editorSectionRows,
  resolveRowEditorComponent,
  resolveRowEditorProps,
  updateSectionRowField,
  removeSectionRow,
  sectionValue,
  sectionModelValue,
  updateSectionModelValue,
  previewSectionRows,
  formatDisplayValue,
  applyModelSchemaContext,
  resetModelForm,
} = useModelMetadataSupport({
  schemas,
  modelForm,
  selectedModel,
  selectedDatasource,
  editorDatasource,
  activeQueryDatasourceType,
  queryGroups,
  t,
});
const isSharedSelectedModel = computed(() =>
  isSharedFromAnotherProject(authStore.currentProjectId, selectedModel.value?.projectId),
);
const dynamicFilterActions: ModelDynamicFilterActions = {
  appendQueryGroup,
  searchModels,
  resetQueryFilters,
  handleQuerySchemaChange,
  appendQueryCondition,
  removeQueryGroup,
  querySchemaLabel,
  querySchemaFields,
  isMultipleQuerySchema,
  handleQueryFieldChange,
  queryConditionField,
  queryConditionOperators,
  queryConditionInputValue,
  queryConditionBooleanValue,
  setQueryConditionValue,
  setQueryConditionValueTo,
  isNumericQueryField,
  removeQueryCondition,
};
const modelListActions = {
  handleDatasourceTypeChange,
  handleDatasourceChange,
  openCreateDialog,
  openSyncDialog,
  rebuildQueryIndex,
  openStatisticsWorkspace: () => openStatisticsWorkspace(),
  refreshModels,
  openModelDetail,
  resolveDatasourceLabel,
  resolveProjectLabel,
  isSharedModel,
  buildModelActions,
  handleModelPageChange,
  handleModelPageSizeChange,
  handleModelSortChange,
};
const syncTaskSectionActions = {
  handleDatasourceTypeChange: handleSyncTaskDatasourceTypeChange,
  loadSyncTasks,
  openCreateSyncTaskDialog,
  openSyncTaskDetail,
  canStopSyncTask,
  canDeleteSyncTask,
  stopSyncTask,
  deleteSyncTask,
  handlePageSizeChange: handleSyncTaskPageSizeChange,
  formatDurationMs: (durationMs?: number) => formatSyncTaskDurationMs(durationMs, t("common.none")),
};
const syncDialogActions = {
  handleSyncDatasourceTypeChange,
  handleSyncDatasourceChange,
  handleSyncTaskFormDatasourceTypeChange,
  handleSyncTaskFormDatasourceChange,
  submitSync,
  submitSyncTaskCreate,
};
const modelEditorActions = {
  handleModelDatasourceChange,
  handleModelSchemaChange,
  appendSectionRow,
  editorSectionRows,
  resolveRowEditorComponent,
  resolveRowEditorProps,
  updateSectionRowField,
  removeSectionRow,
  sectionModelValue,
  updateSectionModelValue,
  saveModel,
};
const modelDetailActions = {
  goBackToList,
  openStatisticsWorkspace,
  refreshDetail,
  openDetailEdit,
};
let modelDetailLoadSeq = 0;
let previewLoadSeq = 0;

function findDatasourceById(datasourceId?: EntityId) {
  return datasources.value.find((item) => sameId(item.id, datasourceId));
}

function resolveProjectLabel(projectId?: EntityId | null) {
  return resolveProjectName(authStore.projects, projectId);
}

function isSharedModel(model: DataModelDefinition) {
  return isSharedFromAnotherProject(authStore.currentProjectId, model.projectId);
}

async function searchModels() {
  modelPagination.page = 1;
  await handleDatasourceChange();
}

async function resetQueryFilters() {
  queryGroups.value = [];
  modelPagination.page = 1;
  await handleDatasourceChange();
}

function handleModelPageChange(page: number) {
  modelPagination.page = page;
  void handleDatasourceChange();
}

function handleModelPageSizeChange(pageSize: number) {
  modelPagination.pageSize = pageSize;
  modelPagination.page = 1;
  void handleDatasourceChange();
}

function handleModelSortChange(sort: { prop?: string; order?: "ascending" | "descending" | null }) {
  modelSortState.prop = normalizeModelSortField(sort.prop);
  modelSortState.order = sort.order ?? "descending";
  modelPagination.page = 1;
  void handleDatasourceChange();
}

function openCreateDialog() {
  const prefillDatasourceId =
    selectedDatasource.value && !isDatabaseDatasourceType(selectedDatasource.value.typeCode) ? selectedDatasource.value.id : undefined;
  resetModelForm(prefillDatasourceId);
  editorOpen.value = true;
}

function editModel(model: DataModelDefinition) {
  modelForm.id = model.id;
  modelForm.datasourceId = model.datasourceId;
  modelForm.name = model.name;
  modelForm.physicalLocator = model.physicalLocator;
  modelForm.modelKind = model.modelKind;
  modelForm.schemaVersionId = model.schemaVersionId;
  modelForm.technicalMetadata = cloneDeep(model.technicalMetadata ?? {});
  modelForm.businessMetadata = cloneDeep(model.businessMetadata ?? {});
  applyModelSchemaContext({ resetMetadata: false, forceKind: false });
  editorOpen.value = true;
}

function handleModelDatasourceChange() {
  modelForm.schemaVersionId = undefined;
  modelForm.modelKind = undefined;
  applyModelSchemaContext({ resetMetadata: true, forceKind: true });
}

function handleModelSchemaChange() {
  applyModelSchemaContext({ resetMetadata: false, forceKind: true });
}

function openSyncDialog() {
  syncForm.datasourceType = "";
  syncForm.datasourceId = undefined;
  syncSelectedLocators.value = [];
  syncDialogOpen.value = true;
}

function handleSyncDatasourceTypeChange() {
  syncForm.datasourceId = undefined;
  syncSelectedLocators.value = [];
}

function handleSyncDatasourceChange() {
  syncSelectedLocators.value = [];
}

function openCreateSyncTaskDialog() {
  syncTaskForm.datasourceType = "";
  syncTaskForm.datasourceId = undefined;
  syncTaskForm.selectedLocators = [];
  syncTaskDialogOpen.value = true;
}

function handleSyncTaskFormDatasourceTypeChange() {
  syncTaskForm.datasourceId = undefined;
  syncTaskForm.selectedLocators = [];
}

function handleSyncTaskFormDatasourceChange() {
  syncTaskForm.selectedLocators = [];
}

function handleSyncTaskDatasourceTypeChange() {
  syncTaskFilters.datasourceId = undefined;
  syncTaskPagination.page = 1;
  void loadSyncTasks();
}

function handleSyncTaskPageSizeChange(pageSize: number) {
  syncTaskPagination.pageSize = pageSize;
  syncTaskPagination.page = 1;
  void loadSyncTasks();
}

async function loadSyncTasks() {
  if (!authStore.currentProjectId) {
    syncTasks.value = [];
    syncTaskTotal.value = 0;
    return;
  }
  loadingSyncTasksPage.value = true;
  try {
    const result = await studioApi.modelSyncTasks.list({
      pageNo: syncTaskPagination.page,
      pageSize: syncTaskPagination.pageSize,
      datasourceType: syncTaskFilters.datasourceType || undefined,
      datasourceId: syncTaskFilters.datasourceId,
      status: syncTaskFilters.status || undefined,
    });
    syncTasks.value = result.items;
    syncTaskTotal.value = result.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载同步任务失败");
  } finally {
    loadingSyncTasksPage.value = false;
  }
}

async function loadIndexQueueStatus(showError = false) {
  try {
    indexQueueStatus.value = await studioApi.models.indexQueueStatus();
  } catch (error) {
    if (showError) {
      ElMessage.error(error instanceof Error ? error.message : "加载索引队列状态失败");
    }
  }
}

function openSyncTaskDetail(task: ModelSyncTaskView) {
  if (!task.id) {
    return;
  }
  router.push({
    name: "model-sync-task-detail",
    params: { taskId: String(task.id) },
  });
}

async function stopSyncTask(task: ModelSyncTaskView) {
  if (!task.id || !canStopSyncTask(task)) {
    return;
  }
  try {
    await studioApi.modelSyncTasks.stop(task.id);
    ElMessage.success("已请求停止同步任务");
    await loadSyncTasks();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "停止同步任务失败");
  }
}

async function deleteSyncTask(task: ModelSyncTaskView) {
  if (!task.id || !canDeleteSyncTask(task)) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `删除同步任务“${task.name}”吗？已同步的模型不会回滚。`,
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.modelSyncTasks.delete(task.id);
    ElMessage.success("同步任务已删除");
    await loadSyncTasks();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除同步任务失败");
    }
  }
}

async function loadPage() {
  try {
    const [datasourceData, schemaData] = await Promise.all([
      studioApi.datasources.list(),
      studioApi.metaSchemas.list(),
    ]);
    datasources.value = datasourceData;
    schemas.value = schemaData;
    if (selectedDatasourceType.value && !queryDatasourceTypes.value.some((item) => normalizeTypeCode(item) === normalizeTypeCode(selectedDatasourceType.value))) {
      selectedDatasourceType.value = "";
    }
    if (selectedDatasourceId.value && !datasourceData.some((item) => sameId(item.id, selectedDatasourceId.value))) {
      selectedDatasourceId.value = undefined;
      models.value = [];
      selectedModel.value = undefined;
      previewRows.value = [];
    }
    if (selectedModel.value?.datasourceId && !sameId(selectedDatasourceId.value, selectedModel.value.datasourceId)) {
      selectedDatasourceId.value = selectedModel.value.datasourceId;
    }
    const activeDatasource = findDatasourceById(selectedDatasourceId.value);
    if (activeDatasource?.typeCode) {
      selectedDatasourceType.value = activeDatasource.typeCode;
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.loadDatasourcesFailed"));
  }
}

async function loadModelsForSelectedDatasource() {
  selectedModel.value = undefined;
  previewRows.value = [];
  const queryRequest = buildModelQueryRequest();
  queryRequest.sortField = modelSortState.prop;
  queryRequest.sortOrder = modelSortState.order;
  const sortParams = {
    sortField: modelSortState.prop,
    sortOrder: modelSortState.order,
  };
  const payload = queryRequest.groups.length > 0
    ? await studioApi.models.queryPage(queryRequest, {
      pageNo: modelPagination.page,
      pageSize: modelPagination.pageSize,
    })
    : (selectedDatasourceId.value
      ? await studioApi.models.listByDatasourcePage(selectedDatasourceId.value, {
        pageNo: modelPagination.page,
        pageSize: modelPagination.pageSize,
        ...sortParams,
      })
      : await studioApi.models.listPage({
        datasourceType: selectedDatasourceType.value || undefined,
        pageNo: modelPagination.page,
        pageSize: modelPagination.pageSize,
        ...sortParams,
      }));
  const result = normalizeModelPagePayload(payload);
  models.value = result.items;
  modelPagination.total = Number(result.total ?? 0);
}

function normalizeModelSortField(value?: string) {
  if (value === "name"
    || value === "projectId"
    || value === "updatedAt"
    || value === "createdAt"
    || value === "id") {
    return value;
  }
  return "updatedAt";
}

async function handleDatasourceTypeChange() {
  if (selectedDatasourceId.value) {
    const datasource = findDatasourceById(selectedDatasourceId.value);
    if (datasource && selectedDatasourceType.value && normalizeTypeCode(datasource.typeCode) !== normalizeTypeCode(selectedDatasourceType.value)) {
      selectedDatasourceId.value = undefined;
    }
  }
  modelPagination.page = 1;
  await handleDatasourceChange();
}

async function handleDatasourceChange() {
  try {
    const datasource = findDatasourceById(selectedDatasourceId.value);
    if (datasource?.typeCode) {
      selectedDatasourceType.value = datasource.typeCode;
    }
    normalizeQueryGroupsForDatasource();
    await loadModelsForSelectedDatasource();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.loadModelsFailed"));
  }
}

async function refreshModels() {
  await loadPage();
  await loadIndexQueueStatus();
  await handleDatasourceChange();
}

async function rebuildQueryIndex() {
  try {
    await studioApi.models.rebuildIndex(selectedDatasourceId.value);
    ElMessage.success(t("web.models.rebuildIndexSuccess"));
    await handleDatasourceChange();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.rebuildIndexFailed"));
  }
}

function resolveDatasourceLabel(datasourceId?: EntityId) {
  const datasource = findDatasourceById(datasourceId);
  if (!datasource) {
    return "-";
  }
  return `${datasource.name} (${datasource.typeCode})`;
}

async function selectModel(model: DataModelDefinition) {
  selectedModel.value = model;
  if (model.datasourceId) {
    selectedDatasourceId.value = model.datasourceId;
  }
  const datasource = findDatasourceById(model.datasourceId);
  if (datasource?.typeCode) {
    selectedDatasourceType.value = datasource.typeCode;
  }
  if (!model.id) {
    previewRows.value = [];
    loadingPreviewRows.value = false;
    return;
  }
  void loadModelPreviewRows(model.id);
}

async function loadModelPreviewRows(modelId: EntityId) {
  const seq = ++previewLoadSeq;
  previewRows.value = [];
  loadingPreviewRows.value = true;
  try {
    const rows = await studioApi.models.preview(modelId);
    if (seq === previewLoadSeq && sameId(selectedModel.value?.id, modelId)) {
      previewRows.value = rows;
    }
  } catch (error) {
    if (seq === previewLoadSeq && sameId(selectedModel.value?.id, modelId)) {
      ElMessage.error(error instanceof Error ? error.message : t("web.models.previewFailed"));
    }
  } finally {
    if (seq === previewLoadSeq) {
      loadingPreviewRows.value = false;
    }
  }
}

function openModelDetail(model: DataModelDefinition, edit = false) {
  if (!model.id) {
    return;
  }
  router.push({
    name: "model-detail",
    params: { modelId: String(model.id) },
    query: edit ? { edit: "1" } : undefined,
  });
}

function openStatisticsWorkspace(datasourceId?: EntityId) {
  const activeDatasource = datasourceId == null ? selectedDatasource.value : findDatasourceById(datasourceId);
  router.push({
    name: "statistics",
    query: {
      ...(datasourceId == null ? {} : { datasourceId: String(datasourceId) }),
      ...(activeDatasource?.typeCode ? { datasourceType: activeDatasource.typeCode } : {}),
    },
  });
}

function goBackToList() {
  editorOpen.value = false;
  router.push({ name: "models" });
}

function openDetailEdit() {
  if (!selectedModel.value) {
    return;
  }
  editModel(selectedModel.value);
}

async function loadModelDetail() {
  if (!detailModelId.value) {
    modelDetailLoadSeq += 1;
    selectedModel.value = undefined;
    previewRows.value = [];
    loadingPreviewRows.value = false;
    previewLoadSeq += 1;
    return;
  }
  const seq = ++modelDetailLoadSeq;
  try {
    const model = await studioApi.models.get(detailModelId.value, { studioSkipGlobalLoading: true });
    if (seq !== modelDetailLoadSeq) {
      return;
    }
    selectModel(model);
    if (route.query.edit === "1") {
      editModel(model);
      const nextQuery = { ...route.query };
      delete nextQuery.edit;
      router.replace({ name: "model-detail", params: { modelId: String(detailModelId.value) }, query: nextQuery });
    }
  } catch (error) {
    if (seq === modelDetailLoadSeq) {
      ElMessage.error(error instanceof Error ? error.message : t("web.models.loadModelDetailFailed"));
    }
  }
}

async function refreshDetail() {
  await loadPage();
  await loadModelDetail();
}

async function saveModel() {
  if (!modelForm.datasourceId) {
    ElMessage.warning(t("web.models.saveSelectDatasourceFirst"));
    return;
  }
  if (!isEditingModel.value && editorDatasource.value && isDatabaseDatasourceType(editorDatasource.value.typeCode)) {
    ElMessage.warning(t("web.models.manualDatasourceOnly"));
    return;
  }
  if (!isEditingModel.value && !selectedModelSchema.value) {
    ElMessage.warning(t("web.models.noModelSchema"));
    return;
  }
  saving.value = true;
  try {
    const payload: DataModelSaveRequest = {
      id: modelForm.id,
      datasourceId: modelForm.datasourceId,
      name: modelForm.name,
      physicalLocator: modelForm.physicalLocator,
      modelKind: modelForm.modelKind,
      schemaVersionId: modelForm.schemaVersionId,
      technicalMetadata: cloneDeep(modelForm.technicalMetadata),
      businessMetadata: cloneDeep(modelForm.businessMetadata),
    };
    const saved = await studioApi.models.save(payload);
    editorOpen.value = false;
    selectedDatasourceId.value = saved.datasourceId;
    const datasource = findDatasourceById(saved.datasourceId);
    if (datasource?.typeCode) {
      selectedDatasourceType.value = datasource.typeCode;
    }
    ElMessage.success(t("web.models.saveSuccess"));
    if (isDetailPage.value) {
      goBackToList();
    } else {
      modelPagination.page = 1;
      await handleDatasourceChange();
      const current = models.value.find((item) => sameId(item.id, saved.id));
      if (current) {
        await selectModel(current);
      }
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.saveFailed"));
  } finally {
    saving.value = false;
  }
}

async function submitSync() {
  if (!syncForm.datasourceId) {
    ElMessage.warning(t("web.models.syncSelectDatasourceFirst"));
    return;
  }
  if (syncSelectedLocators.value.length === 0) {
    ElMessage.warning(t("web.models.syncNoSelection"));
    return;
  }
  if (syncSelectedLocators.value.length > 20) {
    try {
      await ElMessageBox.confirm(
        "当前选择的表超过 20 张，页面同步耗时较长，将自动创建模型同步任务并立即执行。",
        t("common.confirm"),
        { type: "warning" },
      );
      const created = await studioApi.modelSyncTasks.create({
        datasourceId: syncForm.datasourceId,
        physicalLocators: cloneDeep(syncSelectedLocators.value),
        source: "AUTO_PAGE",
      });
      syncDialogOpen.value = false;
      activeListTab.value = "sync-tasks";
      await loadSyncTasks();
      if (created.id) {
        openSyncTaskDetail(created);
      }
      return;
    } catch (error) {
      if (error !== "cancel") {
        ElMessage.error(error instanceof Error ? error.message : "创建同步任务失败");
      }
      return;
    }
  }
  syncing.value = true;
  try {
    selectedDatasourceId.value = syncForm.datasourceId;
    const datasource = findDatasourceById(syncForm.datasourceId);
    if (datasource?.typeCode) {
      selectedDatasourceType.value = datasource.typeCode;
    }
    await studioApi.models.syncSelected(syncForm.datasourceId, {
      physicalLocators: cloneDeep(syncSelectedLocators.value),
    });
    modelPagination.page = 1;
    syncDialogOpen.value = false;
    ElMessage.success(t("web.models.syncSuccess"));
    await handleDatasourceChange();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.syncFailed"));
  } finally {
    syncing.value = false;
  }
}

async function submitSyncTaskCreate() {
  if (!syncTaskForm.datasourceId) {
    ElMessage.warning("请先选择数据源");
    return;
  }
  if (syncTaskForm.selectedLocators.length === 0) {
    ElMessage.warning(t("web.models.syncNoSelection"));
    return;
  }
  creatingSyncTask.value = true;
  try {
    const created = await studioApi.modelSyncTasks.create({
      datasourceId: syncTaskForm.datasourceId,
      physicalLocators: cloneDeep(syncTaskForm.selectedLocators),
      source: "MANUAL",
    });
    syncTaskDialogOpen.value = false;
    activeListTab.value = "sync-tasks";
    await loadSyncTasks();
    if (created.id) {
      openSyncTaskDetail(created);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "创建同步任务失败");
  } finally {
    creatingSyncTask.value = false;
  }
}

async function deleteModel(model: DataModelDefinition) {
  if (!model.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.models.deleteConfirmMessage", { name: model.name }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.models.delete(model.id);
    if (selectedModel.value?.id === model.id) {
      selectedModel.value = undefined;
      previewRows.value = [];
    }
    ElMessage.success(t("web.models.deleteSuccess"));
    if (isDetailPage.value && sameId(detailModelId.value, model.id)) {
      goBackToList();
      return;
    }
    await handleDatasourceChange();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.models.deleteFailed"));
    }
  }
}

function buildModelActions(model: DataModelDefinition) {
  const shared = isSharedModel(model);
  return [
    {
      key: "statistics",
      label: t("common.statistics"),
      onClick: () => openStatisticsWorkspace(model.datasourceId),
    },
    {
      key: "edit",
      label: t("common.edit"),
      type: "primary",
      disabled: shared,
      onClick: () => openModelDetail(model, true),
    },
    {
      key: "delete",
      label: t("common.delete"),
      type: "danger",
      disabled: shared,
      onClick: () => deleteModel(model),
    },
  ];
}

watch(
  () => route.query.tab,
  (value) => {
    if (!isDetailPage.value) {
      activeListTab.value = normalizeModelListTab(value);
    }
  },
  { immediate: true },
);

watch(
  activeListTab,
  async (value) => {
    if (isDetailPage.value) {
      return;
    }
    const normalized = normalizeModelListTab(value);
    if (route.query.tab !== normalized) {
      await router.replace({
        name: "models",
        query: normalized === "sync-tasks" ? { tab: normalized } : {},
      });
    }
    if (normalized === "sync-tasks") {
      await loadSyncTasks();
      return;
    }
  },
  { immediate: true },
);

watch(
  () => [route.params.modelId, route.fullPath, authStore.currentTenantId, authStore.currentProjectId],
  async () => {
    await loadPage();
    if (isDetailPage.value) {
      activeDetailTab.value = "overview";
      await loadModelDetail();
      return;
    }
    await loadIndexQueueStatus();
    if (activeListTab.value === "sync-tasks") {
      await loadSyncTasks();
      return;
    }
    await handleDatasourceChange();
  },
  { immediate: true },
);
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.models-tabs {
  display: grid;
  gap: 12px;
}

</style>
