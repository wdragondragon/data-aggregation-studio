<template>
  <el-form class="http-reader-options-editor" :disabled="disabled">
    <MetaFormRenderer
      v-if="baseFields.length"
      :fields="baseFields"
      :model-value="modelValue"
      :dynamic-function-fields="baseDynamicFunctionFields"
      :disabled="disabled"
      @update:model-value="emitMergedValue($event)"
      @field-change="emit('dirty-key', $event)"
    />

    <div v-if="httpFields.length" class="http-reader-options">
      <div class="http-reader-options__summary">
        <div>
          <strong>{{ t("web.collectionTasks.httpOptionsGroupTitle") }}</strong>
          <p>{{ t("web.collectionTasks.httpOptionsGroupDescription") }}</p>
        </div>
      </div>

      <section
        v-for="key in httpFields"
        :key="key"
        class="http-reader-option"
        :class="`http-reader-option--${key}`"
      >
        <div class="http-reader-option__header">
          <div class="http-reader-option__title">
            <span class="http-reader-option__index">{{ sectionNumber(key) }}</span>
            <div>
              <strong>{{ fieldLabel(key) }}</strong>
              <p>{{ fieldDescription(key) }}</p>
            </div>
          </div>
          <div class="http-reader-option__toolbar">
            <el-button
              v-if="editorModes[key] === 'table'"
              size="small"
              plain
              :icon="Plus"
              @click="appendRow(key)"
            >
              {{ t("web.collectionTasks.httpOptionAddRow") }}
            </el-button>
            <el-button
              v-else
              size="small"
              plain
              @click="openRawDynamicFunctionDialog(key)"
            >
              {{ t("web.collectionTasks.httpDynamicFunctionRawButton") }}
            </el-button>
            <el-radio-group v-model="editorModes[key]" size="small">
              <el-radio-button value="table">{{ t("web.collectionTasks.httpOptionTableMode") }}</el-radio-button>
              <el-radio-button value="raw">{{ rawModeLabel(key) }}</el-radio-button>
            </el-radio-group>
          </div>
        </div>

        <div class="http-reader-option__body">
          <template v-if="editorModes[key] === 'table'">
            <HttpOptionKeyValueTable
              :rows="optionRows[key]"
              :key-placeholder="keyPlaceholder(key)"
              :value-placeholder="valuePlaceholder(key)"
              @update-row="(index, field, value) => updateRow(key, index, field, value)"
              @remove-row="(index) => removeRow(key, index)"
              @open-dynamic-function="(index) => openRowDynamicFunctionDialog(key, index)"
              @register-value-input-ref="(index, element) => registerRowValueInputRef(key, index, element)"
              @sync-value-selection="(index, event) => syncRowValueSelection(key, index, event)"
            />
            <p v-if="optionRows[key].length === 0" class="http-reader-option__empty">
              {{ t("web.collectionTasks.httpOptionEmpty") }}
            </p>
          </template>

          <template v-else>
            <HttpRawOptionEditor
              :model-value="rawText[key]"
              :rows="key === 'requestBody' ? 8 : 5"
              :placeholder="rawPlaceholder(key)"
              :parse-error="parseErrors[key]"
              @update:model-value="updateRawText(key, $event)"
              @register-input-ref="(element) => registerRawInputRef(key, element)"
              @sync-selection="(event) => syncRawSelection(key, event)"
            />
          </template>
        </div>
      </section>
    </div>

    <HttpDynamicFunctionDialog
      v-model:visible="dynamicFunctionDialogVisible"
      :initial-function-name="dynamicFunctionInitialName"
      :selected-snippet="selectedInputSnippet"
      :token-prefix="dynamicFunctionTokenPrefix"
      @confirm="confirmDynamicFunctionInsert"
    />
  </el-form>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from "vue";
import { Plus } from "@element-plus/icons-vue";
import { useI18n } from "vue-i18n";
import type { MetadataFieldDefinition } from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import HttpDynamicFunctionDialog from "@/components/http-request/HttpDynamicFunctionDialog.vue";
import HttpOptionKeyValueTable from "@/components/http-request/HttpOptionKeyValueTable.vue";
import HttpRawOptionEditor from "@/components/http-request/HttpRawOptionEditor.vue";

type HttpOptionKey = "header" | "params" | "requestBody";
type EditorMode = "table" | "raw";
type RowField = "name" | "value";
type DynamicFunctionTarget =
  | { mode: "row"; key: HttpOptionKey; index: number }
  | { mode: "raw"; key: HttpOptionKey };

