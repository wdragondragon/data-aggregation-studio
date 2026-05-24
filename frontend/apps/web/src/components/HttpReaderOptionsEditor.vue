<template>
  <div class="http-reader-options-editor">
    <MetaFormRenderer
      v-if="baseFields.length"
      :fields="baseFields"
      :model-value="modelValue"
      :dynamic-function-fields="baseDynamicFunctionFields"
      @update:model-value="emitMergedValue($event)"
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

    <el-dialog
      v-model="dynamicFunctionDialogVisible"
      :title="t('web.collectionTasks.httpDynamicFunctionDialogTitle')"
      width="min(1120px, calc(100vw - 64px))"
      top="4vh"
      destroy-on-close
      append-to-body
      class="http-dynamic-function-dialog"
    >
      <div class="http-dynamic-function-dialog__layout">
        <div class="http-dynamic-function-dialog__sidebar">
          <div class="http-dynamic-function-dialog__sidebar-title">
            {{ t("web.collectionTasks.httpDynamicFunctionListTitle") }}
          </div>
          <div class="http-dynamic-function-list">
            <button
              v-for="item in httpDynamicFunctionCatalog"
              :key="item.name"
              type="button"
              class="http-dynamic-function-item"
              :class="{ 'http-dynamic-function-item--active': selectedHttpFunctionName === item.name }"
              @click="selectedHttpFunctionName = item.name"
            >
              <strong>{{ item.label }}</strong>
              <span>{{ item.summary }}</span>
            </button>
          </div>
        </div>

        <div class="http-dynamic-function-dialog__content">
          <div class="http-dynamic-function-card">
            <div class="http-dynamic-function-card__header">
              <div>
                <h4>{{ selectedHttpFunction.label }}</h4>
                <p>{{ selectedHttpFunction.summary }}</p>
              </div>
              <code>{{ selectedHttpFunction.signature }}</code>
            </div>
            <p class="http-dynamic-function-card__description">{{ selectedHttpFunction.description }}</p>
            <div class="http-dynamic-function-card__meta">
              <span>{{ t("web.collectionTasks.httpDynamicFunctionReturn") }}{{ selectedHttpFunction.returnDescription }}</span>
              <span>{{ t("web.collectionTasks.httpDynamicFunctionExample") }}{{ selectedHttpFunction.example }}</span>
            </div>
          </div>

          <div v-if="selectedInputSnippet" class="http-dynamic-function-selection-tip">
            {{ t("web.collectionTasks.httpDynamicFunctionSelected") }}<code>{{ selectedInputSnippet }}</code>
          </div>

          <div v-if="selectedHttpFunction.kind === 'token'" class="http-token-function-form">
            <div class="http-token-function-grid">
              <el-form-item :label="t('web.collectionTasks.httpTokenMethod')">
                <el-select v-model="tokenConfig.method">
                  <el-option label="GET" value="GET" />
                  <el-option label="POST" value="POST" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('web.collectionTasks.httpTokenUrl')">
                <el-input v-model="tokenConfig.url" :placeholder="t('web.collectionTasks.httpTokenUrlPlaceholder')" />
              </el-form-item>
              <el-form-item :label="t('web.collectionTasks.httpTokenPath')">
                <el-input v-model="tokenConfig.path" :placeholder="t('web.collectionTasks.httpTokenPathPlaceholder')" />
              </el-form-item>
              <el-form-item :label="t('web.collectionTasks.httpTokenPrefix')">
                <el-input v-model="tokenConfig.prefix" :placeholder="t('web.collectionTasks.httpTokenPrefixPlaceholder')" />
              </el-form-item>
            </div>

            <HttpTokenPairTable
              :rows="tokenHeaderRows"
              :title="t('web.collectionTasks.httpTokenHeaderTitle')"
              :description="t('web.collectionTasks.httpTokenHeaderDescription')"
              :add-label="t('web.collectionTasks.httpTokenAddHeader')"
              key-placeholder="Authorization"
              value-placeholder="Bearer xxx"
              @append="appendTokenPairRow('header')"
              @update-row="(index, field, value) => updateTokenPairRow('header', index, field, value)"
              @remove-row="(index) => removeTokenPairRow('header', index)"
            />

            <HttpTokenPairTable
              :rows="tokenBodyRows"
              :title="t('web.collectionTasks.httpTokenBodyTitle')"
              :description="t('web.collectionTasks.httpTokenBodyDescription')"
              :add-label="t('web.collectionTasks.httpTokenAddBody')"
              key-placeholder="username"
              value-placeholder="admin"
              @append="appendTokenPairRow('body')"
              @update-row="(index, field, value) => updateTokenPairRow('body', index, field, value)"
              @remove-row="(index) => removeTokenPairRow('body', index)"
            />
          </div>

          <div v-else-if="selectedHttpFunction.params.length" class="http-dynamic-function-args">
            <el-form-item
              v-for="param in selectedHttpFunction.params"
              :key="param.key"
              :label="param.label"
              class="http-dynamic-function-args__item"
            >
              <el-input v-model="dynamicFunctionArgs[param.key]" :placeholder="param.placeholder" />
              <div class="http-dynamic-function-hint">
                {{ param.description }}
                <span v-if="param.required === false">{{ t("web.collectionTasks.httpDynamicFunctionOptional") }}</span>
              </div>
            </el-form-item>
          </div>

          <div v-else class="http-dynamic-function-note">
            {{ t("web.collectionTasks.httpDynamicFunctionNoArgs") }}
          </div>

          <div class="http-dynamic-function-preview">
            <span class="http-dynamic-function-preview__label">
              {{ t("web.collectionTasks.httpDynamicFunctionPreview") }}
            </span>
            <pre>{{ dynamicFunctionPreview }}</pre>
            <div class="http-dynamic-function-hint">
              {{ t("web.collectionTasks.httpDynamicFunctionPreviewHint") }}
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="http-dynamic-function-dialog__footer">
          <el-button @click="dynamicFunctionDialogVisible = false">{{ t("common.cancel") }}</el-button>
          <el-button type="primary" @click="confirmDynamicFunctionInsert">
            {{ t("web.collectionTasks.httpDynamicFunctionConfirm") }}
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import { useI18n } from "vue-i18n";
import type { MetadataFieldDefinition } from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import HttpOptionKeyValueTable from "@/components/http-request/HttpOptionKeyValueTable.vue";
import HttpRawOptionEditor from "@/components/http-request/HttpRawOptionEditor.vue";
import HttpTokenPairTable from "@/components/http-request/HttpTokenPairTable.vue";

