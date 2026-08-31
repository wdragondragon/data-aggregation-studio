<template>
  <div class="meta-form">
    <section
      v-for="section in scopedSections"
      :key="section.key"
      class="meta-form__section"
    >
      <header class="meta-form__section-header">
        <h4>{{ section.title }}</h4>
        <span>{{ t("common.fields", { count: section.fields.length }) }}</span>
      </header>

      <el-form label-position="top" :disabled="disabled">
        <div class="meta-form__grid">
          <el-form-item
            v-for="field in section.fields"
            :key="field.fieldKey"
            :label="field.fieldName"
            :required="field.required"
            :class="{ 'meta-form__item--dynamic': isDynamicFunctionField(field) }"
          >
            <el-select
              v-if="field.componentType === 'SELECT'"
              :key="`${field.fieldKey}:${field.valueType}`"
              :model-value="fieldValue(field.fieldKey)"
              :multiple="isArraySelectField(field)"
              :collapse-tags="isArraySelectField(field)"
              :collapse-tags-tooltip="isArraySelectField(field)"
              clearable
              filterable
              :placeholder="field.placeholder ?? t('metaForm.selectField', { fieldName: field.fieldName })"
              @update:model-value="updateSelectField(field, $event)"
            >
              <el-option
                v-for="option in field.options ?? []"
                :key="option"
                :label="option"
                :value="option"
              />
            </el-select>
            <div v-else-if="isDynamicFunctionField(field)" class="meta-form__dynamic-input">
              <el-input
                :ref="(element) => registerDynamicInputRef(field.fieldKey, element)"
                class="meta-form__dynamic-input-control"
                v-bind="resolveProps(field)"
                :model-value="textFieldValue(field.fieldKey)"
                @update:model-value="updateField(field.fieldKey, $event)"
                @click="syncDynamicInputSelection(field.fieldKey, $event)"
                @focus="syncDynamicInputSelection(field.fieldKey, $event)"
                @keyup="syncDynamicInputSelection(field.fieldKey, $event)"
                @select="syncDynamicInputSelection(field.fieldKey, $event)"
              />
              <el-button plain size="small" @click="openDynamicFunctionDialog(field.fieldKey)">
                {{ t("metaForm.dynamicFunctionButton") }}
              </el-button>
            </div>
            <component
              v-else
              :is="resolveComponent(field)"
              v-bind="resolveProps(field)"
              :model-value="componentFieldValue(field)"
              @update:model-value="updateField(field.fieldKey, $event)"
            />
            <p v-if="isSavedSensitiveField(field.fieldKey)" class="meta-form__saved-sensitive-hint">
              {{ t("metaForm.savedSensitiveHint") }}
            </p>
          </el-form-item>
        </div>
      </el-form>
    </section>

    <el-dialog
      v-model="dynamicFunctionDialogVisible"
      :title="t('metaForm.dynamicFunctionDialogTitle')"
      width="880px"
      destroy-on-close
      class="dynamic-function-dialog"
    >
      <div class="dynamic-function-dialog__layout">
        <div class="dynamic-function-dialog__sidebar">
          <div class="dynamic-function-dialog__sidebar-title">{{ t("metaForm.dynamicFunctionListTitle") }}</div>
          <div class="dynamic-function-list">
            <button
              v-for="item in dynamicFunctionCatalog"
              :key="item.name"
              type="button"
              class="dynamic-function-item"
              :class="{ 'dynamic-function-item--active': selectedDynamicFunctionName === item.name }"
              @click="selectedDynamicFunctionName = item.name"
            >
              <strong>{{ item.label }}</strong>
              <span>{{ item.summary }}</span>
            </button>
          </div>
        </div>

        <div class="dynamic-function-dialog__content">
          <div class="dynamic-function-card">
            <div class="dynamic-function-card__header">
              <div>
                <h4>{{ selectedDynamicFunction.label }}</h4>
                <p>{{ selectedDynamicFunction.summary }}</p>
              </div>
              <code>{{ selectedDynamicFunction.signature }}</code>
            </div>
            <p class="dynamic-function-card__description">{{ selectedDynamicFunction.description }}</p>
            <div class="dynamic-function-card__meta">
              <span>{{ t("metaForm.dynamicFunctionReturn") }}{{ selectedDynamicFunction.returnDescription }}</span>
              <span>{{ t("metaForm.dynamicFunctionExample") }}{{ selectedDynamicFunction.example }}</span>
            </div>
          </div>

          <div v-if="selectedInputSnippet" class="dynamic-function-selection-tip">
            {{ t("metaForm.dynamicFunctionSelected") }}<code>{{ selectedInputSnippet }}</code>
          </div>

          <div class="dynamic-function-args">
            <el-form-item
              v-for="param in selectedDynamicFunction.params"
              :key="param.key"
              :label="param.label"
              class="dynamic-function-args__item"
            >
              <el-input v-model="dynamicFunctionArgs[param.key]" :placeholder="param.placeholder" />
              <div class="field-hint">
                {{ param.description }}
                <span v-if="param.required === false">{{ t("metaForm.dynamicFunctionOptional") }}</span>
              </div>
            </el-form-item>
          </div>

          <div class="dynamic-function-preview">
            <span class="dynamic-function-preview__label">{{ t("metaForm.dynamicFunctionPreview") }}</span>
            <pre>{{ dynamicFunctionPreview }}</pre>
            <div class="field-hint">{{ t("metaForm.dynamicFunctionPreviewHint") }}</div>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="meta-form__dialog-footer">
          <el-button @click="dynamicFunctionDialogVisible = false">{{ t("common.cancel") }}</el-button>
          <el-button type="primary" @click="confirmDynamicFunctionInsert">{{ t("metaForm.dynamicFunctionConfirm") }}</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from "vue";
