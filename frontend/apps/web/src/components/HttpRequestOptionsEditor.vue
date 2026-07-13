<template>
  <HttpReaderOptionsEditor
    :fields="fields"
    :model-value="modelValue"
    :dynamic-function-fields="dynamicFunctionFields"
    @update:model-value="emit('update:modelValue', $event)"
    @dirty-key="emit('dirty-key', $event)"
  />
</template>

<script setup lang="ts">
import type { MetadataFieldDefinition } from "@studio/api-sdk";
import HttpReaderOptionsEditor from "./HttpReaderOptionsEditor.vue";

withDefaults(defineProps<{
  fields: MetadataFieldDefinition[];
  modelValue: Record<string, unknown>;
  dynamicFunctionFields?: string[];
}>(), {
  fields: () => [],
  modelValue: () => ({}),
  dynamicFunctionFields: () => [],
});

const emit = defineEmits<{
  "update:modelValue": [value: Record<string, unknown>];
  "dirty-key": [fieldKey: string];
}>();
</script>
