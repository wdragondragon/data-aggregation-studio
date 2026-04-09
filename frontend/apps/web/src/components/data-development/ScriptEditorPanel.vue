<template>
  <MonacoScriptEditor
    v-model="model"
    :script-type="scriptType"
    :placeholder="placeholder"
    :sql-hints="sqlHints"
  />
</template>

<script setup lang="ts">
import type { ScriptType } from "@studio/api-sdk";
import type { SqlEditorHintSource } from "./editorTypes";
import MonacoScriptEditor from "./MonacoScriptEditor.vue";

const props = defineProps<{
  scriptType: ScriptType;
  placeholder?: string;
  sqlHints?: SqlEditorHintSource;
}>();

const model = defineModel<string>({ required: true });
</script>
