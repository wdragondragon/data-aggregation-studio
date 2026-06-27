<template>
  <div class="lineage-panel">
    <SectionCard
      :title="t('web.models.lineageSummaryTitle')"
      :description="t('web.models.lineageSummaryDescription')"
    >
      <div class="lineage-summary-grid">
        <div v-for="item in summaryCards" :key="item.key" class="lineage-summary-card">
          <span class="lineage-summary-card__label">{{ item.label }}</span>
          <strong class="lineage-summary-card__value">{{ item.value }}</strong>
        </div>
      </div>
    </SectionCard>

    <SectionCard
      :title="t('web.models.lineageGraphTitle')"
      :description="t('web.models.lineageGraphDescription')"
    >
      <template #actions>
        <div class="lineage-toolbar">
          <div class="lineage-toolbar__primary">
            <el-button
              v-if="activeLevel !== 'DATABASE'"
              type="primary"
              plain
              @click="openCreateManualRelation"
            >
              {{ t("web.models.lineageCreateRelation") }}
            </el-button>
            <div class="lineage-level-switch" role="tablist" :aria-label="t('web.models.lineageLevelSwitchLabel')">
              <button
                v-for="item in levelOptions"
                :key="item.value"
                type="button"
                class="lineage-level-switch__option"
                :class="{ 'lineage-level-switch__option--active': activeLevel === item.value }"
                @click="switchLevel(item.value)"
              >
                {{ item.label }}
              </button>
            </div>
          </div>
          <div class="lineage-toolbar__actions">
            <div class="lineage-legend">
              <div v-for="item in legendItems" :key="item.key" class="lineage-legend__item">
                <span class="lineage-legend__swatch" :class="`lineage-legend__swatch--${item.key}`">
                  <span class="lineage-legend__swatch-line"></span>
                </span>
                <span>{{ item.label }}</span>
              </div>
            </div>
          </div>
        </div>
      </template>

      <div v-loading="loading">
        <ModelLineageGraph
          :key="graphRenderKey"
          :nodes="currentLineage.nodes"
          :edges="currentLineage.edges"
          :level="activeLevel"
          :empty-text="t('web.models.lineageEmpty')"
          @select-edge="openEdgeDetail"
        />
      </div>
    </SectionCard>

    <SectionCard
      v-if="activeLevel === 'FIELD' && currentLineage.unresolvedExpressions.length > 0"
      :title="t('web.models.lineageUnresolvedTitle')"
      :description="t('web.models.lineageUnresolvedDescription')"
    >
      <StudioTableShell min-width="820px">
        <el-table :data="currentLineage.unresolvedExpressions" border>
        <el-table-column prop="collectionTaskName" :label="t('web.models.lineageUnresolvedTask')" min-width="180" />
        <el-table-column prop="targetField" :label="t('web.models.lineageUnresolvedTargetField')" min-width="160" />
        <el-table-column prop="expression" :label="t('web.models.lineageUnresolvedExpression')" min-width="260" show-overflow-tooltip />
        <el-table-column :label="t('web.models.lineageContributorStatus')" width="140" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="formatDisplayStatus(row.latestRunStatus)" :tone="displayStatusTone(row.latestRunStatus)" />
          </template>
        </el-table-column>
        </el-table>
      </StudioTableShell>
    </SectionCard>

    <ModelLineageEdgeDrawer
      v-model="edgeDrawerOpen"
      :edge-loading="edgeLoading"
      :edge-detail="edgeDetail"
      :active-level="activeLevel"
      :edge-actions="edgeDrawerActions"
    />

    <el-dialog
      :model-value="manualDialogOpen"
      :title="manualDialogTitle"
      width="720px"
      append-to-body
      destroy-on-close
      @close="manualDialogOpen = false"
    >
      <el-form label-position="top" class="lineage-form" @submit.prevent>
        <el-form-item :label="t('web.models.lineageManualSourceModel')">
          <el-select
            v-model="manualForm.sourceModelId"
            filterable
            remote
            remote-show-suffix
            class="lineage-form__control"
            :loading="loadingModelOptions"
            :remote-method="searchManualModelOptions"
            :placeholder="t('web.models.lineageManualSourceModelPlaceholder')"
          >
            <el-option v-for="item in manualModelOptions" :key="String(item.id)" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('web.models.lineageManualTargetModel')">
          <el-select
            v-model="manualForm.targetModelId"
            filterable
            remote
            remote-show-suffix
            class="lineage-form__control"
            :loading="loadingModelOptions"
            :remote-method="searchManualModelOptions"
            :placeholder="t('web.models.lineageManualTargetModelPlaceholder')"
          >
            <el-option v-for="item in manualModelOptions" :key="`target-${String(item.id)}`" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>

        <template v-if="manualForm.level === 'FIELD'">
          <el-form-item :label="t('web.models.lineageManualSourceField')">
            <el-select
              v-model="manualForm.sourceFieldKey"
              filterable
              class="lineage-form__control"
              :placeholder="t('web.models.lineageManualSourceFieldPlaceholder')"
            >
              <el-option v-for="item in sourceFieldOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>

          <el-form-item :label="t('web.models.lineageManualTargetField')">
            <el-select
              v-model="manualForm.targetFieldKey"
              filterable
              class="lineage-form__control"
              :placeholder="t('web.models.lineageManualTargetFieldPlaceholder')"
            >
              <el-option v-for="item in targetFieldOptions" :key="`target-field-${item.value}`" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
        </template>
      </el-form>

      <template #footer>
        <el-button @click="manualDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="manualSaving" @click="submitManualRelation">
          {{ t("common.confirm") }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import type { DataModelDefinition, DataModelLineageContributorView, DataModelLineageEdgeDetailView, DataModelLineageLevel, DataModelLineageView, DataModelManualLineageSaveRequest, DataModelOptionView, EntityId } from "@studio/api-sdk";
import { SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import ModelLineageGraph from "@/components/ModelLineageGraph.vue";
import ModelLineageEdgeDrawer from "@/components/model-lineage/ModelLineageEdgeDrawer.vue";
import { studioApi } from "@/api/studio";

const props = defineProps<{
  modelId?: EntityId;
  model?: DataModelDefinition;
}>();

const { t } = useI18n();
const router = useRouter();
const MODEL_OPTION_PAGE_SIZE = 50;

const activeLevel = ref<DataModelLineageLevel>("DATABASE");
const loading = ref(false);
const edgeLoading = ref(false);
const edgeDrawerOpen = ref(false);
const edgeDetail = ref<DataModelLineageEdgeDetailView>();
const currentModel = ref<DataModelDefinition>();
const availableModels = ref<DataModelOptionView[]>([]);
const modelDetailCache = ref<Record<string, DataModelDefinition>>({});
const loadingModelOptions = ref(false);
const modelOptionSearchSeq = ref(0);
const manualDialogOpen = ref(false);
const manualSaving = ref(false);

const lineageCache = reactive<Record<DataModelLineageLevel, DataModelLineageView>>({
  DATABASE: emptyLineageView(),
  TABLE: emptyLineageView(),
  FIELD: emptyLineageView(),
});

const manualForm = reactive<{
  relationId?: EntityId;
  level: DataModelLineageLevel;
  sourceModelId?: EntityId;
  targetModelId?: EntityId;
  sourceFieldKey?: string;
  targetFieldKey?: string;
}>({
  level: "TABLE",
  sourceModelId: undefined,
  targetModelId: undefined,
  sourceFieldKey: undefined,
  targetFieldKey: undefined,
});

const currentLineage = computed(() => lineageCache[activeLevel.value] ?? emptyLineageView());
const graphRenderKey = computed(() => `${activeLevel.value}:${currentLineage.value.nodes.length}:${currentLineage.value.edges.length}`);
const levelOptions = computed(() => [
  { value: "DATABASE" as const, label: t("web.models.lineageLevelDatabaseCard") },
  { value: "TABLE" as const, label: t("web.models.lineageLevelTableCard") },
  { value: "FIELD" as const, label: t("web.models.lineageLevelFieldCard") },
]);
const summaryCards = computed(() => {
  const summary = calculateDisplayedSummary(currentLineage.value, activeLevel.value);
  return [
    { key: "upstreamDepth", label: t("web.models.lineageUpstreamDepth"), value: Number(summary.upstreamDepth ?? 0) },
    { key: "totalUpstream", label: t("web.models.lineageTotalUpstream"), value: Number(summary.totalUpstreamCount ?? 0) },
    { key: "directUpstream", label: t("web.models.lineageDirectUpstream"), value: Number(summary.directUpstreamCount ?? 0) },
    { key: "downstreamDepth", label: t("web.models.lineageDownstreamDepth"), value: Number(summary.downstreamDepth ?? 0) },
    { key: "totalDownstream", label: t("web.models.lineageTotalDownstream"), value: Number(summary.totalDownstreamCount ?? 0) },
    { key: "directDownstream", label: t("web.models.lineageDirectDownstream"), value: Number(summary.directDownstreamCount ?? 0) },
  ];
});
const legendItems = computed(() => [
  { key: "automatic", label: `${t("web.models.lineageLegendAutomatic")} · ${t("web.models.lineageStatusNormal")}` },
  { key: "manual", label: `${t("web.models.lineageLegendManual")} · ${t("web.models.lineageStatusNormal")}` },
  { key: "mixed", label: `${t("web.models.lineageLegendAutomatic")} + ${t("web.models.lineageLegendManual")}` },
]);
const manualDialogTitle = computed(() => (manualForm.relationId != null ? t("web.models.lineageEditRelation") : t("web.models.lineageCreateRelation")));
const manualModelOptions = computed(() => {
  const items = new Map<string, DataModelOptionView | DataModelDefinition>();
  for (const item of availableModels.value) {
    const id = normalizeId(item.id);
    items.set(id, modelDetailCache.value[id] ?? item);
  }
  if (currentModel.value?.id != null) {
    items.set(normalizeId(currentModel.value.id), currentModel.value);
  }
  return Array.from(items.values()).sort((left, right) => String(left.name || "").localeCompare(String(right.name || "")));
});
const sourceFieldOptions = computed(() => extractFieldOptions(findModelById(manualForm.sourceModelId)));
const targetFieldOptions = computed(() => extractFieldOptions(findModelById(manualForm.targetModelId)));

const edgeDrawerActions = {
  formatSourceTypeLabel,
  formatDisplayStatus,
  displayStatusTone,
  formatManualContributorTitle,
  jumpToPath,
  openEditManualRelation,
  deleteManualRelation,
};

watch(
  () => props.model,
  (value) => {
    currentModel.value = value ? { ...value } : currentModel.value;
  },
  { immediate: true },
);

watch(
  () => props.modelId,
  () => {
    edgeDrawerOpen.value = false;
    edgeDetail.value = undefined;
    manualDialogOpen.value = false;
    void loadCurrentModel();
    void loadLineageBundle();
  },
  { immediate: true },
);

watch(
  () => activeLevel.value,
  () => {
    edgeDrawerOpen.value = false;
    edgeDetail.value = undefined;
  },
);

watch(
  () => [manualForm.sourceModelId, manualForm.level],
  async () => {
    if (manualForm.level === "FIELD") {
      await ensureManualModelDetail(manualForm.sourceModelId);
      const options = sourceFieldOptions.value;
      if (!options.some((item) => item.value === manualForm.sourceFieldKey)) {
        manualForm.sourceFieldKey = options[0]?.value;
      }
    } else {
      manualForm.sourceFieldKey = undefined;
    }
  },
);

watch(
  () => [manualForm.targetModelId, manualForm.level],
  async () => {
    if (manualForm.level === "FIELD") {
      await ensureManualModelDetail(manualForm.targetModelId);
      const options = targetFieldOptions.value;
      if (!options.some((item) => item.value === manualForm.targetFieldKey)) {
        manualForm.targetFieldKey = options[0]?.value;
      }
    } else {
      manualForm.targetFieldKey = undefined;
    }
  },
);

function emptyLineageView(): DataModelLineageView {
  return {
    editable: false,
    summary: {
      upstreamDepth: 0,
      totalUpstreamCount: 0,
      directUpstreamCount: 0,
      downstreamDepth: 0,
      totalDownstreamCount: 0,
      directDownstreamCount: 0,
    },
    nodes: [],
    edges: [],
    unresolvedExpressions: [],
  };
}

function normalizeId(value?: EntityId) {
  return value == null ? "" : String(value);
}

function normalizeLineageFieldKey(value?: string) {
  return String(value ?? "").trim().toLowerCase();
}

function findModelById(modelId?: EntityId) {
  const id = normalizeId(modelId);
  if (!id) {
    return undefined;
  }
  return manualModelOptions.value.find((item) => normalizeId(item.id) === id);
}

function parseObjectRows(value: unknown) {
  if (!Array.isArray(value)) {
    return [] as Record<string, unknown>[];
  }
  return value
    .filter((item) => item && typeof item === "object" && !Array.isArray(item))
    .map((item) => ({ ...(item as Record<string, unknown>) }));
}

function pickFieldRows(model?: DataModelOptionView | DataModelDefinition) {
  const metadata = "technicalMetadata" in (model ?? {}) ? (model as DataModelDefinition).technicalMetadata ?? {} : {};
  const preferredKeys = ["columns", "fields", "items"];
  const entries = Object.entries(metadata);
  const scored = entries
    .map(([key, value]) => {
      const rows = parseObjectRows(value);
      const score = rows.filter((row) => resolveFieldKey(row)).length;
      return { key, rows, score };
    })
    .filter((item) => item.rows.length > 0 && item.score > 0)
    .sort((left, right) => {
      const leftPriority = preferredKeys.indexOf(left.key);
      const rightPriority = preferredKeys.indexOf(right.key);
      if (leftPriority !== rightPriority) {
        return (leftPriority === -1 ? preferredKeys.length : leftPriority)
          - (rightPriority === -1 ? preferredKeys.length : rightPriority);
      }
      return right.score - left.score;
    });
  return scored[0]?.rows ?? [];
}

function resolveFieldKey(row: Record<string, unknown>) {
  return firstNonBlank(row.fieldKey, row.name, row.fieldName, row.columnName, row.physicalName, row.code);
}

function resolveFieldLabel(row: Record<string, unknown>) {
  return firstNonBlank(row.fieldName, row.name, row.columnName, row.physicalName, row.fieldKey, row.code);
}

function extractFieldOptions(model?: DataModelOptionView | DataModelDefinition) {
  const items = new Map<string, { value: string; label: string }>();
  for (const row of pickFieldRows(model)) {
    const value = String(resolveFieldKey(row) || "").trim();
    if (!value || items.has(value)) {
      continue;
    }
    const labelText = String(resolveFieldLabel(row) || value).trim();
    items.set(value, {
      value,
      label: labelText === value ? value : `${labelText} (${value})`,
    });
  }
  return Array.from(items.values());
}

function firstNonBlank(...values: unknown[]) {
  for (const value of values) {
    const text = String(value ?? "").trim();
    if (text) {
      return text;
    }
  }
  return "";
}

async function loadCurrentModel() {
  if (props.model) {
    currentModel.value = { ...props.model };
    return;
  }
  if (!props.modelId) {
    currentModel.value = undefined;
    return;
  }
  try {
    currentModel.value = await studioApi.models.get(props.modelId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.lineageLoadFailed"));
  }
}

async function loadLineageBundle() {
  if (!props.modelId) {
    lineageCache.DATABASE = emptyLineageView();
    lineageCache.TABLE = emptyLineageView();
    lineageCache.FIELD = emptyLineageView();
    return;
  }
  loading.value = true;
  try {
    const [databaseLineage, tableLineage, fieldLineage] = await Promise.all([
      studioApi.models.lineage(props.modelId, "DATABASE"),
      studioApi.models.lineage(props.modelId, "TABLE"),
      studioApi.models.lineage(props.modelId, "FIELD"),
    ]);
    lineageCache.DATABASE = sanitizeLineage(databaseLineage);
    lineageCache.TABLE = sanitizeLineage(tableLineage);
    lineageCache.FIELD = sanitizeLineage(fieldLineage);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.lineageLoadFailed"));
  } finally {
    loading.value = false;
  }
}

function sanitizeLineage(value?: DataModelLineageView) {
  return {
    editable: Boolean(value?.editable),
    summary: {
      upstreamDepth: Number(value?.summary?.upstreamDepth ?? 0),
      totalUpstreamCount: Number(value?.summary?.totalUpstreamCount ?? 0),
      directUpstreamCount: Number(value?.summary?.directUpstreamCount ?? 0),
      downstreamDepth: Number(value?.summary?.downstreamDepth ?? 0),
      totalDownstreamCount: Number(value?.summary?.totalDownstreamCount ?? 0),
      directDownstreamCount: Number(value?.summary?.directDownstreamCount ?? 0),
    },
    nodes: value?.nodes ?? [],
    edges: value?.edges ?? [],
    unresolvedExpressions: value?.unresolvedExpressions ?? [],
  } satisfies DataModelLineageView;
}

function switchLevel(level: DataModelLineageLevel) {
  activeLevel.value = level;
}

function formatSourceTypeLabel(value?: string) {
  const normalized = String(value ?? "").trim().toUpperCase();
  if (normalized === "COLLECTION_TASK" || normalized === "AUTOMATIC") {
    return t("web.models.lineageLegendAutomatic");
  }
  if (normalized === "MANUAL") {
    return t("web.models.lineageLegendManual");
  }
  if (normalized === "MIXED") {
    return `${t("web.models.lineageLegendAutomatic")} + ${t("web.models.lineageLegendManual")}`;
  }
  return value || t("common.none");
}

function formatDisplayStatus(value?: string) {
  const normalized = String(value ?? "").trim().toUpperCase();
  if (normalized === "NORMAL" || normalized === "SUCCESS") {
    return t("web.models.lineageStatusNormal");
  }
  if (normalized === "EXCEPTION" || normalized === "FAILED" || normalized === "ERROR") {
    return t("web.models.lineageStatusException");
  }
  if (normalized === "RUNNING" || normalized === "PENDING") {
    return t("web.models.lineageStatusRunning");
  }
  if (normalized === "NOT_RUN" || normalized === "STOPPED") {
    return t("web.models.lineageStatusNotRun");
  }
  return value || t("common.none");
}

function displayStatusTone(value?: string): "success" | "warning" | "danger" | "neutral" {
  const normalized = String(value ?? "").trim().toUpperCase();
  if (normalized === "NORMAL" || normalized === "SUCCESS") {
    return "success";
  }
  if (normalized === "EXCEPTION" || normalized === "FAILED" || normalized === "ERROR") {
    return "danger";
  }
  if (normalized === "RUNNING" || normalized === "PENDING") {
    return "warning";
  }
  return "neutral";
}

async function openEdgeDetail(edgeId: string) {
  if (!props.modelId || !edgeId) {
    return;
  }
  edgeDrawerOpen.value = true;
  edgeLoading.value = true;
  try {
    edgeDetail.value = await studioApi.models.lineageEdgeDetail(props.modelId, edgeId, activeLevel.value);
  } catch (error) {
    edgeDrawerOpen.value = false;
    ElMessage.error(error instanceof Error ? error.message : t("web.models.lineageEdgeLoadFailed"));
  } finally {
    edgeLoading.value = false;
  }
}

function jumpToPath(path: string) {
  if (!path) {
    return;
  }
  void router.push(path);
}

async function searchManualModelOptions(keyword: string) {
  await loadModelOptions(keyword);
}

async function loadModelOptions(keyword: string) {
  const requestSeq = modelOptionSearchSeq.value + 1;
  modelOptionSearchSeq.value = requestSeq;
  loadingModelOptions.value = true;
  try {
    const page = await studioApi.models.listOptions({
      keyword: keyword.trim() || undefined,
      pageNo: 1,
      pageSize: MODEL_OPTION_PAGE_SIZE,
    }, { studioSkipGlobalLoading: true });
    if (modelOptionSearchSeq.value === requestSeq) {
      availableModels.value = normalizeModelOptionPayload(page);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.lineageModelOptionsLoadFailed"));
  } finally {
    if (modelOptionSearchSeq.value === requestSeq) {
      loadingModelOptions.value = false;
    }
  }
}

function normalizeModelOptionPayload(payload: unknown): DataModelOptionView[] {
  if (Array.isArray(payload)) {
    return payload.filter((item): item is DataModelOptionView => item != null && typeof item === "object");
  }
  if (payload && typeof payload === "object") {
    const items = (payload as { items?: unknown }).items;
    if (Array.isArray(items)) {
      return items.filter((item): item is DataModelOptionView => item != null && typeof item === "object");
    }
  }
  return [];
}

async function ensureManualModelDetail(modelId?: EntityId) {
  const id = normalizeId(modelId);
  if (!id || modelDetailCache.value[id]) {
    return;
  }
  if (currentModel.value?.id != null && normalizeId(currentModel.value.id) === id) {
    modelDetailCache.value = { ...modelDetailCache.value, [id]: currentModel.value };
    return;
  }
  const detail = await studioApi.models.get(id, { studioSkipGlobalLoading: true });
  modelDetailCache.value = { ...modelDetailCache.value, [id]: detail };
}

async function openCreateManualRelation() {
  manualForm.relationId = undefined;
  manualForm.level = activeLevel.value === "FIELD" ? "FIELD" : "TABLE";
  manualForm.sourceModelId = undefined;
  manualForm.targetModelId = currentModel.value?.id ?? props.modelId;
  manualForm.sourceFieldKey = undefined;
  manualForm.targetFieldKey = undefined;
  manualDialogOpen.value = true;
  void loadModelOptions("");
}

async function openEditManualRelation(contributor: DataModelLineageContributorView) {
  manualForm.relationId = contributor.relationId;
  manualForm.level = activeLevel.value === "FIELD" ? "FIELD" : "TABLE";
  manualForm.sourceModelId = contributor.sourceModelId;
  manualForm.targetModelId = contributor.targetModelId ?? currentModel.value?.id ?? props.modelId;
  manualForm.sourceFieldKey = contributor.sourceField;
  manualForm.targetFieldKey = contributor.targetField;
  manualDialogOpen.value = true;
  void loadModelOptions("");
  void ensureManualModelDetail(manualForm.sourceModelId);
  void ensureManualModelDetail(manualForm.targetModelId);
}

async function submitManualRelation() {
  if (!props.modelId) {
    return;
  }
  if (!manualForm.sourceModelId || !manualForm.targetModelId) {
    ElMessage.warning(t("web.models.lineageManualModelRequired"));
    return;
  }
  const currentId = normalizeId(currentModel.value?.id ?? props.modelId);
  const sourceId = normalizeId(manualForm.sourceModelId);
  const targetId = normalizeId(manualForm.targetModelId);
  if (sourceId !== currentId && targetId !== currentId) {
    ElMessage.warning(t("web.models.lineageManualCurrentModelRequired"));
    return;
  }
  if (manualForm.level === "FIELD" && (!manualForm.sourceFieldKey || !manualForm.targetFieldKey)) {
    ElMessage.warning(t("web.models.lineageManualFieldRequired"));
    return;
  }

  const payload: DataModelManualLineageSaveRequest = {
    level: manualForm.level,
    sourceModelId: manualForm.sourceModelId,
    targetModelId: manualForm.targetModelId,
    sourceFieldKey: manualForm.level === "FIELD" ? manualForm.sourceFieldKey : undefined,
    targetFieldKey: manualForm.level === "FIELD" ? manualForm.targetFieldKey : undefined,
  };

  manualSaving.value = true;
  try {
    if (manualForm.relationId != null) {
      await studioApi.models.updateManualLineage(props.modelId, manualForm.relationId, payload);
    } else {
      await studioApi.models.createManualLineage(props.modelId, payload);
    }
    manualDialogOpen.value = false;
    edgeDrawerOpen.value = false;
    edgeDetail.value = undefined;
    ElMessage.success(t("web.models.lineageManualSaveSuccess"));
    await loadLineageBundle();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.lineageManualSaveFailed"));
  } finally {
    manualSaving.value = false;
  }
}

async function deleteManualRelation(contributor: DataModelLineageContributorView) {
  if (!props.modelId || contributor.relationId == null) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.models.lineageManualDeleteConfirm"),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.models.deleteManualLineage(props.modelId, contributor.relationId);
    edgeDrawerOpen.value = false;
    edgeDetail.value = undefined;
    ElMessage.success(t("web.models.lineageManualDeleteSuccess"));
    await loadLineageBundle();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.models.lineageManualDeleteFailed"));
    }
  }
}

function formatManualContributorTitle(contributor: DataModelLineageContributorView) {
  const sourceModel = findModelById(contributor.sourceModelId);
  const targetModel = findModelById(contributor.targetModelId);
  if (activeLevel.value === "FIELD") {
    return `${sourceModel?.name || contributor.sourceModelId || "--"} · ${contributor.sourceField || "--"} -> ${targetModel?.name || contributor.targetModelId || "--"} · ${contributor.targetField || "--"}`;
  }
  return `${sourceModel?.name || contributor.sourceModelId || "--"} -> ${targetModel?.name || contributor.targetModelId || "--"}`;
}

function calculateDisplayedSummary(lineage: DataModelLineageView, level: DataModelLineageLevel) {
  const fallback = lineage.summary ?? emptyLineageView().summary ?? {};
  const focusNodeId = lineage.nodes.find((item) => item.focus)?.nodeId ?? lineage.nodes[0]?.nodeId ?? "";
  if (!focusNodeId) {
    return fallback;
  }
  return level === "FIELD"
    ? calculateDisplayedFieldSummary(lineage, focusNodeId)
    : calculateDisplayedNodeSummary(lineage, focusNodeId);
}

function calculateDisplayedNodeSummary(lineage: DataModelLineageView, focusNodeId: string) {
  const incoming = new Map<string, Set<string>>();
  const outgoing = new Map<string, Set<string>>();
  for (const edge of lineage.edges) {
    if (!edge.sourceNodeId || !edge.targetNodeId) {
      continue;
    }
    if (!incoming.has(edge.targetNodeId)) {
      incoming.set(edge.targetNodeId, new Set<string>());
    }
    if (!outgoing.has(edge.sourceNodeId)) {
      outgoing.set(edge.sourceNodeId, new Set<string>());
    }
    incoming.get(edge.targetNodeId)?.add(edge.sourceNodeId);
    outgoing.get(edge.sourceNodeId)?.add(edge.targetNodeId);
  }
  const directUpstream = new Set(incoming.get(focusNodeId) ?? []);
  const directDownstream = new Set(outgoing.get(focusNodeId) ?? []);
  directUpstream.delete(focusNodeId);
  directDownstream.delete(focusNodeId);
  return {
    upstreamDepth: calculateMaxDepth(incoming, [focusNodeId]),
    totalUpstreamCount: traverseAllNodes(incoming, [focusNodeId]).size,
    directUpstreamCount: directUpstream.size,
    downstreamDepth: calculateMaxDepth(outgoing, [focusNodeId]),
    totalDownstreamCount: traverseAllNodes(outgoing, [focusNodeId]).size,
    directDownstreamCount: directDownstream.size,
  };
}

function calculateDisplayedFieldSummary(lineage: DataModelLineageView, focusNodeId: string) {
  const incoming = new Map<string, Set<string>>();
  const outgoing = new Map<string, Set<string>>();
  const focusFieldVertexIds = new Set<string>();
  for (const edge of lineage.edges) {
    if (!edge.sourceNodeId || !edge.targetNodeId || !edge.sourceField || !edge.targetField) {
      continue;
    }
    const sourceVertexId = `${edge.sourceNodeId}:${normalizeLineageFieldKey(edge.sourceField)}`;
    const targetVertexId = `${edge.targetNodeId}:${normalizeLineageFieldKey(edge.targetField)}`;
    if (!incoming.has(targetVertexId)) {
      incoming.set(targetVertexId, new Set<string>());
    }
    if (!outgoing.has(sourceVertexId)) {
      outgoing.set(sourceVertexId, new Set<string>());
    }
    incoming.get(targetVertexId)?.add(sourceVertexId);
    outgoing.get(sourceVertexId)?.add(targetVertexId);
    if (edge.sourceNodeId === focusNodeId) {
      focusFieldVertexIds.add(sourceVertexId);
    }
    if (edge.targetNodeId === focusNodeId) {
      focusFieldVertexIds.add(targetVertexId);
    }
  }
  if (focusFieldVertexIds.size === 0) {
    return {
      upstreamDepth: 0,
      totalUpstreamCount: 0,
      directUpstreamCount: 0,
      downstreamDepth: 0,
      totalDownstreamCount: 0,
      directDownstreamCount: 0,
    };
  }
  const directUpstream = new Set<string>();
  const directDownstream = new Set<string>();
  for (const focusFieldVertexId of focusFieldVertexIds) {
    for (const sourceId of incoming.get(focusFieldVertexId) ?? []) {
      directUpstream.add(sourceId);
    }
    for (const targetId of outgoing.get(focusFieldVertexId) ?? []) {
      directDownstream.add(targetId);
    }
  }
  for (const focusFieldVertexId of focusFieldVertexIds) {
    directUpstream.delete(focusFieldVertexId);
    directDownstream.delete(focusFieldVertexId);
  }
  return {
    upstreamDepth: calculateMaxDepth(incoming, Array.from(focusFieldVertexIds)),
    totalUpstreamCount: traverseAllNodes(incoming, Array.from(focusFieldVertexIds)).size,
    directUpstreamCount: directUpstream.size,
    downstreamDepth: calculateMaxDepth(outgoing, Array.from(focusFieldVertexIds)),
    totalDownstreamCount: traverseAllNodes(outgoing, Array.from(focusFieldVertexIds)).size,
    directDownstreamCount: directDownstream.size,
  };
}

function traverseAllNodes(adjacency: Map<string, Set<string>>, startNodeIds: string[]) {
  const startIds = new Set(startNodeIds.filter(Boolean));
  const visited = new Set<string>();
  const queue = [...startIds];
  while (queue.length > 0) {
    const current = queue.shift();
    if (!current) {
      continue;
    }
    for (const next of adjacency.get(current) ?? []) {
      if (startIds.has(next) || visited.has(next)) {
        continue;
      }
      visited.add(next);
      queue.push(next);
    }
  }
  return visited;
}

function calculateMaxDepth(adjacency: Map<string, Set<string>>, startNodeIds: string[]) {
  const startIds = new Set(startNodeIds.filter(Boolean));
  if (startIds.size === 0) {
    return 0;
  }
  const visited = new Set<string>(startIds);
  const queue = Array.from(startIds).map((id) => ({ id, depth: 0 }));
  let maxDepth = 0;
  while (queue.length > 0) {
    const current = queue.shift();
    if (!current) {
      continue;
    }
    for (const next of adjacency.get(current.id) ?? []) {
      if (startIds.has(next) || visited.has(next)) {
        continue;
      }
      visited.add(next);
      const depth = current.depth + 1;
      maxDepth = Math.max(maxDepth, depth);
      queue.push({ id: next, depth });
    }
  }
  return maxDepth;
}
</script>

<style scoped>
.lineage-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.lineage-summary-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
}

.lineage-summary-card {
  padding: 14px 16px;
  border-radius: 16px;
  border: 1px solid rgba(148, 163, 184, 0.24);
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.lineage-summary-card__label {
  font-size: 12px;
  color: var(--studio-text-soft);
}

.lineage-summary-card__value {
  font-size: 24px;
  line-height: 1;
  color: #0f172a;
}

.lineage-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  width: 100%;
}

.lineage-toolbar__primary {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.lineage-toolbar__actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.lineage-level-switch {
  display: inline-flex;
  border-radius: 12px;
  border: 1px solid rgba(37, 99, 235, 0.22);
  overflow: hidden;
  background: #ffffff;
}

.lineage-level-switch__option {
  border: none;
  background: transparent;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 700;
  color: #475569;
  cursor: pointer;
  transition: background-color 0.18s ease, color 0.18s ease;
}

.lineage-level-switch__option + .lineage-level-switch__option {
  border-left: 1px solid rgba(37, 99, 235, 0.14);
}

.lineage-level-switch__option--active {
  background: #2563eb;
  color: #ffffff;
}

.lineage-legend {
  display: inline-flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.lineage-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #475569;
}

.lineage-legend__swatch {
  width: 34px;
  height: 14px;
  display: inline-flex;
  align-items: center;
}

.lineage-legend__swatch-line {
  width: 100%;
  height: 0;
  border-top: 3px solid #2563eb;
  position: relative;
}

.lineage-legend__swatch-line::after {
  content: "";
  position: absolute;
  right: -1px;
  top: -5px;
  border-left: 7px solid currentColor;
  border-top: 5px solid transparent;
  border-bottom: 5px solid transparent;
}

.lineage-legend__swatch--automatic .lineage-legend__swatch-line {
  border-top-color: #2563eb;
  color: #2563eb;
}

.lineage-legend__swatch--manual .lineage-legend__swatch-line {
  border-top-color: #10b981;
  border-top-style: dashed;
  color: #10b981;
}

.lineage-legend__swatch--mixed .lineage-legend__swatch-line {
  border-top-color: #8b5cf6;
  border-top-style: dashed;
  color: #8b5cf6;
}

.lineage-form {
  display: grid;
  gap: 8px;
}

.lineage-form__control {
  width: 100%;
}

@media (max-width: 1280px) {
  .lineage-summary-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .lineage-summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
