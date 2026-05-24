<template>
  <div class="http-raw-option-editor">
    <el-input
      :ref="(element: unknown) => emit('registerInputRef', element)"
      :model-value="modelValue"
      type="textarea"
      :rows="rows"
      :placeholder="placeholder"
      @update:model-value="emit('update:modelValue', String($event))"
      @click="emit('syncSelection', $event)"
      @focus="emit('syncSelection', $event)"
      @keyup="emit('syncSelection', $event)"
      @select="emit('syncSelection', $event)"
    />
    <p v-if="parseError" class="http-raw-option-editor__error">
      {{ parseError }}
    </p>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: string;
  rows: number;
  placeholder: string;
  parseError?: string;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: string];
  registerInputRef: [element: unknown];
  syncSelection: [event: Event];
}>();
</script>

<style scoped>
.http-raw-option-editor__error {
  margin: 8px 0 0;
  color: var(--el-color-danger);
  font-size: 12px;
}
</style>
