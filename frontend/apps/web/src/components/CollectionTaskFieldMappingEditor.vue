<template>
  <div class="mapping-editor">
    <div class="mapping-editor__toolbar">
      <span>{{ t("fieldMapping.title") }}</span>
      <el-button type="primary" plain :disabled="disabled" @click="appendRow">{{ t("common.addMapping") }}</el-button>
    </div>

    <StudioTableShell min-width="1120px">
      <el-table :data="rows" border>
      <el-table-column v-if="showSourceAlias" :label="t('fieldMapping.sourceAlias')" min-width="150">
        <template #default="{ row, $index }">
          <el-select
            :model-value="row.sourceAlias"
            clearable
            filterable
            :disabled="disabled"
            :placeholder="t('fieldMapping.selectSourceAlias')"
            @update:model-value="updateRow($index, 'sourceAlias', $event)"
          >
            <el-option v-for="alias in sourceAliases" :key="alias" :label="alias" :value="alias" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column :label="t('fieldMapping.sourceField')" min-width="180">
        <template #default="{ row, $index }">
          <el-select
            :model-value="row.sourceField"
            clearable
            filterable
            :disabled="disabled"
            :placeholder="t('fieldMapping.selectSourceField')"
            @update:model-value="updateRow($index, 'sourceField', $event)"
          >
            <el-option v-for="field in resolveSourceFieldOptions(row)" :key="field" :label="field" :value="field" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column :label="t('fieldMapping.targetField')" min-width="180">
        <template #default="{ row, $index }">
          <el-select
            :model-value="row.targetField"
            clearable
            filterable
            :disabled="disabled"
            :placeholder="t('fieldMapping.selectTargetField')"
            @update:model-value="updateRow($index, 'targetField', $event)"
          >
            <el-option v-for="field in targetFields" :key="field" :label="field" :value="field" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column v-if="showExpression" :label="t('fieldMapping.expression')" min-width="220">
        <template #default="{ row, $index }">
          <el-input
            :model-value="row.expression"
            :disabled="disabled"
            :placeholder="t('fieldMapping.optionalExpression')"
            @update:model-value="updateRow($index, 'expression', $event)"
          />
        </template>
      </el-table-column>

      <el-table-column :label="t('fieldMapping.transformers')" min-width="280">
        <template #default="{ row, $index }">
          <div class="mapping-editor__transformers">
            <span class="mapping-editor__transformer-summary">{{ transformerSummary(row.transformers) }}</span>
            <el-button link type="primary" :disabled="disabled" @click="openTransformerDialog($index)">{{ t("fieldMapping.configureTransformers") }}</el-button>
          </div>
        </template>
      </el-table-column>

      <el-table-column :label="t('fieldMapping.actions')" width="110" fixed="right">
        <template #default="{ $index }">
          <el-button type="danger" link :disabled="disabled" @click="removeRow($index)">{{ t("common.remove") }}</el-button>
        </template>
      </el-table-column>
      </el-table>
    </StudioTableShell>

    <TransformerBindingEditor
      v-model:visible="transformerDialogVisible"
      :model-value="editingRowTransformers"
      :rule-options="ruleOptions"
      :load-rule-detail="loadRuleDetail"
      @save="saveRowTransformers"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import type {
  EntityId,
  FieldMappingDefinition,
  FieldMappingRuleOptionView,
  FieldMappingRuleView,
  TransformerBinding,
} from "@studio/api-sdk";
import { StudioTableShell } from "@studio/ui";
import TransformerBindingEditor from "@/components/TransformerBindingEditor.vue";

const props = withDefaults(
  defineProps<{
    modelValue: FieldMappingDefinition[];
    sourceFields?: string[];
    targetFields?: string[];
    sourceAliases?: string[];
    sourceFieldOptionsByAlias?: Record<string, string[]>;
    ruleOptions?: FieldMappingRuleOptionView[];
    loadRuleDetail?: (id: EntityId) => Promise<FieldMappingRuleView>;
    showSourceAlias?: boolean;
    showExpression?: boolean;
    disabled?: boolean;
  }>(),
  {
    modelValue: () => [],
    sourceFields: () => [],
    targetFields: () => [],
    sourceAliases: () => [],
    sourceFieldOptionsByAlias: () => ({}),
    ruleOptions: () => [],
    showSourceAlias: true,
    showExpression: true,
    disabled: false,
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: FieldMappingDefinition[]];
}>();