import { ElInput, ElInputNumber, ElMessage, ElSelect, ElSwitch } from "element-plus";
import { useI18n } from "vue-i18n";
import type { MetadataFieldDefinition } from "@studio/api-sdk";
import { dynamicFunctionCatalog, type DynamicFunctionParamSchema, type DynamicFunctionSchema } from "./dynamicFunctions";
import { formatJsonEditorValue } from "./jsonEditorValue";

interface DynamicInputRef {
  input?: HTMLInputElement;
  textarea?: HTMLTextAreaElement;
  focus?: () => void;
  $el?: HTMLElement;
}

const props = withDefaults(
  defineProps<{
    fields: MetadataFieldDefinition[];
    modelValue: Record<string, unknown>;
    dynamicFunctionFields?: string[];
    savedSensitiveFieldKeys?: string[];
    disabled?: boolean;
  }>(),
  {
    fields: () => [],
    modelValue: () => ({}),
    dynamicFunctionFields: () => [],
    savedSensitiveFieldKeys: () => [],
    disabled: false,
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: Record<string, unknown>];
  "field-change": [fieldKey: string];
}>();

const { t } = useI18n();
const localValue = computed(() => props.modelValue ?? {});
const dynamicFunctionDialogVisible = ref(false);
const selectedDynamicFunctionName = ref("getCurrentTime");
const selectedInputSnippet = ref("");
const dynamicFunctionTargetFieldKey = ref("");
const dynamicFunctionArgs = reactive<Record<string, string>>({});
const dynamicInputRefs = new Map<string, DynamicInputRef>();
const dynamicInputSelections = ref<Record<string, { start: number; end: number }>>({});

const scopedSections = computed(() => {
  const technical = props.fields.filter((field) => field.scope === "TECHNICAL" || !field.scope);
  const business = props.fields.filter((field) => field.scope === "BUSINESS");
  return [
    { key: "technical", title: t("metaForm.technicalTitle"), fields: technical },
    { key: "business", title: t("metaForm.businessTitle"), fields: business },
  ].filter((section) => section.fields.length > 0);
});

const selectedDynamicFunction = computed(() =>
  dynamicFunctionCatalog.find((item) => item.name === selectedDynamicFunctionName.value) ?? dynamicFunctionCatalog[0],
);

const dynamicFunctionPreview = computed(() => buildDynamicFunctionExpression());

