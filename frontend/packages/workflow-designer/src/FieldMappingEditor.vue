<template>
  <div class="mapping-editor">
    <div class="mapping-editor__toolbar">
      <span>Field mappings</span>
      <el-button type="primary" plain @click="appendRow">Add Mapping</el-button>
    </div>

    <el-table :data="rows" border>
      <el-table-column label="Source Field" min-width="180">
        <template #default="{ row, $index }">
          <el-select
            :model-value="row.sourceField"
            clearable
            filterable
            placeholder="Select source field"
            @update:model-value="updateRow($index, 'sourceField', $event)"
          >
            <el-option v-for="field in sourceFields" :key="field" :label="field" :value="field" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="Target Field" min-width="180">
        <template #default="{ row, $index }">
          <el-select
            :model-value="row.targetField"
            clearable
            filterable
            placeholder="Select target field"
            @update:model-value="updateRow($index, 'targetField', $event)"
          >
            <el-option v-for="field in targetFields" :key="field" :label="field" :value="field" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="Expression" min-width="200">
        <template #default="{ row, $index }">
          <el-input
            :model-value="row.expression"
            placeholder="Optional expression"
            @update:model-value="updateRow($index, 'expression', $event)"
          />
        </template>
      </el-table-column>

      <el-table-column label="Transformers" min-width="220">
        <template #default="{ row, $index }">
          <el-select
            :model-value="transformerCodes(row.transformers)"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="Choose transformers"
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

      <el-table-column label="Actions" width="110" fixed="right">
        <template #default="{ $index }">
          <el-button type="danger" link @click="removeRow($index)">Remove</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
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
