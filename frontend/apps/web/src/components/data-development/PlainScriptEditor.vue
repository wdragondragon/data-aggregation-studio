<template>
  <div class="plain-editor">
    <div class="soft-panel plain-editor__meta">
      <strong>{{ typeLabel }}</strong>
      <span>{{ t("web.dataDevelopment.genericEditorHint") }}</span>
    </div>

    <textarea
      ref="textareaRef"
      v-model="model"
      class="plain-editor__textarea"
      :placeholder="placeholder"
      spellcheck="false"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import type { ScriptType } from "@studio/api-sdk";

const props = defineProps<{
  scriptType: ScriptType;
  placeholder?: string;
}>();

const model = defineModel<string>({ required: true });

const { t } = useI18n();
const textareaRef = ref<HTMLTextAreaElement | null>(null);

const typeLabel = computed(() => {
  switch (props.scriptType) {
    case "JAVA":
      return t("web.dataDevelopment.scriptTypeJava");
    case "PYTHON":
      return t("web.dataDevelopment.scriptTypePython");
    default:
      return props.scriptType;
  }
});
</script>

<style scoped>
.plain-editor {
  display: grid;
  gap: 10px;
}

.plain-editor__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
  color: var(--studio-text-soft);
}

.plain-editor__textarea {
  width: 100%;
  min-height: 360px;
  padding: 14px 16px;
  border: 1px solid var(--studio-border);
  border-radius: 14px;
  background: rgba(248, 250, 252, 0.9);
  color: var(--studio-text);
  font: 13px/1.65 "Cascadia Code", "Consolas", monospace;
  resize: vertical;
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.plain-editor__textarea:focus {
  border-color: var(--studio-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}
</style>