interface KeyValueRow {
  name: string;
  value: string;
  hasOriginalValue?: boolean;
  originalText?: string;
  originalValue?: unknown;
}

interface DynamicInputRef {
  input?: HTMLInputElement;
  textarea?: HTMLTextAreaElement;
  focus?: () => void;
  $el?: HTMLElement;
}

interface DynamicFunctionSelection {
  start: number;
  end: number;
}

const httpOptionKeys: HttpOptionKey[] = ["header", "params", "requestBody"];
const objectStringKeys = new Set<HttpOptionKey>(["header", "params"]);

const props = withDefaults(
  defineProps<{
    fields: MetadataFieldDefinition[];
    modelValue: Record<string, unknown>;
    dynamicFunctionFields?: string[];
    disabled?: boolean;
  }>(),
  {
    fields: () => [],
    modelValue: () => ({}),
    dynamicFunctionFields: () => [],
    disabled: false,
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: Record<string, unknown>];
  "dirty-key": [fieldKey: string];
}>();

const { t } = useI18n();

const optionRows = reactive<Record<HttpOptionKey, KeyValueRow[]>>({
  header: [],
  params: [],
  requestBody: [],
});
const rawText = reactive<Record<HttpOptionKey, string>>({
  header: "{}",
  params: "{}",
  requestBody: "",
});
const editorModes = reactive<Record<HttpOptionKey, EditorMode>>({
  header: "table",
  params: "table",
  requestBody: "table",
});
const parseErrors = reactive<Record<HttpOptionKey, string>>({
  header: "",
  params: "",
  requestBody: "",
});
const pendingEmittedValues = reactive<Record<HttpOptionKey, string | undefined>>({
  header: undefined,
  params: undefined,
  requestBody: undefined,
});
const dynamicFunctionDialogVisible = ref(false);
const dynamicFunctionInitialName = ref("dyn_page");
const dynamicFunctionTokenPrefix = ref("");
const selectedInputSnippet = ref("");
const dynamicFunctionTarget = ref<DynamicFunctionTarget | null>(null);
const rowValueInputRefs = new Map<string, DynamicInputRef>();
const rawInputRefs = new Map<HttpOptionKey, DynamicInputRef>();
const rowValueSelections = ref<Record<string, DynamicFunctionSelection>>({});
const rawSelections = ref<Partial<Record<HttpOptionKey, DynamicFunctionSelection>>>({});

const httpFieldSet = computed(() =>
  new Set(props.fields
    .map((field) => field.fieldKey)
    .filter((key): key is HttpOptionKey => isHttpOptionKey(key))),
);
const httpFields = computed(() => httpOptionKeys.filter((key) => httpFieldSet.value.has(key)));
const baseFields = computed(() => props.fields.filter((field) => !isHttpOptionKey(field.fieldKey)));
const baseDynamicFunctionFields = computed(() =>
  props.dynamicFunctionFields.filter((fieldKey) => !isHttpOptionKey(fieldKey)),
);

watch(
  () => props.modelValue?.header,
  (value) => syncFromModel("header", value),
  { immediate: true },
);
watch(
  () => props.modelValue?.params,
  (value) => syncFromModel("params", value),
  { immediate: true },
);
watch(
  () => props.modelValue?.requestBody,
  (value) => syncFromModel("requestBody", value),
  { immediate: true },
);

function isHttpOptionKey(key: unknown): key is HttpOptionKey {
  return key === "header" || key === "params" || key === "requestBody";
}

function emitMergedValue(value: Record<string, unknown>) {
  emit("update:modelValue", value ?? {});
}

function emitOptionValue(key: HttpOptionKey, value: string) {
  pendingEmittedValues[key] = value;
  emit("dirty-key", key);
  emit("update:modelValue", {
    ...(props.modelValue ?? {}),
    [key]: value,
  });
}

function syncFromModel(key: HttpOptionKey, value: unknown) {
  const text = stringifyOptionValue(key, value);
  if (pendingEmittedValues[key] === text) {
    pendingEmittedValues[key] = undefined;
    return;
  }
  rawText[key] = text;
  const parsed = parseObjectText(text);
  if (parsed.ok) {
    optionRows[key] = toRows(parsed.value);
    parseErrors[key] = "";
    editorModes[key] = "table";
    return;
  }
  optionRows[key] = [];
  parseErrors[key] = objectStringKeys.has(key)
    ? t("web.collectionTasks.httpOptionInvalidObject")
    : "";
  editorModes[key] = "raw";
}

