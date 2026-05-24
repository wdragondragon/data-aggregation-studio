<template>
  <el-dialog
    v-model="dialogVisible"
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
        <el-button @click="dialogVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="confirmDynamicFunctionInsert">
          {{ t("web.collectionTasks.httpDynamicFunctionConfirm") }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import HttpTokenPairTable from "@/components/http-request/HttpTokenPairTable.vue";
import {
  buildHttpDynamicFunctionCatalog,
  buildTokenPairParam,
  type HttpDynamicFunctionSchema,
  type HttpDynamicTokenPair,
} from "@/components/http-request/httpDynamicFunctions";

type TokenPairType = "header" | "body";
type RowField = "name" | "value";

const props = withDefaults(
  defineProps<{
    visible: boolean;
    initialFunctionName?: string;
    selectedSnippet?: string;
    tokenPrefix?: string;
  }>(),
  {
    initialFunctionName: "dyn_page",
    selectedSnippet: "",
    tokenPrefix: "",
  },
);

const emit = defineEmits<{
  "update:visible": [value: boolean];
  confirm: [expression: string];
}>();

const { t } = useI18n();

const dialogVisible = computed({
  get: () => props.visible,
  set: (value: boolean) => emit("update:visible", value),
});
const httpDynamicFunctionCatalog = computed<HttpDynamicFunctionSchema[]>(() =>
  buildHttpDynamicFunctionCatalog((key, params) => t(key, params ?? {})),
);
const selectedHttpFunctionName = ref("dyn_page");
const selectedInputSnippet = computed(() => props.selectedSnippet || "");
const dynamicFunctionArgs = reactive<Record<string, string>>({});
const tokenConfig = reactive({
  method: "POST",
  url: "",
  path: "data.token",
  prefix: "",
});
const tokenHeaderRows = ref<HttpDynamicTokenPair[]>([]);
const tokenBodyRows = ref<HttpDynamicTokenPair[]>([]);

const selectedHttpFunction = computed<HttpDynamicFunctionSchema>(() =>
  httpDynamicFunctionCatalog.value.find((item) => item.name === selectedHttpFunctionName.value)
  ?? httpDynamicFunctionCatalog.value[0] as HttpDynamicFunctionSchema,
);
const dynamicFunctionPreview = computed(() => buildDynamicFunctionExpression());

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      resetDialogState();
    }
  },
);
watch(selectedHttpFunctionName, () => {
  if (!dialogVisible.value) {
    return;
  }
  resetDynamicFunctionArgs();
});

function resetDialogState() {
  selectedHttpFunctionName.value = resolveInitialFunctionName();
  resetDynamicFunctionArgs();
}

function resolveInitialFunctionName() {
  return httpDynamicFunctionCatalog.value.some((item) => item.name === props.initialFunctionName)
    ? props.initialFunctionName
    : "dyn_page";
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
  tokenConfig.prefix = props.tokenPrefix || "";
  tokenHeaderRows.value = [];
  tokenBodyRows.value = [];
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

function confirmDynamicFunctionInsert() {
  const validationMessage = validateDynamicFunction();
  if (validationMessage) {
    ElMessage.warning(validationMessage);
    return;
  }
  emit("confirm", dynamicFunctionPreview.value);
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

function validateTokenPairs(rows: HttpDynamicTokenPair[]) {
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
</script>

<style scoped>
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

  .http-dynamic-function-card__header {
    display: grid;
  }
}
</style>