type HttpOptionKey = "header" | "params" | "requestBody";
type EditorMode = "table" | "raw";
type RowField = "name" | "value";
type TokenPairType = "header" | "body";
type HttpDynamicFunctionKind = "literal" | "digest" | "token";
type DynamicFunctionTarget =
  | { mode: "row"; key: HttpOptionKey; index: number }
  | { mode: "raw"; key: HttpOptionKey };

interface KeyValueRow {
  name: string;
  value: string;
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

interface HttpDynamicFunctionParamSchema {
  key: string;
  label: string;
  placeholder: string;
  description: string;
  required?: boolean;
  defaultValue?: string;
  useSelectedSnippet?: boolean;
}

interface HttpDynamicFunctionSchema {
  name: string;
  label: string;
  summary: string;
  description: string;
  signature: string;
  returnDescription: string;
  example: string;
  kind: HttpDynamicFunctionKind;
  expression?: string;
  params: HttpDynamicFunctionParamSchema[];
}

const httpOptionKeys: HttpOptionKey[] = ["header", "params", "requestBody"];
const objectStringKeys = new Set<HttpOptionKey>(["header", "params"]);

const props = withDefaults(
  defineProps<{
    fields: MetadataFieldDefinition[];
    modelValue: Record<string, unknown>;
    dynamicFunctionFields?: string[];
  }>(),
  {
    fields: () => [],
    modelValue: () => ({}),
    dynamicFunctionFields: () => [],
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: Record<string, unknown>];
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
const selectedHttpFunctionName = ref("dyn_page");
const selectedInputSnippet = ref("");
const dynamicFunctionTarget = ref<DynamicFunctionTarget | null>(null);
const dynamicFunctionArgs = reactive<Record<string, string>>({});
const tokenConfig = reactive({
  method: "POST",
  url: "",
  path: "data.token",
  prefix: "",
});
const tokenHeaderRows = ref<KeyValueRow[]>([]);
const tokenBodyRows = ref<KeyValueRow[]>([]);
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
const httpDynamicFunctionCatalog = computed<HttpDynamicFunctionSchema[]>(() => [
  literalFunction(
    "dyn_page",
    "{dyn_page}",
    t("web.collectionTasks.httpDynPageSummary"),
    t("web.collectionTasks.httpDynPageDescription"),
    t("web.collectionTasks.httpDynPageReturn"),
  ),
  literalFunction(
    "dyn_pageSize",
    "{dyn_pageSize}",
    t("web.collectionTasks.httpDynPageSizeSummary"),
    t("web.collectionTasks.httpDynPageSizeDescription"),
    t("web.collectionTasks.httpDynPageSizeReturn"),
  ),
  literalFunction(
    "dyn_offset",
    "{dyn_offset}",
    t("web.collectionTasks.httpDynOffsetSummary"),
    t("web.collectionTasks.httpDynOffsetDescription"),
    t("web.collectionTasks.httpDynOffsetReturn"),
  ),
  {
    name: "dyn_from_http_token",
    label: "dyn_from_http_token",
    summary: t("web.collectionTasks.httpDynTokenSummary"),
    description: t("web.collectionTasks.httpDynTokenDescription"),
    signature: "{dyn_from_http_token(method,url,header,body,path)}",
    returnDescription: t("web.collectionTasks.httpDynTokenReturn"),
    example: "Bearer {dyn_from_http_token(POST,http://localhost/login,,username=admin&password=admin123,data.token)}",
    kind: "token",
    params: [],
  },
  literalFunction(
    "dyn_timestamp",
    "{dyn_timestamp}",
    t("web.collectionTasks.httpDynTimestampSummary"),
    t("web.collectionTasks.httpDynTimestampDescription"),
    t("web.collectionTasks.httpDynTimestampReturn"),
  ),
  literalFunction(
    "dyn_ten_timestamp",
    "{dyn_ten_timestamp}",
    t("web.collectionTasks.httpDynTenTimestampSummary"),
    t("web.collectionTasks.httpDynTenTimestampDescription"),
    t("web.collectionTasks.httpDynTenTimestampReturn"),
  ),
  digestFunction("dyn_MD5", "MD5"),
  digestFunction("dyn_Sha1", "SHA1"),
  digestFunction("dyn_Sha256", "SHA256"),
  digestFunction("dyn_Sha512", "SHA512"),
]);
const selectedHttpFunction = computed<HttpDynamicFunctionSchema>(() =>
  httpDynamicFunctionCatalog.value.find((item) => item.name === selectedHttpFunctionName.value)
  ?? httpDynamicFunctionCatalog.value[0] as HttpDynamicFunctionSchema,
);
const dynamicFunctionPreview = computed(() => buildDynamicFunctionExpression());

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
watch(selectedHttpFunctionName, () => {
  if (!dynamicFunctionDialogVisible.value) {
    return;
  }
  resetDynamicFunctionArgs();
});

function isHttpOptionKey(key: unknown): key is HttpOptionKey {
  return key === "header" || key === "params" || key === "requestBody";
}

function emitMergedValue(value: Record<string, unknown>) {
  emit("update:modelValue", value ?? {});
}

function emitOptionValue(key: HttpOptionKey, value: string) {
  pendingEmittedValues[key] = value;
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
  return Object.entries(value).map(([name, rowValue]) => ({
    name,
    value: stringifyRowValue(rowValue),
  }));
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
  const objectValue: Record<string, string> = {};
  for (const row of optionRows[key]) {
    const name = row.name.trim();
    if (!name) {
      continue;
    }
    objectValue[name] = row.value;
  }
  const nextValue = JSON.stringify(objectValue);
  rawText[key] = objectStringKeys.has(key) || Object.keys(objectValue).length > 0 ? nextValue : "";
  parseErrors[key] = "";
  emitOptionValue(key, rawText[key]);
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

function literalFunction(
  name: string,
  expression: string,
  summary: string,
  description: string,
  returnDescription: string,
): HttpDynamicFunctionSchema {
  return {
    name,
    label: name,
    summary,
    description,
    signature: expression,
    returnDescription,
    example: expression,
    kind: "literal",
    expression,
    params: [],
  };
}

function digestFunction(name: string, algorithm: string): HttpDynamicFunctionSchema {
  return {
    name,
    label: name,
    summary: t("web.collectionTasks.httpDynDigestSummary", { algorithm }),
    description: t("web.collectionTasks.httpDynDigestDescription", { algorithm }),
    signature: `{${name}(value)}`,
    returnDescription: t("web.collectionTasks.httpDynDigestReturn", { algorithm }),
    example: `{${name}(abc123)}`,
    kind: "digest",
    params: [
      {
        key: "value",
        label: t("web.collectionTasks.httpDynDigestValue"),
        placeholder: "abc123",
        description: t("web.collectionTasks.httpDynDigestValueDescription"),
        useSelectedSnippet: true,
      },
    ],
  };
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
  selectedHttpFunctionName.value = defaultFunctionForTarget(target);
  selectedInputSnippet.value = resolveSelectedInputSnippet(target);
  resetDynamicFunctionArgs();
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

function resetDynamicFunctionArgs() {
  const nextValues: Record<string, string> = {};
  selectedHttpFunction.value.params.forEach((param) => {
    const selectedValue = param.useSelectedSnippet && selectedInputSnippet.value ? selectedInputSnippet.value : "";
    nextValues[param.key] = selectedValue || param.defaultValue || "";
  });
  Object.keys(dynamicFunctionArgs).forEach((key) => {
    delete dynamicFunctionArgs[key];
  });
  Object.assign(dynamicFunctionArgs, nextValues);
  if (selectedHttpFunction.value.kind === "token") {
    resetTokenFunctionArgs();
  }
}

function resetTokenFunctionArgs() {
  tokenConfig.method = "POST";
  tokenConfig.url = "";
  tokenConfig.path = "data.token";
  tokenConfig.prefix = defaultTokenPrefix();
  tokenHeaderRows.value = [];
  tokenBodyRows.value = [];
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

function appendTokenPairRow(type: TokenPairType) {
  const rows = type === "header" ? tokenHeaderRows : tokenBodyRows;
  rows.value = [
    ...rows.value,
    { name: "", value: "" },
  ];
}

function updateTokenPairRow(type: TokenPairType, index: number, field: RowField, value: unknown) {
  const rows = [...(type === "header" ? tokenHeaderRows.value : tokenBodyRows.value)];
  rows[index] = {
    ...(rows[index] ?? { name: "", value: "" }),
    [field]: String(value ?? ""),
  };
  if (type === "header") {
    tokenHeaderRows.value = rows;
    return;
  }
  tokenBodyRows.value = rows;
}

function removeTokenPairRow(type: TokenPairType, index: number) {
  const rows = [...(type === "header" ? tokenHeaderRows.value : tokenBodyRows.value)];
  rows.splice(index, 1);
  if (type === "header") {
    tokenHeaderRows.value = rows;
    return;
  }
  tokenBodyRows.value = rows;
}

function buildDynamicFunctionExpression() {
  const currentFunction = selectedHttpFunction.value;
  if (currentFunction.kind === "literal") {
    return currentFunction.expression ?? "";
  }
  if (currentFunction.kind === "token") {
    const expression = `{dyn_from_http_token(${[
      tokenConfig.method.trim(),
      tokenConfig.url.trim(),
      buildTokenPairParam(tokenHeaderRows.value),
      buildTokenPairParam(tokenBodyRows.value),
      tokenConfig.path.trim(),
    ].join(",")})}`;
    return `${tokenConfig.prefix}${expression}`;
  }
  const args = currentFunction.params.map((param) => String(dynamicFunctionArgs[param.key] ?? "").trim());
  return `{${currentFunction.name}(${args.join(",")})}`;
}

function buildTokenPairParam(rows: KeyValueRow[]) {
  return rows
    .map((row) => ({
      name: row.name.trim(),
      value: row.value.trim(),
    }))
    .filter((row) => row.name)
    .map((row) => `${row.name}=${row.value}`)
    .join("&");
}

async function confirmDynamicFunctionInsert() {
  const target = dynamicFunctionTarget.value;
  if (!target) {
    return;
  }
  const validationMessage = validateDynamicFunction();
  if (validationMessage) {
    ElMessage.warning(validationMessage);
    return;
  }
  await insertDynamicFunctionExpression(target, dynamicFunctionPreview.value);
  dynamicFunctionDialogVisible.value = false;
}

function validateDynamicFunction() {
  const currentFunction = selectedHttpFunction.value;
  if (currentFunction.kind === "token") {
    if (!tokenConfig.method.trim()) {
      return t("web.collectionTasks.httpDynamicFunctionMissingParam", { label: t("web.collectionTasks.httpTokenMethod") });
    }
    if (!tokenConfig.url.trim()) {
      return t("web.collectionTasks.httpDynamicFunctionMissingParam", { label: t("web.collectionTasks.httpTokenUrl") });
    }
    if (!tokenConfig.path.trim()) {
      return t("web.collectionTasks.httpDynamicFunctionMissingParam", { label: t("web.collectionTasks.httpTokenPath") });
    }
    return validateTokenPairs(tokenHeaderRows.value) || validateTokenPairs(tokenBodyRows.value);
  }
  const missingParam = currentFunction.params.find((param) =>
    param.required !== false && !(dynamicFunctionArgs[param.key] ?? "").trim());
  if (missingParam) {
    return t("web.collectionTasks.httpDynamicFunctionMissingParam", { label: missingParam.label });
  }
  return "";
}

function validateTokenPairs(rows: KeyValueRow[]) {
  for (const row of rows) {
    const name = row.name.trim();
    const value = row.value.trim();
    if (!name && !value) {
      continue;
    }
    if (!name) {
      return t("web.collectionTasks.httpTokenPairKeyRequired");
    }
    if (!value) {
      return t("web.collectionTasks.httpTokenPairValueRequired", { key: name });
    }
    if (name.includes("&") || name.includes("=") || value.includes("&") || value.includes("=")) {
      return t("web.collectionTasks.httpTokenPairInvalid", { key: name });
    }
  }
  return "";
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

:global(.http-dynamic-function-dialog .el-dialog__body) {
  max-height: calc(100vh - 180px);
  overflow: auto;
}

.http-dynamic-function-dialog__layout {
  display: grid;
  grid-template-columns: minmax(220px, 260px) minmax(0, 1fr);
  gap: 20px;
  min-width: 0;
}

.http-dynamic-function-dialog__sidebar {
  border-right: 1px solid var(--studio-border);
  min-width: 0;
  padding-right: 16px;
}

.http-dynamic-function-dialog__sidebar-title {
  margin-bottom: 12px;
  color: var(--studio-text-soft);
  font-size: 13px;
  font-weight: 600;
}

.http-dynamic-function-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.http-dynamic-function-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  min-width: 0;
  padding: 11px 13px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: #fff;
  color: var(--studio-text);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.http-dynamic-function-item strong {
  overflow-wrap: anywhere;
  font-size: 13px;
}

.http-dynamic-function-item span {
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.45;
}

.http-dynamic-function-item:hover,
.http-dynamic-function-item--active {
  border-color: var(--studio-primary);
  box-shadow: 0 8px 18px rgba(37, 99, 235, 0.1);
}

.http-dynamic-function-dialog__content {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.http-dynamic-function-card {
  min-width: 0;
  padding: 14px 16px;
  border: 1px solid rgba(37, 99, 235, 0.18);
  border-radius: 8px;
  background: rgba(239, 246, 255, 0.82);
}

.http-dynamic-function-card__header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(180px, auto);
  align-items: start;
  gap: 14px;
  min-width: 0;
}

.http-dynamic-function-card__header > div {
  min-width: 0;
}

.http-dynamic-function-card__header h4 {
  margin: 0;
  color: var(--studio-text);
  font-size: 17px;
}

.http-dynamic-function-card__header p,
.http-dynamic-function-card__description {
  margin: 5px 0 0;
  color: var(--studio-text-soft);
  line-height: 1.55;
}

.http-dynamic-function-card__header code,
.http-dynamic-function-selection-tip code {
  min-width: 0;
  max-width: 100%;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
  color: var(--studio-primary);
  font-size: 12px;
  overflow-wrap: anywhere;
  white-space: normal;
}

.http-dynamic-function-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin-top: 12px;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.http-dynamic-function-card__meta span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.http-dynamic-function-selection-tip,
.http-dynamic-function-note {
  min-width: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(15, 23, 42, 0.04);
  color: var(--studio-text-soft);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.http-dynamic-function-args,
.http-token-function-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 8px 14px;
  min-width: 0;
}

.http-dynamic-function-args__item {
  margin-bottom: 0;
}

.http-dynamic-function-args :deep(.el-form-item),
.http-token-function-grid :deep(.el-form-item) {
  display: grid;
  gap: 6px;
  min-width: 0;
  margin-bottom: 0;
}

.http-dynamic-function-args :deep(.el-form-item__label),
.http-token-function-grid :deep(.el-form-item__label) {
  height: auto;
  justify-content: flex-start;
  padding: 0;
  color: var(--studio-text);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.35;
}

.http-dynamic-function-args :deep(.el-form-item__content),
.http-token-function-grid :deep(.el-form-item__content) {
  min-width: 0;
  width: 100%;
  line-height: 1.4;
}

.http-dynamic-function-args :deep(.el-input),
.http-token-function-grid :deep(.el-input),
.http-token-function-grid :deep(.el-select) {
  width: 100%;
  min-width: 0;
}

.http-dynamic-function-hint {
  margin-top: 6px;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.5;
}

.http-token-function-form {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.http-token-pair-group {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.http-token-pair-group__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.http-token-pair-group__header > div {
  min-width: 0;
}

.http-token-pair-group__header strong {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
}

.http-token-pair-group__header p {
  margin: 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.45;
}

.http-token-pair-group :deep(.el-table) {
  max-width: 100%;
}

.http-token-pair-group :deep(.el-input) {
  width: 100%;
  min-width: 0;
}

.http-dynamic-function-preview {
  min-width: 0;
  padding: 12px 14px;
  border: 1px dashed var(--studio-border);
  border-radius: 8px;
  background: rgba(15, 23, 42, 0.02);
}

.http-dynamic-function-preview__label {
  color: var(--studio-text-soft);
  font-size: 13px;
  font-weight: 600;
}

.http-dynamic-function-preview pre {
  margin: 10px 0 0;
  padding: 12px 14px;
  overflow: auto;
  border-radius: 8px;
  background: #0f172a;
  color: #dbeafe;
  font: 12px/1.65 "Cascadia Code", "Consolas", monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

.http-dynamic-function-dialog__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 900px) {
  .http-reader-option__header {
    display: grid;
  }

  .http-reader-option__toolbar {
    justify-content: flex-start;
  }

  .http-dynamic-function-dialog__layout,
  .http-dynamic-function-args,
  .http-token-function-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .http-dynamic-function-dialog__sidebar {
    border-right: 0;
    border-bottom: 1px solid var(--studio-border);
    padding-right: 0;
    padding-bottom: 14px;
  }

  .http-dynamic-function-card__header,
  .http-token-pair-group__header {
    display: grid;
  }
}
</style>
