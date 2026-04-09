<template>
  <div class="monaco-script-editor">
    <div class="soft-panel monaco-script-editor__meta">
      <div class="monaco-script-editor__copy">
        <strong>{{ metaTitle }}</strong>
        <span>{{ metaDescription }}</span>
      </div>
      <span v-if="registryEntry.enableSqlHints" class="monaco-script-editor__shortcut">
        {{ t("web.dataDevelopment.applyHintShortcut") }}
      </span>
    </div>

    <div class="monaco-script-editor__surface">
      <div ref="editorHostRef" class="monaco-script-editor__host"></div>
      <div v-if="showPlaceholder" class="monaco-script-editor__placeholder">
        {{ placeholder }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import { useI18n } from "vue-i18n";
import type { ScriptType } from "@studio/api-sdk";
import type * as MonacoType from "monaco-editor";
import { ensureMonacoSetup, bindSqlHintSource } from "./monacoSetup";
import { resolveScriptEditorEntry } from "./scriptEditorRegistry";
import type { SqlEditorHintSource } from "./editorTypes";

const props = defineProps<{
  scriptType: ScriptType;
  placeholder?: string;
  sqlHints?: SqlEditorHintSource;
}>();

const modelValue = defineModel<string>({ required: true });

const { t } = useI18n();
const editorHostRef = ref<HTMLDivElement | null>(null);
const editorRef = shallowRef<MonacoType.editor.IStandaloneCodeEditor | null>(null);
const monacoRef = shallowRef<typeof import("monaco-editor") | null>(null);
const textModelRef = shallowRef<MonacoType.editor.ITextModel | null>(null);
const disposeSqlHintsRef = ref<(() => void) | null>(null);
const hasFocus = ref(false);
const isInternalUpdate = ref(false);

const registryEntry = computed(() => resolveScriptEditorEntry(props.scriptType));
const showPlaceholder = computed(() => !hasFocus.value && !modelValue.value);
const metaTitle = computed(() => {
  if (registryEntry.value.enableSqlHints) {
    return t("web.dataDevelopment.sqlHintsTitle");
  }
  if (props.scriptType === "JAVA") {
    return t("web.dataDevelopment.javaEditorTitle");
  }
  if (props.scriptType === "PYTHON") {
    return t("web.dataDevelopment.pythonEditorTitle");
  }
  return t("web.dataDevelopment.scriptType");
});
const metaDescription = computed(() => {
  if (registryEntry.value.enableSqlHints) {
    return t("web.dataDevelopment.sqlHintsDescription", {
      datasource: props.sqlHints?.datasourceName || t("web.dataDevelopment.datasourcePlaceholder"),
    });
  }
  if (props.scriptType === "JAVA") {
    return t("web.dataDevelopment.javaEditorDescription");
  }
  if (props.scriptType === "PYTHON") {
    return t("web.dataDevelopment.pythonEditorDescription");
  }
  return t("web.dataDevelopment.genericEditorHint");
});

function createEditor() {
  if (!editorHostRef.value) {
    return;
  }
  const monaco = ensureMonacoSetup();
  monacoRef.value = monaco;

  const model = monaco.editor.createModel(
    modelValue.value ?? "",
    registryEntry.value.languageId,
    monaco.Uri.parse(`inmemory://studio/data-development/${Date.now()}-${Math.random().toString(36).slice(2)}.${registryEntry.value.scriptType.toLowerCase()}`),
  );
  textModelRef.value = model;
  disposeSqlHintsRef.value = bindSqlHintSource(model.uri, () => props.sqlHints);

  const editor = monaco.editor.create(editorHostRef.value, {
    model,
    theme: "vs",
    automaticLayout: true,
    minimap: { enabled: false },
    wordWrap: "on",
    quickSuggestions: {
      other: true,
      strings: true,
      comments: false,
    },
    suggestOnTriggerCharacters: true,
    acceptSuggestionOnCommitCharacter: true,
    acceptSuggestionOnEnter: "on",
    tabCompletion: "on",
    snippetSuggestions: "top",
    hover: { enabled: true },
    formatOnPaste: false,
    formatOnType: false,
    scrollBeyondLastLine: false,
    smoothScrolling: true,
    stickyScroll: { enabled: false },
    bracketPairColorization: { enabled: true },
    guides: {
      bracketPairs: true,
      indentation: true,
    },
    fontSize: 13,
    lineHeight: 22,
    fontFamily: "\"Cascadia Code\", \"Consolas\", monospace",
    padding: { top: 14, bottom: 14 },
  });
  editorRef.value = editor;

  editor.onDidChangeModelContent(() => {
    if (!textModelRef.value) {
      return;
    }
    isInternalUpdate.value = true;
    modelValue.value = textModelRef.value.getValue();
    nextTick(() => {
      isInternalUpdate.value = false;
    });
  });
  editor.onDidFocusEditorText(() => {
    hasFocus.value = true;
  });
  editor.onDidBlurEditorText(() => {
    hasFocus.value = false;
  });
}

function destroyEditor() {
  disposeSqlHintsRef.value?.();
  disposeSqlHintsRef.value = null;
  editorRef.value?.dispose();
  editorRef.value = null;
  textModelRef.value?.dispose();
  textModelRef.value = null;
}

watch(
  () => modelValue.value,
  (value) => {
    if (isInternalUpdate.value) {
      return;
    }
    const textModel = textModelRef.value;
    if (!textModel || textModel.getValue() === (value ?? "")) {
      return;
    }
    textModel.setValue(value ?? "");
  },
);

watch(
  () => registryEntry.value.languageId,
  (languageId) => {
    if (!textModelRef.value || !monacoRef.value) {
      return;
    }
    monacoRef.value.editor.setModelLanguage(textModelRef.value, languageId);
  },
);

watch(
  () => props.sqlHints,
  () => {
    if (registryEntry.value.enableSqlHints && editorRef.value) {
      editorRef.value.trigger("keyboard", "editor.action.triggerSuggest", {});
    }
  },
  { deep: true },
);

onMounted(() => {
  createEditor();
});

onBeforeUnmount(() => {
  destroyEditor();
});
</script>

<style scoped>
.monaco-script-editor {
  display: grid;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.monaco-script-editor__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.monaco-script-editor__copy {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.monaco-script-editor__copy span,
.monaco-script-editor__shortcut {
  font-size: 12px;
  color: var(--studio-text-soft);
}

.monaco-script-editor__surface {
  position: relative;
  width: 100%;
  min-width: 0;
}

.monaco-script-editor__host {
  width: 100%;
  min-height: 420px;
  border: 1px solid var(--studio-border);
  border-radius: 14px;
  overflow: hidden;
  background: #ffffff;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.65);
}

.monaco-script-editor__placeholder {
  position: absolute;
  top: 18px;
  left: 18px;
  color: var(--studio-text-soft);
  font: 13px/1.6 "Cascadia Code", "Consolas", monospace;
  pointer-events: none;
}

@media (max-width: 960px) {
  .monaco-script-editor__meta {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
