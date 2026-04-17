<template>
  <div class="json-editor" :style="{ '--json-editor-height': normalizedHeight }">
    <div class="json-editor__header">
      <div class="json-editor__copy">
        <strong>{{ title }}</strong>
        <span>{{ resolvedDescription }}</span>
      </div>
      <div class="json-editor__actions">
        <span class="json-editor__state" :class="{ invalid: Boolean(errorMessage), readonly }">
          {{ stateText }}
        </span>
        <el-button size="small" text @click="formatContent">格式化</el-button>
        <el-button v-if="!readonly" size="small" text @click="clearContent">清空</el-button>
      </div>
    </div>

    <div class="json-editor__surface">
      <div ref="editorHostRef" class="json-editor__host"></div>
      <div v-if="showPlaceholder" class="json-editor__placeholder">
        {{ placeholder }}
      </div>
    </div>
    <p v-if="errorMessage" class="json-editor__error">{{ errorMessage }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import type * as MonacoType from "monaco-editor";
import { ensureMonacoSetup } from "@/components/data-development/monacoSetup";

const props = withDefaults(defineProps<{
  title?: string;
  description?: string;
  placeholder?: string;
  readonly?: boolean;
  height?: string;
}>(), {
  title: "JSON 编辑器",
  description: "",
  placeholder: "{}",
  readonly: false,
  height: "260px",
});

const modelValue = defineModel<string>({ required: true });

const editorHostRef = ref<HTMLDivElement | null>(null);
const editorRef = shallowRef<MonacoType.editor.IStandaloneCodeEditor | null>(null);
const textModelRef = shallowRef<MonacoType.editor.ITextModel | null>(null);
const hasFocus = ref(false);
const isInternalUpdate = ref(false);
const errorMessage = ref("");

const normalizedHeight = computed(() => props.height || "260px");
const showPlaceholder = computed(() => !hasFocus.value && !String(modelValue.value || "").trim());
const resolvedDescription = computed(() => props.description || (props.readonly ? "只读展示调试返回结果。" : "支持 JSON 高亮、校验与格式化。"));
const stateText = computed(() => {
  if (errorMessage.value) {
    return "JSON 异常";
  }
  return props.readonly ? "只读" : "可编辑";
});

function createEditor() {
  if (!editorHostRef.value) {
    return;
  }
  const monaco = ensureMonacoSetup();
  const model = monaco.editor.createModel(
    modelValue.value ?? "",
    "json",
    monaco.Uri.parse(`inmemory://studio/json-editor/${Date.now()}-${Math.random().toString(36).slice(2)}.json`),
  );
  textModelRef.value = model;

  const editor = monaco.editor.create(editorHostRef.value, {
    model,
    theme: "vs",
    automaticLayout: true,
    minimap: { enabled: false },
    wordWrap: "on",
    lineNumbers: "on",
    folding: true,
    formatOnPaste: true,
    formatOnType: true,
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
    readOnly: props.readonly,
    domReadOnly: props.readonly,
  });
  editorRef.value = editor;

  editor.onDidChangeModelContent(() => {
    if (!textModelRef.value) {
      return;
    }
    isInternalUpdate.value = true;
    modelValue.value = textModelRef.value.getValue();
    validateJson(modelValue.value);
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
  validateJson(modelValue.value);
}

function destroyEditor() {
  editorRef.value?.dispose();
  editorRef.value = null;
  textModelRef.value?.dispose();
  textModelRef.value = null;
}

function validateJson(value: string) {
  const text = String(value || "").trim();
  if (!text) {
    errorMessage.value = "";
    return true;
  }
  try {
    JSON.parse(text);
    errorMessage.value = "";
    return true;
  } catch (error) {
    errorMessage.value = error instanceof Error ? `JSON 格式错误：${error.message}` : "JSON 格式错误";
    return false;
  }
}

function formatContent() {
  const text = String(modelValue.value || "").trim();
  if (!text) {
    setEditorValue("{}");
    errorMessage.value = "";
    return;
  }
  try {
    const formatted = JSON.stringify(JSON.parse(text), null, 2);
    setEditorValue(formatted);
    errorMessage.value = "";
  } catch (error) {
    errorMessage.value = error instanceof Error ? `JSON 格式错误：${error.message}` : "JSON 格式错误";
  }
}

function clearContent() {
  setEditorValue("{}");
  errorMessage.value = "";
}

function setEditorValue(value: string) {
  if (textModelRef.value && textModelRef.value.getValue() !== value) {
    textModelRef.value.setValue(value);
    return;
  }
  modelValue.value = value;
}

watch(
  () => modelValue.value,
  (value) => {
    if (isInternalUpdate.value) {
      return;
    }
    const normalized = value ?? "";
    if (textModelRef.value && textModelRef.value.getValue() !== normalized) {
      textModelRef.value.setValue(normalized);
    }
    validateJson(normalized);
  },
);

watch(
  () => props.readonly,
  (readonly) => {
    editorRef.value?.updateOptions({
      readOnly: readonly,
      domReadOnly: readonly,
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
.json-editor {
  display: grid;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.json-editor__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.json-editor__copy {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.json-editor__copy strong {
  color: var(--studio-text);
  font-size: 13px;
}

.json-editor__copy span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.json-editor__actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.json-editor__state {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.08);
  color: var(--studio-primary);
  font-size: 12px;
}

.json-editor__state.readonly {
  background: rgba(100, 116, 139, 0.12);
  color: #64748b;
}

.json-editor__state.invalid {
  background: rgba(239, 68, 68, 0.1);
  color: #dc2626;
}

.json-editor__surface {
  position: relative;
  width: 100%;
  min-width: 0;
}

.json-editor__host {
  width: 100%;
  height: var(--json-editor-height);
  min-height: 180px;
  border: 1px solid var(--studio-border);
  border-radius: 14px;
  overflow: hidden;
  background: #ffffff;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.65);
}

.json-editor__placeholder {
  position: absolute;
  top: 18px;
  left: 58px;
  color: var(--studio-text-soft);
  font: 13px/1.6 "Cascadia Code", "Consolas", monospace;
  pointer-events: none;
}

.json-editor__error {
  margin: 0;
  color: #dc2626;
  font-size: 12px;
}

@media (max-width: 720px) {
  .json-editor__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .json-editor__actions {
    justify-content: flex-start;
  }
}
</style>
