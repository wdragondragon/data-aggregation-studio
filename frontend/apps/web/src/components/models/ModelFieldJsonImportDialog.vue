<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('web.models.fieldImportTitle')"
    width="min(960px, calc(100vw - 32px))"
    append-to-body
    destroy-on-close
    @open="resetDialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="field-import-dialog">
      <div v-if="isEditingModel" class="field-import-mode">
        <div class="field-import-mode__header">{{ t("web.models.fieldImportModeLabel") }}</div>
        <el-radio-group v-model="importMode" class="field-import-mode__options">
          <el-radio-button v-for="option in importModeOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
        <div class="field-import-mode__description">{{ currentImportModeDescription }}</div>
      </div>

      <div
        class="field-import-dropzone"
        :class="{ 'is-dragging': dragging }"
        role="button"
        tabindex="0"
        @click="openFilePicker"
        @keydown.enter.prevent="openFilePicker"
        @keydown.space.prevent="openFilePicker"
        @dragenter.prevent="dragging = true"
        @dragover.prevent="dragging = true"
        @dragleave.prevent="handleDragLeave"
        @drop.prevent="handleDrop"
      >
        <el-icon :size="24"><Upload /></el-icon>
        <div class="field-import-dropzone__copy">
          <strong>{{ t("web.models.fieldImportFileAction") }}</strong>
          <span>{{ t("web.models.fieldImportFileHint") }}</span>
          <span v-if="selectedFileName" class="field-import-dropzone__filename">{{ selectedFileName }}</span>
        </div>
        <el-button plain :icon="FolderOpened" @click.stop="openFilePicker">
          {{ t("web.models.fieldImportChooseFile") }}
        </el-button>
        <input
          ref="fileInputRef"
          class="field-import-file-input"
          type="file"
          accept=".json,application/json"
          @change="handleFileInput"
        />
      </div>

      <el-alert v-if="fileError" :title="fileError" type="error" :closable="false" show-icon />

      <JsonEditor
        v-model="sourceText"
        :title="t('web.models.fieldImportEditorTitle')"
        :description="t('web.models.fieldImportEditorDescription')"
        :placeholder="fieldImportPlaceholder"
        height="320px"
      />

      <div class="field-import-status" :class="validationStatusTone">
        <el-icon :size="18">
          <Loading v-if="validating" class="is-loading" />
          <CircleCheck v-else-if="canImport" />
          <WarningFilled v-else-if="hasValidated && (issues.length > 0 || mergeBlocked)" />
          <InfoFilled v-else />
        </el-icon>
        <span>{{ validationStatusText }}</span>
      </div>

      <div v-if="showMergeStats" class="field-import-summary">
        <div class="field-import-summary__title">{{ t("web.models.fieldImportMergeSummary") }}</div>
        <div class="field-import-summary__metrics">
          <div v-for="item in mergeStatItems" :key="item.label" class="field-import-summary__metric">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
      </div>

      <el-alert
        v-if="showMergeStats && mergeBlocked"
        :title="mergeBlockedMessage"
        type="error"
        :closable="false"
        show-icon
      />

      <div v-if="hasValidated && issues.length > 0" class="field-import-errors">
        <div class="field-import-errors__header">
          <strong>{{ t("web.models.fieldImportErrorsTitle", { count: issues.length }) }}</strong>
          <span v-if="issues.length > visibleIssues.length">
            {{ t("web.models.fieldImportErrorsTruncated", { count: visibleIssues.length }) }}
          </span>
        </div>
        <ol class="field-import-errors__list">
          <li v-for="(issue, index) in visibleIssues" :key="`${issue.path}-${issue.code}-${index}`">
            {{ formatIssue(issue) }}
          </li>
        </ol>
      </div>
    </div>

    <template #footer>
      <div class="field-import-footer">
        <el-button :icon="Download" @click="downloadTemplate">
          {{ t("web.models.fieldImportDownloadTemplate") }}
        </el-button>
        <div class="field-import-footer__primary">
          <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
          <el-button type="primary" :disabled="!canImport" @click="confirmImport">
            {{ t("web.models.fieldImportConfirm") }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { CircleCheck, Download, FolderOpened, InfoFilled, Loading, Upload, WarningFilled } from "@element-plus/icons-vue";
import { ElIcon, ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { MetadataFieldDefinition } from "@studio/api-sdk";
import JsonEditor from "@/components/JsonEditor.vue";
import {
  buildModelFieldImportTemplate,
  MODEL_FIELD_IMPORT_MAX_BYTES,
  type ModelFieldImportMode,
  type ModelFieldImportIssue,
  mergeModelFieldRows,
  parseAndValidateModelFieldJson,
  visibleModelFieldImportIssues,
} from "./modelFieldJsonImport";

const props = defineProps<{
  modelValue: boolean;
  fields: MetadataFieldDefinition[];
  currentRows: Record<string, unknown>[];
  isEditingModel: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  imported: [rows: Record<string, unknown>[]];
}>();

const { t } = useI18n();
const fileInputRef = ref<HTMLInputElement | null>(null);
const sourceText = ref("");
const selectedFileName = ref("");
const fileError = ref("");
const dragging = ref(false);
const validating = ref(false);
const hasValidated = ref(false);
const validatedRows = ref<Record<string, unknown>[]>([]);
const issues = ref<ModelFieldImportIssue[]>([]);
const importMode = ref<ModelFieldImportMode>("MERGE_IN_PLACE");
let validationTimer: ReturnType<typeof setTimeout> | undefined;

const fieldImportPlaceholder = `[
  {
    "name": "example_field"
  }
]`;

const currentCount = computed(() => props.currentRows.length);
const importModeOptions = computed(() => [
  {
    value: "MERGE_IN_PLACE" as const,
    label: t("web.models.fieldImportModeMergeInPlace"),
    description: t("web.models.fieldImportModeMergeInPlaceDescription"),
  },
  {
    value: "MERGE_IMPORT_FIRST" as const,
    label: t("web.models.fieldImportModeMergeImportFirst"),
    description: t("web.models.fieldImportModeMergeImportFirstDescription"),
  },
  {
    value: "REPLACE_ALL" as const,
    label: t("web.models.fieldImportModeReplaceAll"),
    description: t("web.models.fieldImportModeReplaceAllDescription"),
  },
]);
const currentImportModeDescription = computed(
  () => importModeOptions.value.find((option) => option.value === importMode.value)?.description ?? "",
);
const activeImportMode = computed<ModelFieldImportMode>(() => (props.isEditingModel ? importMode.value : "REPLACE_ALL"));
const mergeResult = computed(() => mergeModelFieldRows(props.currentRows, validatedRows.value, activeImportMode.value));
const mergeBlocked = computed(() => props.isEditingModel && mergeResult.value.blocked);
const mergeBlockedMessage = computed(() => t("web.models.fieldImportExistingDuplicates", {
  names: mergeResult.value.duplicateExistingNames.join(", "),
}));
const showMergeStats = computed(
  () => props.isEditingModel && hasValidated.value && issues.value.length === 0 && validatedRows.value.length > 0,
);
const mergeStatItems = computed(() => {
  const stats = mergeResult.value.stats;
  return [
    { label: t("web.models.fieldImportStatOverwrite"), value: stats.overwriteCount },
    { label: t("web.models.fieldImportStatAdded"), value: stats.addedCount },
    { label: t("web.models.fieldImportStatRetained"), value: stats.retainedCount },
    { label: t("web.models.fieldImportStatRemoved"), value: stats.removedCount },
    { label: t("web.models.fieldImportStatFinal"), value: stats.finalCount },
  ];
});
const visibleIssues = computed(() => visibleModelFieldImportIssues(issues.value));
const canImport = computed(
  () => hasValidated.value
    && !validating.value
    && !fileError.value
    && issues.value.length === 0
    && validatedRows.value.length > 0
    && !mergeBlocked.value,
);
const validationStatusTone = computed(() => ({
  "is-success": canImport.value,
  "is-error": hasValidated.value && (issues.value.length > 0 || mergeBlocked.value),
}));
const validationStatusText = computed(() => {
  if (validating.value) {
    return t("web.models.fieldImportValidating");
  }
  if (!sourceText.value.trim()) {
    return t("web.models.fieldImportWaiting");
  }
  if (mergeBlocked.value) {
    return t("web.models.fieldImportMergeBlocked");
  }
  if (canImport.value) {
    return t("web.models.fieldImportValid", { count: validatedRows.value.length });
  }
  if (hasValidated.value) {
    return t("web.models.fieldImportInvalid", { count: issues.value.length });
  }
  return t("web.models.fieldImportWaiting");
});

function resetDialog() {
  clearValidationTimer();
  sourceText.value = "";
  selectedFileName.value = "";
  fileError.value = "";
  dragging.value = false;
  validating.value = false;
  hasValidated.value = false;
  validatedRows.value = [];
  issues.value = [];
  importMode.value = "MERGE_IN_PLACE";
  if (fileInputRef.value) {
    fileInputRef.value.value = "";
  }
}

function clearValidationTimer() {
  if (validationTimer) {
    clearTimeout(validationTimer);
    validationTimer = undefined;
  }
}

function scheduleValidation() {
  clearValidationTimer();
  fileError.value = "";
  hasValidated.value = false;
  validatedRows.value = [];
  issues.value = [];
  if (!sourceText.value.trim()) {
    validating.value = false;
    return;
  }
  validating.value = true;
  validationTimer = setTimeout(runValidation, 250);
}

function runValidation() {
  clearValidationTimer();
  const result = parseAndValidateModelFieldJson(sourceText.value, props.fields);
  validatedRows.value = result.rows;
  issues.value = result.issues;
  validating.value = false;
  hasValidated.value = true;
}

function openFilePicker() {
  fileInputRef.value?.click();
}

function handleFileInput(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (file) {
    void loadFile(file);
  }
}

function handleDragLeave(event: DragEvent) {
  const currentTarget = event.currentTarget as HTMLElement | null;
  const relatedTarget = event.relatedTarget as Node | null;
  if (!currentTarget || !relatedTarget || !currentTarget.contains(relatedTarget)) {
    dragging.value = false;
  }
}

function handleDrop(event: DragEvent) {
  dragging.value = false;
  const file = event.dataTransfer?.files?.[0];
  if (file) {
    void loadFile(file);
  }
}

async function loadFile(file: File) {
  clearValidationTimer();
  fileError.value = "";
  validating.value = false;
  hasValidated.value = false;
  validatedRows.value = [];
  issues.value = [];
  selectedFileName.value = file.name;
  if (!file.name.toLowerCase().endsWith(".json")) {
    fileError.value = t("web.models.fieldImportFileTypeError");
    return;
  }
  if (file.size > MODEL_FIELD_IMPORT_MAX_BYTES) {
    fileError.value = t("web.models.fieldImportFileSizeError");
    return;
  }
  try {
    const content = new TextDecoder("utf-8", { fatal: true }).decode(await file.arrayBuffer());
    const sourceChanged = sourceText.value !== content;
    sourceText.value = content;
    if (!sourceChanged) {
      runValidation();
    }
  } catch {
    fileError.value = t("web.models.fieldImportFileEncodingError");
  }
}

async function confirmImport() {
  if (!canImport.value) {
    return;
  }
  if (!props.isEditingModel && currentCount.value > 0) {
    try {
      await ElMessageBox.confirm(
        t("web.models.fieldImportReplaceConfirm", {
          currentCount: currentCount.value,
          importCount: validatedRows.value.length,
        }),
        t("web.models.fieldImportReplaceTitle"),
        { type: "warning" },
      );
    } catch (error) {
      if (error !== "cancel" && error !== "close") {
        ElMessage.error(error instanceof Error ? error.message : t("web.models.fieldImportFailed"));
      }
      return;
    }
  }
  if (props.isEditingModel && importMode.value === "REPLACE_ALL" && currentCount.value > 0) {
    try {
      await ElMessageBox.confirm(
        t("web.models.fieldImportReplaceAllConfirm", {
          currentCount: currentCount.value,
          importCount: validatedRows.value.length,
        }),
        t("web.models.fieldImportReplaceAllTitle"),
        { type: "warning" },
      );
    } catch (error) {
      if (error !== "cancel" && error !== "close") {
        ElMessage.error(error instanceof Error ? error.message : t("web.models.fieldImportFailed"));
      }
      return;
    }
  }
  emit("imported", mergeResult.value.rows.map((row) => ({ ...row })));
  emit("update:modelValue", false);
}

function downloadTemplate() {
  const template = buildModelFieldImportTemplate(props.fields);
  const validation = parseAndValidateModelFieldJson(template, props.fields);
  if (validation.issues.length > 0) {
    ElMessage.error(t("web.models.fieldImportTemplateInvalid"));
    return;
  }
  const url = URL.createObjectURL(new Blob([template], { type: "application/json;charset=utf-8" }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = "model-fields-template.json";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function formatIssue(issue: ModelFieldImportIssue) {
  switch (issue.code) {
    case "TEXT_TOO_LARGE":
      return t("web.models.fieldImportIssueTextTooLarge", { path: issue.path });
    case "INVALID_JSON":
      return t("web.models.fieldImportIssueInvalidJson", { path: issue.path, detail: issue.detail ?? "" });
    case "INVALID_ROOT":
      return t("web.models.fieldImportIssueInvalidRoot", { path: issue.path, actualType: issue.actualType ?? "unknown" });
    case "UNEXPECTED_ROOT_KEY":
      return t("web.models.fieldImportIssueUnexpectedRootKey", { path: issue.path, field: issue.fieldName ?? "" });
    case "EMPTY_ROWS":
      return t("web.models.fieldImportIssueEmptyRows", { path: issue.path });
    case "INVALID_ROW":
      return t("web.models.fieldImportIssueInvalidRow", { path: issue.path, actualType: issue.actualType ?? "unknown" });
    case "UNKNOWN_FIELD":
      return t("web.models.fieldImportIssueUnknownField", { path: issue.path, field: issue.fieldName ?? "" });
    case "MISSING_REQUIRED":
      return t("web.models.fieldImportIssueMissingRequired", { path: issue.path, field: issue.fieldName ?? "" });
    case "INVALID_TYPE":
      return t("web.models.fieldImportIssueInvalidType", {
        path: issue.path,
        expectedType: issue.expectedType ?? "unknown",
        actualType: issue.actualType ?? "unknown",
      });
    case "INVALID_OPTION":
      return t("web.models.fieldImportIssueInvalidOption", {
        path: issue.path,
        value: issue.detail ?? "",
        options: issue.allowedOptions ?? "",
      });
    case "DUPLICATE_NAME":
      return t("web.models.fieldImportIssueDuplicateName", {
        path: issue.path,
        name: issue.duplicateValue ?? "",
        firstPath: issue.firstPath ?? "",
      });
  }
}

watch(sourceText, scheduleValidation);

onBeforeUnmount(() => {
  clearValidationTimer();
});
</script>

<style scoped>
.field-import-dialog {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.field-import-mode {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.field-import-mode__header,
.field-import-summary__title {
  color: var(--studio-text);
  font-size: 13px;
  font-weight: 600;
}

.field-import-mode__options {
  display: flex;
  min-width: 0;
}

.field-import-mode__options :deep(.el-radio-button) {
  flex: 1 1 0;
  min-width: 0;
}

.field-import-mode__options :deep(.el-radio-button__inner) {
  width: 100%;
  padding-inline: 12px;
  white-space: normal;
}

.field-import-mode__description {
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.6;
}

.field-import-dropzone {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 88px;
  padding: 14px 16px;
  border: 1px dashed var(--studio-border);
  border-radius: 8px;
  color: var(--studio-text-soft);
  background: #f8fafc;
  cursor: pointer;
  transition: border-color 0.2s ease, background-color 0.2s ease;
}

.field-import-dropzone:hover,
.field-import-dropzone:focus-visible,
.field-import-dropzone.is-dragging {
  border-color: var(--studio-primary);
  background: rgba(37, 99, 235, 0.06);
  outline: none;
}

.field-import-dropzone__copy {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.field-import-dropzone__copy strong {
  color: var(--studio-text);
  font-size: 14px;
}

.field-import-dropzone__copy span {
  font-size: 12px;
  overflow-wrap: anywhere;
}

.field-import-dropzone__filename {
  color: var(--studio-primary);
}

.field-import-file-input {
  display: none;
}

.field-import-status {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 8px 12px;
  border-left: 3px solid #94a3b8;
  color: #475569;
  background: #f8fafc;
  font-size: 13px;
}

.field-import-status.is-success {
  border-left-color: #16a34a;
  color: #166534;
  background: #f0fdf4;
}

.field-import-status.is-error {
  border-left-color: #dc2626;
  color: #991b1b;
  background: #fef2f2;
}

.field-import-summary {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 10px 0;
  border-top: 1px solid var(--studio-border);
  border-bottom: 1px solid var(--studio-border);
}

.field-import-summary__metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.field-import-summary__metric {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 2px 12px;
  border-left: 1px solid var(--studio-border);
}

.field-import-summary__metric:first-child {
  padding-left: 0;
  border-left: 0;
}

.field-import-summary__metric span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.field-import-summary__metric strong {
  color: var(--studio-text);
  font-size: 18px;
  font-weight: 600;
}

.field-import-errors {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.field-import-errors__header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  color: #991b1b;
  font-size: 13px;
}

.field-import-errors__header span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.field-import-errors__list {
  box-sizing: border-box;
  max-height: 220px;
  margin: 0;
  padding: 10px 12px 10px 38px;
  overflow: auto;
  border: 1px solid #fecaca;
  border-radius: 8px;
  color: #991b1b;
  background: #fffafa;
  font: 12px/1.7 "Cascadia Code", "Consolas", monospace;
  overflow-wrap: anywhere;
}

.field-import-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.field-import-footer__primary {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 640px) {
  .field-import-mode__options {
    align-items: stretch;
    flex-direction: column;
  }

  .field-import-mode__options :deep(.el-radio-button__inner) {
    border: 1px solid var(--el-border-color);
    border-radius: 0;
  }

  .field-import-summary__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px 0;
  }

  .field-import-summary__metric:nth-child(odd) {
    padding-left: 0;
    border-left: 0;
  }

  .field-import-dropzone {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .field-import-dropzone > .el-button {
    grid-column: 1 / -1;
    width: 100%;
  }

  .field-import-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .field-import-footer > .el-button,
  .field-import-footer__primary,
  .field-import-footer__primary > .el-button {
    width: 100%;
  }
}
</style>
