<template>
  <div class="mapping-editor">
    <div class="mapping-editor__toolbar">
      <span>{{ t("fieldMapping.title") }}</span>
      <el-button type="primary" plain @click="appendRow">{{ t("common.addMapping") }}</el-button>
    </div>

    <el-table :data="rows" border>
      <el-table-column v-if="showSourceAlias" :label="t('fieldMapping.sourceAlias')" min-width="150">
        <template #default="{ row, $index }">
          <el-select
            :model-value="row.sourceAlias"
            clearable
            filterable
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
            :placeholder="t('fieldMapping.optionalExpression')"
            @update:model-value="updateRow($index, 'expression', $event)"
          />
        </template>
      </el-table-column>

      <el-table-column :label="t('fieldMapping.transformers')" min-width="220">
        <template #default="{ row, $index }">
          <div class="mapping-editor__transformers">
            <span>{{ transformerSummary(row.transformers) }}</span>
            <el-button link type="primary" @click="openTransformerDialog($index)">{{ t("fieldMapping.configureTransformers") }}</el-button>
          </div>
        </template>
      </el-table-column>

      <el-table-column :label="t('fieldMapping.actions')" width="110" fixed="right">
        <template #default="{ $index }">
          <el-button type="danger" link @click="removeRow($index)">{{ t("common.remove") }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="transformerDialogVisible" :title="t('fieldMapping.transformerDialogTitle')" width="760px">
      <div class="mapping-editor__dialog">
        <div class="mapping-editor__toolbar">
          <span>{{ t("fieldMapping.transformerDialogDescription") }}</span>
          <el-button type="primary" plain @click="appendTransformer">{{ t("fieldMapping.addTransformer") }}</el-button>
        </div>

        <el-table :data="transformerDrafts" border>
          <el-table-column :label="t('fieldMapping.transformerCode')" min-width="200">
            <template #default="{ row }">
              <el-select v-model="row.transformerCode" filterable allow-create default-first-option>
                <el-option v-for="option in transformerOptions" :key="option" :label="option" :value="option" />
              </el-select>
            </template>
          </el-table-column>

          <el-table-column :label="t('fieldMapping.transformerParameters')" min-width="320">
            <template #default="{ row }">
              <el-input v-model="row.parametersText" type="textarea" :rows="4" placeholder='{"paras": ["0", "2"]}' />
            </template>
          </el-table-column>

          <el-table-column :label="t('fieldMapping.actions')" width="110">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeTransformer($index)">{{ t("common.remove") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <template #footer>
        <div class="mapping-editor__dialog-footer">
          <el-button @click="transformerDialogVisible = false">{{ t("common.cancel") }}</el-button>
          <el-button type="primary" @click="saveTransformers">{{ t("common.save") }}</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { FieldMappingDefinition, TransformerBinding } from "@studio/api-sdk";

interface TransformerDraft {
  transformerCode: string;
  parametersText: string;
}

const props = withDefaults(
  defineProps<{
    modelValue: FieldMappingDefinition[];
    sourceFields?: string[];
    targetFields?: string[];
    transformerOptions?: string[];
    sourceAliases?: string[];
    sourceFieldOptionsByAlias?: Record<string, string[]>;
    showExpression?: boolean;
  }>(),
  {
    modelValue: () => [],
    sourceFields: () => [],
    targetFields: () => [],
    transformerOptions: () => [],
    sourceAliases: () => [],
    sourceFieldOptionsByAlias: () => ({}),
    showExpression: true,
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: FieldMappingDefinition[]];
}>();

const { t } = useI18n();
const rows = computed(() => props.modelValue ?? []);
const showSourceAlias = computed(() => props.sourceAliases.length > 0);
const transformerDialogVisible = ref(false);
const transformerDrafts = ref<TransformerDraft[]>([]);
const editingRowIndex = ref<number | null>(null);

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

function resolveSourceFieldOptions(row: FieldMappingDefinition) {
  if (row.sourceAlias && props.sourceFieldOptionsByAlias[row.sourceAlias]?.length) {
    return props.sourceFieldOptionsByAlias[row.sourceAlias];
  }
  return props.sourceFields;
}

function transformerSummary(transformers: TransformerBinding[]) {
  if (!transformers?.length) {
    return t("fieldMapping.noTransformers");
  }
  return transformers.map((item) => item.transformerCode).join(", ");
}

function openTransformerDialog(index: number) {
  editingRowIndex.value = index;
  transformerDrafts.value = (rows.value[index]?.transformers ?? []).map((item) => ({
    transformerCode: item.transformerCode,
    parametersText: JSON.stringify(item.parameters ?? {}, null, 2),
  }));
  if (!transformerDrafts.value.length) {
    appendTransformer();
  }
  transformerDialogVisible.value = true;
}

function appendTransformer() {
  transformerDrafts.value.push({
    transformerCode: props.transformerOptions[0] ?? "",
    parametersText: "{}",
  });
}

function removeTransformer(index: number) {
  transformerDrafts.value.splice(index, 1);
}

function saveTransformers() {
  if (editingRowIndex.value == null) {
    transformerDialogVisible.value = false;
    return;
  }
  const transformers: TransformerBinding[] = [];
  for (const item of transformerDrafts.value) {
    if (!item.transformerCode.trim()) {
      continue;
    }
    try {
      transformers.push({
        transformerCode: item.transformerCode.trim(),
        parameters: item.parametersText.trim() ? JSON.parse(item.parametersText) : {},
      });
    } catch (error) {
      ElMessage.error(
        error instanceof Error ? `${t("fieldMapping.invalidTransformerParameters")}: ${error.message}` : t("fieldMapping.invalidTransformerParameters"),
      );
      return;
    }
  }
  updateRow(editingRowIndex.value, "transformers", transformers);
  transformerDialogVisible.value = false;
}

function removeRow(index: number) {
  emit(
    "update:modelValue",
    rows.value.filter((_, rowIndex) => rowIndex !== index),
  );
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

.mapping-editor__dialog {
  display: grid;
  gap: 12px;
}

.mapping-editor__dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