const { t } = useI18n();
const rows = computed(() => props.modelValue ?? []);
const showSourceAlias = computed(() => props.showSourceAlias && props.sourceAliases.length > 0);
const transformerDialogVisible = ref(false);
const editingRowIndex = ref<number | null>(null);
const editingRowTransformers = computed(() => {
  if (editingRowIndex.value == null) {
    return [];
  }
  return rows.value[editingRowIndex.value]?.transformers ?? [];
});

function appendRow() {
  emit("update:modelValue", [
    ...rows.value,
    {
      sourceAlias: props.sourceAliases[0] ?? "",
      sourceField: "",
      targetField: "",
      expression: "",
      transformers: [],
    },
  ]);
}

function updateRow(index: number, key: keyof FieldMappingDefinition, value: unknown) {
  const next = rows.value.map((row, rowIndex) =>
    rowIndex === index
      ? {
          ...row,
          [key]: value,
          ...(key === "sourceAlias" ? { sourceField: "" } : {}),
        }
      : row,
  );
  emit("update:modelValue", next);
}

function removeRow(index: number) {
  emit("update:modelValue", rows.value.filter((_, rowIndex) => rowIndex !== index));
}

function resolveSourceFieldOptions(row: FieldMappingDefinition) {
  const sourceAlias = row.sourceAlias || props.sourceAliases[0];
  if (sourceAlias && props.sourceFieldOptionsByAlias[sourceAlias]?.length) {
    return props.sourceFieldOptionsByAlias[sourceAlias];
  }
  return props.sourceFields;
}

function transformerSummary(transformers: TransformerBinding[]) {
  if (!transformers?.length) {
    return t("fieldMapping.noTransformers");
  }
  return transformers
    .map((item) => item.mappingName || item.mappingCode || item.transformerCode)
    .filter(Boolean)
    .join(", ");
}

function openTransformerDialog(index: number) {
  editingRowIndex.value = index;
  transformerDialogVisible.value = true;
}

function saveRowTransformers(transformers: TransformerBinding[]) {
  if (editingRowIndex.value == null) {
    transformerDialogVisible.value = false;
    return;
  }
  updateRow(editingRowIndex.value, "transformers", transformers);
  transformerDialogVisible.value = false;
}
</script>

<style scoped>
.mapping-editor {
  display: grid;
  gap: 12px;
}

.mapping-editor__toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.mapping-editor__transformers {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.mapping-editor__transformer-summary {
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mapping-editor__dialog {
  display: grid;
  gap: 16px;
}

.mapping-editor__dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.mapping-editor__empty {
  padding: 16px;
  border-radius: 12px;
  border: 1px dashed var(--el-border-color);
  color: var(--el-text-color-secondary);
}

.transformer-draft {
  display: grid;
  gap: 14px;
  padding: 16px;
  border-radius: 16px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  background: rgba(248, 250, 252, 0.72);
}

.transformer-draft__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.transformer-draft__heading {
  display: grid;
  gap: 4px;
}

.transformer-draft__summary {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.transformer-draft__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.transformer-draft__content {
  display: grid;
  gap: 14px;
}

.transformer-draft__grid {
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
}

.transformer-draft__params {
  display: grid;
  gap: 14px;
}

.transformer-draft__param {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr);
  align-items: start;
  gap: 12px;
}

.transformer-draft__param label {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  line-height: 32px;
}

.transformer-draft__param-control {
  display: grid;
  gap: 8px;
}

.transformer-draft__missing {
  display: grid;
  gap: 12px;
}

.stack-label {
  display: grid;
  gap: 4px;
}

.stack-label span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.checkbox-group {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
}

.radio-group {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
}

.param-description {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.full-width {
  width: 100%;
}

@media (max-width: 900px) {
  .transformer-draft__param {
    grid-template-columns: minmax(0, 1fr);
  }

  .transformer-draft__param label {
    line-height: 1.5;
  }
}
</style>