function stringifyOptionValue(key: HttpOptionKey, value: unknown) {
  if (value === undefined || value === null) {
    return objectStringKeys.has(key) ? "{}" : "";
  }
  if (typeof value === "string") {
    return value;
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function parseObjectText(value: string): { ok: true; value: Record<string, unknown> } | { ok: false } {
  const text = value.trim();
  if (!text) {
    return { ok: true, value: {} };
  }
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return { ok: true, value: parsed as Record<string, unknown> };
    }
  } catch {
    return { ok: false };
  }
  return { ok: false };
}

function toRows(value: Record<string, unknown>): KeyValueRow[] {
  return Object.entries(value).map(([name, rowValue]) => {
    const text = stringifyRowValue(rowValue);
    return {
      name,
      value: text,
      hasOriginalValue: true,
      originalText: text,
      originalValue: rowValue,
    };
  });
}

function stringifyRowValue(value: unknown) {
  if (value === undefined || value === null) {
    return "";
  }
  if (typeof value === "string") {
    return value;
  }
  if (typeof value === "object") {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

function appendRow(key: HttpOptionKey) {
  optionRows[key] = [
    ...optionRows[key],
    { name: "", value: "" },
  ];
}

function updateRow(key: HttpOptionKey, index: number, field: RowField, value: unknown) {
  const rows = [...optionRows[key]];
  rows[index] = {
    ...(rows[index] ?? { name: "", value: "" }),
    [field]: String(value ?? ""),
  };
  optionRows[key] = rows;
  commitRows(key);
}

function removeRow(key: HttpOptionKey, index: number) {
  const rows = [...optionRows[key]];
  rows.splice(index, 1);
  optionRows[key] = rows;
  commitRows(key);
}

function commitRows(key: HttpOptionKey) {
  const objectValue: Record<string, unknown> = {};
  for (const row of optionRows[key]) {
    const name = row.name.trim();
    if (!name) {
      continue;
    }
    objectValue[name] = resolveRowValue(key, row);
  }
  const nextValue = JSON.stringify(objectValue);
  rawText[key] = objectStringKeys.has(key) || Object.keys(objectValue).length > 0 ? nextValue : "";
  parseErrors[key] = "";
  emitOptionValue(key, rawText[key]);
}

function resolveRowValue(key: HttpOptionKey, row: KeyValueRow): unknown {
  if (key !== "requestBody" || !row.hasOriginalValue) {
    return row.value;
  }
  if (row.value === row.originalText) {
    return row.originalValue;
  }
  if (row.originalValue === null || typeof row.originalValue !== "string") {
    try {
      return JSON.parse(row.value);
    } catch {
      return row.value;
    }
  }
  return row.value;
}

function updateRawText(key: HttpOptionKey, value: string | number) {
  const nextValue = String(value ?? "");
  rawText[key] = nextValue;
  const parsed = parseObjectText(nextValue);
  if (parsed.ok) {
    optionRows[key] = toRows(parsed.value);
    parseErrors[key] = "";
  } else {
    parseErrors[key] = objectStringKeys.has(key)
      ? t("web.collectionTasks.httpOptionInvalidObject")
      : "";
  }
  if (objectStringKeys.has(key)) {
    emitOptionValue(key, nextValue.trim() ? nextValue : "{}");
    return;
  }
  emitOptionValue(key, nextValue);
}

function rowRefKey(key: HttpOptionKey, index: number) {
  return `${key}:${index}`;
}

function registerRowValueInputRef(key: HttpOptionKey, index: number, element: unknown) {
  const refKey = rowRefKey(key, index);
  if (element) {
    rowValueInputRefs.set(refKey, element as DynamicInputRef);
    return;
  }
  rowValueInputRefs.delete(refKey);
}

function registerRawInputRef(key: HttpOptionKey, element: unknown) {
  if (element) {
    rawInputRefs.set(key, element as DynamicInputRef);
    return;
  }
  rawInputRefs.delete(key);
}

function syncRowValueSelection(key: HttpOptionKey, index: number, event?: Event) {
  const refKey = rowRefKey(key, index);
  const row = optionRows[key][index];
  rowValueSelections.value = {
    ...rowValueSelections.value,
    [refKey]: readSelection(event, row?.value.length ?? 0, rowValueInputRefs.get(refKey)),
  };
}

function syncRawSelection(key: HttpOptionKey, event?: Event) {
  rawSelections.value = {
    ...rawSelections.value,
    [key]: readSelection(event, rawText[key].length, rawInputRefs.get(key)),
  };
}

function readSelection(event: Event | undefined, fallbackLength: number, inputRef?: DynamicInputRef): DynamicFunctionSelection {
  const target = event?.target;
  if (isTextInput(target)) {
    return {
      start: target.selectionStart ?? fallbackLength,
      end: target.selectionEnd ?? fallbackLength,
    };
  }
  const input = resolveInputElement(inputRef);
  if (input && document.activeElement === input) {
    return {
      start: input.selectionStart ?? fallbackLength,
      end: input.selectionEnd ?? fallbackLength,
    };
  }
  return {
    start: fallbackLength,
    end: fallbackLength,
  };
}

function isTextInput(value: unknown): value is HTMLInputElement | HTMLTextAreaElement {
  return value instanceof HTMLInputElement || value instanceof HTMLTextAreaElement;
}

function resolveInputElement(inputRef?: DynamicInputRef) {
  if (!inputRef) {
    return null;
  }
  return inputRef.input
    ?? inputRef.textarea
    ?? inputRef.$el?.querySelector<HTMLInputElement | HTMLTextAreaElement>("input, textarea")
    ?? null;
}

function openRowDynamicFunctionDialog(key: HttpOptionKey, index: number) {
  syncRowValueSelection(key, index);
  openDynamicFunctionDialog({ mode: "row", key, index });
}

function openRawDynamicFunctionDialog(key: HttpOptionKey) {
  syncRawSelection(key);
  openDynamicFunctionDialog({ mode: "raw", key });
}

function openDynamicFunctionDialog(target: DynamicFunctionTarget) {
  dynamicFunctionTarget.value = target;
  dynamicFunctionInitialName.value = defaultFunctionForTarget(target);
  selectedInputSnippet.value = resolveSelectedInputSnippet(target);
  dynamicFunctionTokenPrefix.value = defaultTokenPrefix();
  dynamicFunctionDialogVisible.value = true;
}

function defaultFunctionForTarget(target: DynamicFunctionTarget) {
  return target.key === "header" ? "dyn_from_http_token" : "dyn_page";
}

function resolveTargetValue(target: DynamicFunctionTarget) {
  if (target.mode === "raw") {
    return rawText[target.key];
  }
  return optionRows[target.key][target.index]?.value ?? "";
}

function resolveTargetSelection(target: DynamicFunctionTarget): DynamicFunctionSelection {
  const currentValue = resolveTargetValue(target);
  if (target.mode === "raw") {
    const input = resolveInputElement(rawInputRefs.get(target.key));
    if (input && document.activeElement === input) {
      return {
        start: input.selectionStart ?? currentValue.length,
        end: input.selectionEnd ?? currentValue.length,
      };
    }
    return rawSelections.value[target.key] ?? {
      start: currentValue.length,
      end: currentValue.length,
    };
  }
  const refKey = rowRefKey(target.key, target.index);
  const input = resolveInputElement(rowValueInputRefs.get(refKey));
  if (input && document.activeElement === input) {
    return {
      start: input.selectionStart ?? currentValue.length,
      end: input.selectionEnd ?? currentValue.length,
    };
  }
  return rowValueSelections.value[refKey] ?? {
    start: currentValue.length,
    end: currentValue.length,
  };
}

function resolveSelectedInputSnippet(target: DynamicFunctionTarget) {
  const range = resolveTargetSelection(target);
  const currentValue = resolveTargetValue(target);
  return range.end > range.start ? currentValue.slice(range.start, range.end) : "";
}

function defaultTokenPrefix() {
  const target = dynamicFunctionTarget.value;
  if (!target || target.mode !== "row" || target.key !== "header") {
    return "";
  }
  const row = optionRows.header[target.index];
  if (!row || row.value.trim()) {
    return "";
  }
  return row.name.trim().toLowerCase() === "authorization" ? "Bearer " : "";
}

async function confirmDynamicFunctionInsert(expression: string) {
  const target = dynamicFunctionTarget.value;
  if (!target) {
    return;
  }
  await insertDynamicFunctionExpression(target, expression);
  dynamicFunctionDialogVisible.value = false;
}

async function insertDynamicFunctionExpression(target: DynamicFunctionTarget, content: string) {
  const currentValue = resolveTargetValue(target);
  const range = resolveTargetSelection(target);
  const nextValue = `${currentValue.slice(0, range.start)}${content}${currentValue.slice(range.end)}`;
  const nextCursor = range.start + content.length;
  if (target.mode === "raw") {
    updateRawText(target.key, nextValue);
    rawSelections.value = {
      ...rawSelections.value,
      [target.key]: { start: nextCursor, end: nextCursor },
    };
    await nextTick();
    const input = resolveInputElement(rawInputRefs.get(target.key));
    input?.focus();
    input?.setSelectionRange(nextCursor, nextCursor);
    return;
  }
  updateRow(target.key, target.index, "value", nextValue);
  const refKey = rowRefKey(target.key, target.index);
  rowValueSelections.value = {
    ...rowValueSelections.value,
    [refKey]: { start: nextCursor, end: nextCursor },
  };
  await nextTick();
  const input = resolveInputElement(rowValueInputRefs.get(refKey));
  input?.focus();
  input?.setSelectionRange(nextCursor, nextCursor);
}

function findField(key: HttpOptionKey) {
  return props.fields.find((field) => field.fieldKey === key);
}

function fieldLabel(key: HttpOptionKey) {
  return findField(key)?.fieldName || key;
}

function fieldDescription(key: HttpOptionKey) {
  const field = findField(key);
  const description = field?.description?.trim();
  const label = fieldLabel(key).trim();
  if (description && description !== label && description !== key) {
    return description;
  }
  if (key === "header") {
    return t("web.collectionTasks.httpHeaderVisualDescription");
  }
  if (key === "params") {
    return t("web.collectionTasks.httpParamsVisualDescription");
  }
  return t("web.collectionTasks.httpBodyVisualDescription");
}

function sectionNumber(key: HttpOptionKey) {
  return httpFields.value.indexOf(key) + 1;
}

function rawModeLabel(key: HttpOptionKey) {
  return key === "requestBody"
    ? t("web.collectionTasks.httpOptionRawTextMode")
    : t("web.collectionTasks.httpOptionRawJsonMode");
}

function keyPlaceholder(key: HttpOptionKey) {
  if (key === "header") {
    return "Authorization";
  }
  if (key === "params") {
    return "pageNo";
  }
  return "name";
}

function valuePlaceholder(key: HttpOptionKey) {
  if (key === "header") {
    return "Bearer {dyn_from_http_token(...)}";
  }
  if (key === "params") {
    return "{dyn_page}";
  }
  return t("web.collectionTasks.httpOptionValuePlaceholder");
}

function rawPlaceholder(key: HttpOptionKey) {
  if (key === "requestBody") {
    return "{\n  \"name\": \"value\"\n}";
  }
  return "{\n  \"key\": \"value\"\n}";
}
</script>

<style scoped>
.http-reader-options-editor {
  display: grid;
  gap: 18px;
}

.http-reader-options {
  display: grid;
  gap: 18px;
}

.http-reader-options__summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 0 13px;
  border-bottom: 1px solid var(--studio-border);
}

.http-reader-options__summary strong {
  display: block;
  margin-bottom: 4px;
  color: var(--studio-text);
  font-size: 15px;
  line-height: 1.35;
}

.http-reader-options__summary p,
.http-reader-option__title p {
  margin: 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.55;
}

.http-reader-option {
  position: relative;
  display: grid;
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--studio-border);
  border-left: 4px solid var(--studio-primary);
  border-radius: 8px;
  background: var(--studio-surface-strong, #fff);
}

.http-reader-option--params {
  border-left-color: var(--studio-accent);
}

.http-reader-option--requestBody {
  border-left-color: var(--studio-success);
}

.http-reader-option__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid rgba(64, 113, 187, 0.12);
  background: rgba(15, 35, 66, 0.025);
}

