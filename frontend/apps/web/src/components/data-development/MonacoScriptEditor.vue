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
import { ensureMonacoSetup, bindJavaHintSource, bindSqlHintSource } from "./monacoSetup";
import { resolveScriptEditorEntry } from "./scriptEditorRegistry";
import type { JavaEditorHintSource, SqlEditorHintSource } from "./editorTypes";

const props = defineProps<{
  scriptType: ScriptType;
  placeholder?: string;
  sqlHints?: SqlEditorHintSource;
  javaHintSource?: JavaEditorHintSource;
  javaHintKey?: string | number;
  readonly?: boolean;
}>();

const modelValue = defineModel<string>({ required: true });

const { t } = useI18n();
const editorHostRef = ref<HTMLDivElement | null>(null);
const editorRef = shallowRef<MonacoType.editor.IStandaloneCodeEditor | null>(null);
const monacoRef = shallowRef<typeof import("monaco-editor") | null>(null);
const textModelRef = shallowRef<MonacoType.editor.ITextModel | null>(null);
const disposeSqlHintsRef = ref<(() => void) | null>(null);
const disposeJavaHintsRef = ref<(() => void) | null>(null);
const suggestPositionObserverRef = ref<MutationObserver | null>(null);
const suggestPositionFrameRef = ref<number | null>(null);
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
  disposeJavaHintsRef.value = bindJavaHintSource(model.uri, () => props.javaHintSource);

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
    readOnly: props.readonly === true,
    domReadOnly: props.readonly === true,
  });
  editorRef.value = editor;
  setupSuggestPositionGuard();

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
    scrollEditorIntoComfortableView();
  });
  editor.onDidBlurEditorText(() => {
    hasFocus.value = false;
  });
}

function scrollEditorIntoComfortableView() {
  const host = editorHostRef.value;
  if (!host) {
    return;
  }
  requestAnimationFrame(() => {
    const rect = host.getBoundingClientRect();
    const viewportHeight = window.innerHeight || document.documentElement.clientHeight;
    if (rect.top < 120 || rect.bottom > viewportHeight - 80) {
      host.scrollIntoView({ block: "center", behavior: "auto" });
    }
  });
}

function destroyEditor() {
  destroySuggestPositionGuard();
  disposeSqlHintsRef.value?.();
  disposeSqlHintsRef.value = null;
  disposeJavaHintsRef.value?.();
  disposeJavaHintsRef.value = null;
  editorRef.value?.dispose();
  editorRef.value = null;
  textModelRef.value?.dispose();
  textModelRef.value = null;
}

function setupSuggestPositionGuard() {
  const host = editorHostRef.value;
  if (!host || typeof MutationObserver === "undefined") {
    return;
  }
  const observer = new MutationObserver(scheduleSuggestPositionGuard);
  observer.observe(host, {
    subtree: true,
    childList: true,
    attributes: true,
    attributeFilter: ["class", "style"],
  });
  suggestPositionObserverRef.value = observer;
}

function scheduleSuggestPositionGuard() {
  if (suggestPositionFrameRef.value !== null) {
    return;
  }
  suggestPositionFrameRef.value = window.requestAnimationFrame(() => {
    suggestPositionFrameRef.value = null;
    keepSuggestWidgetInsideEditor();
  });
}

function keepSuggestWidgetInsideEditor() {
  const host = editorHostRef.value;
  const widget = host?.querySelector<HTMLElement>(".suggest-widget.visible");
  if (!host || !widget) {
    return;
  }
  const minTop = host.getBoundingClientRect().top + 6;
  const minLeft = 8;
  const maxRight = window.innerWidth - 8;
  const maxBottom = window.innerHeight - 8;
  const widgetRect = widget.getBoundingClientRect();
  let changed = false;
  const currentTop = Number.parseFloat(widget.style.top || "0");
  const baseTop = Number.isFinite(currentTop) ? currentTop : widget.offsetTop;
  if (widgetRect.top < minTop) {
    widget.style.top = `${baseTop + minTop - widgetRect.top}px`;
    widget.style.bottom = "auto";
    changed = true;
  } else if (widgetRect.bottom > maxBottom && widgetRect.top > minTop) {
    const upwardDelta = Math.min(widgetRect.bottom - maxBottom, widgetRect.top - minTop);
    widget.style.top = `${baseTop - upwardDelta}px`;
    widget.style.bottom = "auto";
    changed = true;
  }

  const currentLeft = Number.parseFloat(widget.style.left || "0");
  const baseLeft = Number.isFinite(currentLeft) ? currentLeft : widget.offsetLeft;
  let leftDelta = 0;
  if (widgetRect.right > maxRight) {
    leftDelta = maxRight - widgetRect.right;
  } else if (widgetRect.left < minLeft) {
    leftDelta = minLeft - widgetRect.left;
  }
  if (leftDelta !== 0) {
    widget.style.left = `${baseLeft + leftDelta}px`;
    changed = true;
  }

  if (changed) {
    scheduleSuggestPositionGuard();
  }
}

function destroySuggestPositionGuard() {
  suggestPositionObserverRef.value?.disconnect();
  suggestPositionObserverRef.value = null;
  if (suggestPositionFrameRef.value !== null) {
    window.cancelAnimationFrame(suggestPositionFrameRef.value);
    suggestPositionFrameRef.value = null;
  }
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

watch(
  () => [props.javaHintSource, props.javaHintKey] as const,
  () => {
    if (props.scriptType === "JAVA" && editorRef.value) {
      editorRef.value.trigger("keyboard", "editor.action.triggerSuggest", {});
    }
  },
);

watch(
  () => props.readonly,
  (readonly) => {
    editorRef.value?.updateOptions({
      readOnly: readonly === true,
      domReadOnly: readonly === true,
    });
  },
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
  position: relative;
  width: 100%;
  min-height: 420px;
  border: 1px solid var(--studio-border);
  border-radius: 14px;
  overflow: visible;
  background: #ffffff;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.65);
}

.monaco-script-editor__host :deep(.overflowingContentWidgets),
.monaco-script-editor__host :deep(.suggest-widget) {
  z-index: 1200;
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