function updateField(fieldKey: string, value: unknown) {
  emit("field-change", fieldKey);
  emit("update:modelValue", {
    ...localValue.value,
    [fieldKey]: value,
  });
}

function updateSelectField(field: MetadataFieldDefinition, value: unknown) {
  updateField(field.fieldKey, isArraySelectField(field) ? normalizeArrayValue(value) : value);
}

function fieldValue(fieldKey: string) {
  const field = props.fields.find((item) => item.fieldKey === fieldKey);
  const value = localValue.value[fieldKey];
  if (field && isArraySelectField(field)) {
    return normalizeArrayValue(value);
  }
  return value as string | number | boolean | Record<string, unknown> | string[] | undefined;
}

function componentFieldValue(field: MetadataFieldDefinition) {
  const value = fieldValue(field.fieldKey);
  return field.componentType === "JSON_EDITOR" ? formatJsonEditorValue(value) : value;
}

function isSavedSensitiveField(fieldKey: string) {
  return props.savedSensitiveFieldKeys.includes(fieldKey);
}

function textFieldValue(fieldKey: string) {
  return resolveFieldTextValue(fieldKey);
}

function isArraySelectField(field: MetadataFieldDefinition) {
  return field.componentType === "SELECT" && field.valueType === "ARRAY";
}

function isDynamicFunctionField(field: MetadataFieldDefinition) {
  if (!props.dynamicFunctionFields.includes(field.fieldKey)) {
    return false;
  }
  return !["NUMBER", "SELECT", "SWITCH"].includes(field.componentType ?? "");
}

function normalizeArrayValue(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => String(item)).filter(Boolean);
  }
  if (value === undefined || value === null) {
    return [];
  }
  const text = String(value).trim();
  if (!text) {
    return [];
  }
  try {
    const parsed = JSON.parse(text) as unknown;
    if (Array.isArray(parsed)) {
      return parsed.map((item) => String(item)).filter(Boolean);
    }
  } catch (error) {
    // Legacy values may be stored as a plain field name instead of JSON.
  }
  return [text];
}

function resolveComponent(field: MetadataFieldDefinition) {
  switch (field.componentType) {
    case "NUMBER":
      return ElInputNumber;
    case "SELECT":
      return ElSelect;
    case "SWITCH":
      return ElSwitch;
    default:
      return ElInput;
  }
}

function resolveProps(field: MetadataFieldDefinition) {
  switch (field.componentType) {
    case "PASSWORD":
      return {
        type: "password",
        showPassword: true,
        placeholder: field.placeholder ?? t("metaForm.enterField", { fieldName: field.fieldName }),
      };
    case "TEXTAREA":
    case "JSON_EDITOR":
    case "SQL_EDITOR":
    case "CODE_EDITOR":
      return {
        type: "textarea",
        rows: 5,
        placeholder: field.placeholder ?? t("metaForm.enterField", { fieldName: field.fieldName }),
      };
    case "SWITCH":
      return {
        inlinePrompt: true,
        activeText: t("common.on"),
        inactiveText: t("common.off"),
      };
    case "NUMBER":
      return {
        controlsPosition: "right",
        placeholder: field.placeholder ?? t("metaForm.enterField", { fieldName: field.fieldName }),
      };
    default:
      return {
        placeholder: field.placeholder ?? t("metaForm.enterField", { fieldName: field.fieldName }),
      };
  }
}

function registerDynamicInputRef(fieldKey: string, element: unknown) {
  if (!element) {
    dynamicInputRefs.delete(fieldKey);
    return;
  }
  dynamicInputRefs.set(fieldKey, element as DynamicInputRef);
}

function resolveNativeInput(fieldKey: string) {
  const instance = dynamicInputRefs.get(fieldKey);
  return instance?.input
    ?? instance?.textarea
    ?? instance?.$el?.querySelector<HTMLInputElement | HTMLTextAreaElement>("input, textarea")
    ?? null;
}

function resolveFieldTextValue(fieldKey: string) {
  const value = localValue.value[fieldKey];
  const field = props.fields.find((item) => item.fieldKey === fieldKey);
  if (field?.componentType === "JSON_EDITOR") {
    return formatJsonEditorValue(value);
  }
  return value === undefined || value === null ? "" : String(value);
}

