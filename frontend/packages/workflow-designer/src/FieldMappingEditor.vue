<template>
  <div class="mapping-editor">
    <div class="mapping-editor__toolbar">
      <span>{{ t("fieldMapping.title") }}</span>
      <el-button type="primary" plain @click="appendRow">{{ t("common.addMapping") }}</el-button>
    </div>

    <el-table :data="rows" border>
      <el-table-column :label="t('fieldMapping.sourceField')" min-width="180">
        <template #default="{ row, $index }">
          <el-select
            :model-value="row.sourceField"
            clearable
            filterable
            :placeholder="t('fieldMapping.selectSourceField')"
            @update:model-value="updateRow($index, 'sourceField', $event)"
          >
            <el-option v-for="field in sourceFields" :key="field" :label="field" :value="field" />
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

      <el-table-column :label="t('fieldMapping.expression')" min-width="200">
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
          <el-select
            :model-value="transformerCodes(row.transformers)"
            multiple
            filterable
            allow-create
            default-first-option
            :placeholder="t('fieldMapping.chooseTransformers')"
            @update:model-value="updateTransformers($index, $event)"
          >
            <el-option
              v-for="option in transformerOptions"
              :key="option"
              :label="option"
              :value="option"
            />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column :label="t('fieldMapping.actions')" width="110" fixed="right">
        <template #default="{ $index }">
          <el-button type="danger" link @click="removeRow($index)">{{ t("common.remove") }}</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { FieldMappingDefinition, TransformerBinding } from "@studio/api-sdk";

const props = withDefaults(
  defineProps<{
    modelValue: FieldMappingDefinition[];
    sourceFields?: string[];
    targetFields?: string[];
    transformerOptions?: string[];
  }>(),
  {
    modelValue: () => [],
    sourceFields: () => [],
    targetFields: () => [],
    transformerOptions: () => [],
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: FieldMappingDefinition[]];
}>();

const { t } = useI18n();
const rows = computed(() => props.modelValue ?? []);

function appendRow() {
  emit("update:modelValue", [
    ...rows.value,
    {
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
        }
      : row,
  );
  emit("update:modelValue", next);
}

function updateTransformers(index: number, values: string[]) {
  const transformers: TransformerBinding[] = values.map((transformerCode) => ({
    transformerCode,
    parameters: {},
  }));
  updateRow(index, "transformers", transformers);
}

function transformerCodes(transformers: TransformerBinding[]) {
  return transformers.map((item) => item.transformerCode);
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
</style>