.http-reader-option__title {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px;
  min-width: 0;
}

.http-reader-option__index {
  display: inline-grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border: 1px solid rgba(37, 99, 235, 0.18);
  border-radius: 50%;
  background: rgba(37, 99, 235, 0.08);
  color: var(--studio-primary);
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
}

.http-reader-option__title strong {
  display: block;
  margin-bottom: 4px;
  color: var(--studio-text);
  font-size: 14px;
  line-height: 1.35;
}

.http-reader-option__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.http-reader-option__body {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 14px 16px 16px;
  background: #fff;
}

.http-reader-option__table {
  overflow: hidden;
  border-radius: 6px;
}

.http-reader-option__table :deep(.cell) {
  overflow: visible;
}

.http-reader-option__table :deep(th.el-table__cell) {
  background: rgba(16, 78, 139, 0.045);
}

.http-reader-option__value-input {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.http-reader-option__empty,
.http-reader-option__error {
  margin: 0;
  font-size: 12px;
  line-height: 1.4;
}

.http-reader-option__empty {
  color: var(--studio-text-soft);
}

.http-reader-option__error {
  color: var(--el-color-danger);
}

@media (max-width: 900px) {
  .http-reader-option__header {
    display: grid;
  }

  .http-reader-option__toolbar {
    justify-content: flex-start;
  }
}
</style>