function syncDynamicInputSelection(fieldKey: string, event: Event) {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | null;
  const input = target?.selectionStart === undefined ? resolveNativeInput(fieldKey) : target;
  if (!input) {
    return;
  }
  dynamicInputSelections.value = {
    ...dynamicInputSelections.value,
    [fieldKey]: {
      start: input.selectionStart ?? input.value.length,
      end: input.selectionEnd ?? input.value.length,
    },
  };
}

function resolveDynamicInputSelectionRange(fieldKey: string) {
  const input = resolveNativeInput(fieldKey);
  const currentValue = resolveFieldTextValue(fieldKey);
  if (input && document.activeElement === input) {
    return {
      start: input.selectionStart ?? currentValue.length,
      end: input.selectionEnd ?? currentValue.length,
    };
  }
  return dynamicInputSelections.value[fieldKey] ?? {
    start: currentValue.length,
    end: currentValue.length,
  };
}

function resolveSelectedInputSnippet(fieldKey: string) {
  const currentValue = resolveFieldTextValue(fieldKey);
  const range = resolveDynamicInputSelectionRange(fieldKey);
  return range.end > range.start ? currentValue.slice(range.start, range.end) : "";
}

function resetDynamicFunctionArgs() {
  const nextValues: Record<string, string> = {};
  selectedDynamicFunction.value.params.forEach((param) => {
    const selectedValue = param.useSelectedSnippet && selectedInputSnippet.value ? selectedInputSnippet.value : "";
    nextValues[param.key] = selectedValue || param.defaultValue || "";
  });
  Object.keys(dynamicFunctionArgs).forEach((key) => {
    delete dynamicFunctionArgs[key];
  });
  Object.assign(dynamicFunctionArgs, nextValues);
}

function openDynamicFunctionDialog(fieldKey: string) {
  dynamicFunctionTargetFieldKey.value = fieldKey;
  selectedInputSnippet.value = resolveSelectedInputSnippet(fieldKey);
  resetDynamicFunctionArgs();
  dynamicFunctionDialogVisible.value = true;
}

function formatDynamicFunctionArg(param: DynamicFunctionParamSchema, rawValue: string | undefined) {
  const trimmed = rawValue?.trim() ?? "";
  if (!trimmed) {
    return param.required === false ? null : "";
  }
  if (param.autoQuote === false) {
    return trimmed;
  }
  if (trimmed.startsWith("'")
    || trimmed.startsWith('"')
    || trimmed.startsWith("$")
    || trimmed.startsWith("${")) {
    return trimmed;
  }
  return `'${trimmed}'`;
}

function buildDynamicFunctionExpression() {
  const args = selectedDynamicFunction.value.params
    .map((param) => formatDynamicFunctionArg(param, dynamicFunctionArgs[param.key]))
    .filter((value): value is string => value !== null);
  return `$${selectedDynamicFunction.value.name}(${args.join(", ")})`;
}

async function insertIntoDynamicField(fieldKey: string, content: string) {
  const currentValue = resolveFieldTextValue(fieldKey);
  const range = resolveDynamicInputSelectionRange(fieldKey);
  const nextValue = `${currentValue.slice(0, range.start)}${content}${currentValue.slice(range.end)}`;
  updateField(fieldKey, nextValue);
  const nextCursor = range.start + content.length;
  dynamicInputSelections.value = {
    ...dynamicInputSelections.value,
    [fieldKey]: { start: nextCursor, end: nextCursor },
  };
  await nextTick();
  const input = resolveNativeInput(fieldKey);
  input?.focus();
  input?.setSelectionRange(nextCursor, nextCursor);
}

async function confirmDynamicFunctionInsert() {
  const fieldKey = dynamicFunctionTargetFieldKey.value;
  if (!fieldKey) {
    return;
  }
  const missingParam = selectedDynamicFunction.value.params.find((param) =>
    param.required !== false && !(dynamicFunctionArgs[param.key] ?? "").trim());
  if (missingParam) {
    ElMessage.warning(t("metaForm.dynamicFunctionMissingParam", { label: missingParam.label }));
    return;
  }
  await insertIntoDynamicField(fieldKey, buildDynamicFunctionExpression());
  dynamicFunctionDialogVisible.value = false;
}

