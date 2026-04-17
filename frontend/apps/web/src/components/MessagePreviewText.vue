<template>
  <span class="message-preview-text" :title="hasValue ? normalizedText : undefined">
    {{ previewText }}
  </span>
</template>

<script setup lang="ts">
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    text?: string | null;
    emptyText?: string;
    maxLength?: number;
  }>(),
  {
    text: "",
    emptyText: "-",
    maxLength: 96,
  },
);

const normalizedText = computed(() => String(props.text ?? "").trim());
const hasValue = computed(() => normalizedText.value.length > 0);
const previewText = computed(() => {
  if (!hasValue.value) {
    return props.emptyText;
  }
  if (normalizedText.value.length <= props.maxLength) {
    return normalizedText.value;
  }
  return `${normalizedText.value.slice(0, props.maxLength).trimEnd()}...`;
});
</script>

<style scoped>
.message-preview-text {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
