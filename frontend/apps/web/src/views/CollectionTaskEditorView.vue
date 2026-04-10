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
      </div>

      <div class="soft-panel">
        <div class="section-toolbar">
          <div>
            <strong>{{ t("web.collectionTasks.sourcesTitle") }}</strong>
            <p>{{ t("web.collectionTasks.sourcesDescription") }}</p>
          </div>
          <el-button type="primary" plain @click="appendSourceBinding">{{ t("common.addRow") }}</el-button>
        </div>

        <el-table :data="form.sourceBindings" border>
          <el-table-column :label="t('web.collectionTasks.sourceAlias')" min-width="150">
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

          <el-table-column :label="t('fieldMapping.actions')" width="110">
            <template #default="{ $index }">
              <el-button link type="danger" :disabled="form.sourceBindings.length === 1" @click="removeSourceBinding($index)">{{ t("common.remove") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
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

      <CollectionTaskFieldMappingEditor
        :model-value="form.fieldMappings"
        :source-aliases="sourceAliasOptions"
        :source-field-options-by-alias="sourceFieldOptionsByAlias"
        :target-fields="targetFieldOptions"
        :rule-options="fieldMappingRules"
        @update:model-value="form.fieldMappings = $event"
      />

      <div class="studio-form-grid">
        <el-form-item :label="t('web.collectionTasks.writeMode')">
          <el-select v-model="writeMode">
            <el-option label="insert" value="insert" />
            <el-option label="replace" value="replace" />
            <el-option label="update" value="update" />
          </el-select>
        </el-form-item>
      </div>
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
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  CollectionTaskDefinitionView,
  JobContainerConfig,
  CollectionTaskSaveRequest,
  CollectionTaskSourceBinding,
  DataModelDefinition,
  DataSourceDefinition,
  FieldMappingRuleView,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import CollectionTaskFieldMappingEditor from "@web/components/CollectionTaskFieldMappingEditor.vue";
import CronExpressionPicker from "@web/components/CronExpressionPicker.vue";
import { cloneDeep, prettyJson } from "@/utils/studio";

interface CollectionTaskEditorForm extends Omit<CollectionTaskSaveRequest, "schedule"> {
  schedule: NonNullable<CollectionTaskSaveRequest["schedule"]>;
}

const { t } = useI18n();
const route = useRoute();
const router = useRouter();

const taskId = computed(() => route.params.taskId as string | undefined);
const activeStep = ref(1);
const datasources = ref<DataSourceDefinition[]>([]);
const fieldMappingRules = ref<FieldMappingRuleView[]>([]);
const modelCache = ref<Record<string, DataModelDefinition[]>>({});
const saving = ref(false);
const previewLoading = ref(false);
const previewDirty = ref(true);
const previewConfig = ref<JobContainerConfig | null>(null);

const form = reactive<CollectionTaskEditorForm>({
  name: "",
  sourceBindings: [
    {
      sourceAlias: "src1",
      datasourceId: "",
      modelId: "",
    },
  ],
  targetBinding: {
    datasourceId: "",
    modelId: "",
  },
  fieldMappings: [],
  executionOptions: {
    joinKeys: [],
    joinType: "LEFT",
    writeMode: "insert",
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
const targetFieldOptions = computed(() => resolveFieldsByModelId(form.targetBinding.modelId));
const canPreviewConfig = computed(() =>
  form.sourceBindings.length > 0
  && form.sourceBindings.every((item) => Boolean(item.datasourceId) && Boolean(item.modelId) && Boolean(item.sourceAlias?.trim()))
  && Boolean(form.targetBinding.datasourceId)
  && Boolean(form.targetBinding.modelId),
);
const sourceFieldOptionsByAlias = computed<Record<string, string[]>>(() => {
  const options: Record<string, string[]> = {};
  for (const source of form.sourceBindings) {
    if (source.sourceAlias) {
      options[source.sourceAlias] = resolveFieldsByModelId(source.modelId);
    }
  }
  return options;
});
const commonJoinKeyOptions = computed(() => {
  const sourceFields = form.sourceBindings
    .map((item) => resolveFieldsByModelId(item.modelId))
    .filter((item) => item.length > 0);
  if (!sourceFields.length) {
    return [];
  }
  return sourceFields.reduce((result, current) => result.filter((field) => current.includes(field)));
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

const writeMode = computed<string>({
  get() {
    return String(form.executionOptions.writeMode ?? "insert");
  },
  set(value) {
    form.executionOptions = {
      ...form.executionOptions,
      writeMode: value,
    };
  },
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
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.loadFailed"));
  }
}

function applyTask(task: CollectionTaskDefinitionView) {
  form.id = task.id;
  form.name = task.name;
  form.sourceBindings = cloneDeep(task.sourceBindings);
  form.targetBinding = cloneDeep(task.targetBinding ?? { datasourceId: "", modelId: "" });
  form.fieldMappings = cloneDeep(task.fieldMappings ?? []);
  form.executionOptions = cloneDeep(task.executionOptions ?? { joinKeys: [], joinType: "LEFT", writeMode: "insert" });
  form.schedule = cloneDeep(task.schedule ?? { enabled: false, cronExpression: "0 */30 * * * ?", timezone: "Asia/Shanghai" });
}

function appendSourceBinding() {
  form.sourceBindings.push({
    sourceAlias: `src${form.sourceBindings.length + 1}`,
    datasourceId: "",
    modelId: "",
  });
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

function resolveFieldsByModelId(modelId: unknown) {
  if (!modelId) {
    return [];
  }
  const allModels: DataModelDefinition[] = [];
  Object.values(modelCache.value).forEach((items) => {
    allModels.push(...items);
  });
  const model = allModels.find((item) => String(item.id) === String(modelId));
  const columns = model?.technicalMetadata?.columns;
  if (!Array.isArray(columns)) {
    return [];
  }
  return columns
    .map((item) => (typeof item === "object" && item ? String((item as Record<string, unknown>).name ?? "") : ""))
    .filter(Boolean);
}

async function handleSourceDatasourceChange(row: CollectionTaskSourceBinding, value: string) {
  row.datasourceId = value;
  row.modelId = "";
  await ensureModels(value);
}

function handleSourceModelChange(row: CollectionTaskSourceBinding, value: string) {
  row.modelId = value;
}

async function handleTargetDatasourceChange(value: string) {
  form.targetBinding.datasourceId = value;
  form.targetBinding.modelId = "";
  await ensureModels(value);
}

function handleTargetModelChange(value: string) {
  form.targetBinding.modelId = value;
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
    previewConfig.value = await studioApi.collectionTasks.preview(cloneDeep(form));
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
    const saved = await studioApi.collectionTasks.save(cloneDeep(form));
    ElMessage.success(t("web.collectionTasks.saveSuccess"));
    applyTask(saved);
    await router.push("/collection-tasks");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.saveFailed"));
  } finally {
    saving.value = false;
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
  if (value === 4 && previewDirty.value) {
    await loadPreviewConfig();
  }
}, { immediate: true });

onMounted(loadReferenceData);
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