watch(selectedDynamicFunctionName, () => {
  if (!dynamicFunctionDialogVisible.value) {
    return;
  }
  resetDynamicFunctionArgs();
});
</script>

<style scoped>
.meta-form {
  display: grid;
  gap: 18px;
}

.meta-form__section {
  border: 1px solid var(--studio-border);
  border-radius: 18px;
  padding: 16px 18px 4px;
  background: rgba(255, 255, 255, 0.66);
}

.meta-form__section-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.meta-form__section-header h4 {
  margin: 0;
  font-size: 16px;
}

.meta-form__section-header span {
  font-size: 12px;
  color: var(--studio-text-soft);
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.meta-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.meta-form__saved-sensitive-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--studio-text-soft);
}

.meta-form__dynamic-input {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: start;
  width: 100%;
}

.meta-form__item--dynamic {
  grid-column: 1 / -1;
}

.meta-form__dynamic-input-control {
  min-width: 0;
}

.meta-form__dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.dynamic-function-dialog__layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 20px;
}

.dynamic-function-dialog__sidebar {
  border-right: 1px solid var(--studio-border);
  padding-right: 16px;
}

.dynamic-function-dialog__sidebar-title {
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--studio-text-soft);
}

.dynamic-function-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.dynamic-function-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 12px 14px;
  border: 1px solid var(--studio-border);
  border-radius: 12px;
  background: #fff;
  color: var(--studio-text);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.dynamic-function-item strong {
  font-size: 14px;
}

.dynamic-function-item span {
  font-size: 12px;
  line-height: 1.5;
  color: var(--studio-text-soft);
}

.dynamic-function-item:hover,
.dynamic-function-item--active {
  border-color: var(--studio-primary);
  box-shadow: 0 10px 24px rgba(37, 99, 235, 0.12);
  transform: translateY(-1px);
}

.dynamic-function-dialog__content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.dynamic-function-card {
  padding: 16px 18px;
  border: 1px solid rgba(37, 99, 235, 0.18);
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(219, 234, 254, 0.7), rgba(239, 246, 255, 0.9));
}

.dynamic-function-card__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.dynamic-function-card__header h4 {
  margin: 0;
  font-size: 18px;
  color: var(--studio-text);
}

.dynamic-function-card__header p,
.dynamic-function-card__description {
  margin: 6px 0 0;
  color: var(--studio-text-soft);
  line-height: 1.6;
}

.dynamic-function-card__header code,
.dynamic-function-selection-tip code {
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.9);
  color: var(--studio-primary);
  font-size: 12px;
  white-space: nowrap;
}

.dynamic-function-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 20px;
  margin-top: 14px;
  font-size: 12px;
  color: var(--studio-text-soft);
}

.dynamic-function-selection-tip {
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.04);
  color: var(--studio-text-soft);
  font-size: 13px;
}

.dynamic-function-args {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
}

.dynamic-function-args__item {
  margin-bottom: 0;
}

.field-hint {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--studio-text-soft);
}

.dynamic-function-preview {
  padding: 14px 16px;
  border: 1px dashed var(--studio-border);
  border-radius: 16px;
  background: rgba(15, 23, 42, 0.02);
}

.dynamic-function-preview__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--studio-text-soft);
}

.dynamic-function-preview pre {
  margin: 10px 0 0;
  padding: 14px 16px;
  overflow: auto;
  border-radius: 12px;
  background: #0f172a;
  color: #dbeafe;
  font: 13px/1.65 "Cascadia Code", "Consolas", monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 860px) {
  .meta-form__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .dynamic-function-dialog__layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .dynamic-function-dialog__sidebar {
    border-right: 0;
    border-bottom: 1px solid var(--studio-border);
    padding: 0 0 16px;
  }

  .dynamic-function-args {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
