<template>
  <div class="xml-editor" :style="{ '--xml-editor-height': normalizedHeight }">
    <div class="xml-editor__header">
      <div class="xml-editor__copy">
        <strong>{{ title }}</strong>
        <span>{{ resolvedDescription }}</span>
      </div>
      <div class="xml-editor__actions">
        <span class="xml-editor__state" :class="{ invalid: Boolean(errorMessage), readonly }">
          {{ stateText }}
        </span>
        <el-button size="small" link @click="formatContent">格式化</el-button>
        <el-button v-if="!readonly" size="small" link @click="clearContent">清空</el-button>
      </div>
    </div>

    <div class="xml-editor__surface">
      <div ref="editorHostRef" class="xml-editor__host"></div>
      <div v-if="showPlaceholder" class="xml-editor__placeholder">
        {{ placeholder }}
      </div>
    </div>
    <p v-if="errorMessage" class="xml-editor__error">{{ errorMessage }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import type * as MonacoType from "monaco-editor";
import { ensureMonacoSetup } from "@/components/data-development/monacoSetup";
import { formatXmlText } from "@/components/open-service/openServiceDebugSupport";

const props = withDefaults(defineProps<{
  title?: string;
  description?: string;
  placeholder?: string;
  readonly?: boolean;
  height?: string;
  allowFragment?: boolean;
}>(), {
  title: "XML 编辑器",
  description: "",
  placeholder: "<root></root>",
  readonly: false,
  height: "260px",
  allowFragment: false,
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
const resolvedDescription = computed(() => props.description || (props.readonly ? "只读展示 XML 内容。" : "支持 XML 高亮、校验与格式化。"));
const stateText = computed(() => {
  if (errorMessage.value) {
    return "XML 异常";
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
    "xml",
    monaco.Uri.parse(`inmemory://studio/xml-editor/${Date.now()}-${Math.random().toString(36).slice(2)}.xml`),
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
    validateXml(modelValue.value);
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
  validateXml(modelValue.value);
}

function destroyEditor() {
  editorRef.value?.dispose();
  editorRef.value = null;
  textModelRef.value?.dispose();
  textModelRef.value = null;
}

function validateXml(value: string) {
  const text = String(value || "").trim();
  if (!text) {
    errorMessage.value = "";
    return true;
  }
  const result = formatXmlText(xmlTextForValidation(text), "XML");
  errorMessage.value = result.ok ? "" : result.error;
  return result.ok;
}

function formatContent() {
  const text = String(modelValue.value || "").trim();
  if (!text) {
    setEditorValue("");
    errorMessage.value = "";
    return;
  }
  const result = formatXmlText(xmlTextForValidation(text), "XML");
  if (!result.ok) {
    errorMessage.value = result.error;
    return;
  }
  setEditorValue(props.allowFragment ? unwrapFragmentValidationText(result.value) : result.value);
  errorMessage.value = "";
}

function clearContent() {
  setEditorValue("");
  errorMessage.value = "";
}

function xmlTextForValidation(value: string) {
  return props.allowFragment ? `<__fragment>${value}</__fragment>` : value;
}

function unwrapFragmentValidationText(value: string) {
  return value
    .replace(/^<__fragment>\s*/u, "")
    .replace(/\s*<\/__fragment>$/u, "")
    .trim();
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
    validateXml(normalized);
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
.xml-editor {
  display: grid;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.xml-editor__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.xml-editor__copy {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.xml-editor__copy strong {
  color: var(--studio-text);
  font-size: 13px;
}

.xml-editor__copy span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.xml-editor__actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.xml-editor__state {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.08);
  color: var(--studio-primary);
  font-size: 12px;
}

.xml-editor__state.readonly {
  background: rgba(100, 116, 139, 0.12);
  color: #64748b;
}

.xml-editor__state.invalid {
  background: rgba(239, 68, 68, 0.1);
  color: #dc2626;
}

.xml-editor__surface {
  position: relative;
  width: 100%;
  min-width: 0;
}

.xml-editor__host {
  width: 100%;
  height: var(--xml-editor-height);
  min-height: 180px;
  border: 1px solid var(--studio-border);
  border-radius: 14px;
  overflow: hidden;
  background: #ffffff;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.65);
}

.xml-editor__placeholder {
  position: absolute;
  top: 18px;
  left: 58px;
  color: var(--studio-text-soft);
  font: 13px/1.6 "Cascadia Code", "Consolas", monospace;
  pointer-events: none;
  white-space: pre-wrap;
}

.xml-editor__error {
  margin: 0;
  color: #dc2626;
  font-size: 12px;
}

@media (max-width: 720px) {
  .xml-editor__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .xml-editor__actions {
    justify-content: flex-start;
  }
}
</style>
