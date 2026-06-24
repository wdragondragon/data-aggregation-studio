<template>
  <MonacoScriptEditor
    v-model="model"
    :script-type="scriptType"
    :placeholder="placeholder"
    :sql-hints="sqlHints"
    :java-hint-source="javaHintSource"
    :java-hint-key="javaHintKey"
    :readonly="readonly"
  />
</template>

<script setup lang="ts">
import type { ScriptType } from "@studio/api-sdk";
import type { JavaEditorHintSource, SqlEditorHintSource } from "./editorTypes";
import MonacoScriptEditor from "./MonacoScriptEditor.vue";

const props = defineProps<{
  scriptType: ScriptType;
  placeholder?: string;
  sqlHints?: SqlEditorHintSource;
  javaHintSource?: JavaEditorHintSource;
  javaHintKey?: string | number;
  readonly?: boolean;
}>();

const model = defineModel<string>({ required: true });
</script>
