<template>
  <div class="managed-file-field">
    <el-alert
      v-if="legacyPath"
      type="warning"
      :closable="false"
      show-icon
      :title="t('metaForm.managedFileLegacyTitle')"
      :description="t('metaForm.managedFileLegacyDescription')"
    />

    <div v-if="selectedFile" class="managed-file-field__summary">
      <div class="managed-file-field__identity">
        <strong>{{ selectedFile.fileName }}</strong>
        <span>{{ formatBytes(selectedFile.sizeBytes) }} · {{ selectedFile.sha256Summary || "-" }}</span>
      </div>
      <div class="managed-file-field__meta">
        <span>{{ formatDate(selectedFile.uploadedAt) }}</span>
        <el-tag size="small" :type="selectedFile.referenced ? 'success' : 'info'">
          {{ selectedFile.referenced ? t('metaForm.managedFileReferenced') : t('metaForm.managedFileUnreferenced') }}
        </el-tag>
      </div>
    </div>

    <div v-else-if="managedFileId && loading" class="managed-file-field__loading">
      {{ t("common.loading") }}
    </div>

    <div class="managed-file-field__actions">
      <el-button :disabled="disabled || !client" :loading="uploading" @click="openFilePicker">
        {{ selectedFile || legacyPath ? t("metaForm.managedFileReplace") : t("metaForm.managedFileUpload") }}
      </el-button>
      <el-button :disabled="disabled || !client" @click="openSelectionDialog">
        {{ t("metaForm.managedFileSelect") }}
      </el-button>
      <el-button v-if="selectedFile?.downloadable" :disabled="disabled" @click="download(selectedFile)">
        {{ t("common.download") }}
      </el-button>
      <el-button v-if="modelValue" :disabled="disabled" @click="emitValue(undefined)">
        {{ t("common.remove") }}
      </el-button>
    </div>

    <input ref="fileInput" class="managed-file-field__native-input" type="file" @change="uploadSelectedFile" />
    <p class="managed-file-field__hint">
      {{ t("metaForm.managedFilePolicy", { policy: policyCode || "-" }) }}
    </p>

    <el-dialog
      v-model="selectionVisible"
      :title="t('metaForm.managedFileSelectTitle')"
      width="min(880px, 94vw)"
      append-to-body
    >
      <el-table v-loading="listLoading" :data="availableFiles" border>
        <el-table-column prop="fileName" :label="t('metaForm.managedFileName')" min-width="190" />
        <el-table-column :label="t('metaForm.managedFileSize')" width="110">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column prop="sha256Summary" label="SHA-256" min-width="150" />
        <el-table-column :label="t('metaForm.managedFileUploadedAt')" min-width="170">
          <template #default="{ row }">{{ formatDate(row.uploadedAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="selectFile(row)">{{ t("common.select") }}</el-button>
            <el-button
              v-if="row.deletable"
              link
              type="danger"
              @click="deleteFile(row)"
            >
              {{ t("common.delete") }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="pageNo"
        v-model:page-size="pageSize"
        class="managed-file-field__pagination"
        background
        layout="total, prev, pager, next"
        :total="total"
        @current-change="loadFiles"
      />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { ManagedFileView } from "@studio/api-sdk";
import type { ManagedFileClient } from "./managedFile";

const props = withDefaults(defineProps<{
  modelValue?: unknown;
  policyCode?: string;
  client?: ManagedFileClient;
  disabled?: boolean;
}>(), {
  policyCode: "",
  client: undefined,
  disabled: false,
});

const emit = defineEmits<{ "update:modelValue": [value: string | undefined] }>();
const { t } = useI18n();
const fileInput = ref<HTMLInputElement>();
const selectedFile = ref<ManagedFileView>();
const loading = ref(false);
const uploading = ref(false);
const selectionVisible = ref(false);
const listLoading = ref(false);
const availableFiles = ref<ManagedFileView[]>([]);
const pageNo = ref(1);
const pageSize = ref(10);
const total = ref(0);

const managedFileId = computed(() => {
  const match = String(props.modelValue ?? "").trim().match(/^managed-file:\/\/([1-9][0-9]*)$/);
  return match?.[1];
});
const legacyPath = computed(() => Boolean(String(props.modelValue ?? "").trim()) && !managedFileId.value);

watch([managedFileId, () => props.client], async ([id, client]) => {
  selectedFile.value = undefined;
  if (!id || !client) return;
  loading.value = true;
  try {
    selectedFile.value = await client.get(id);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}, { immediate: true });

function emitValue(value?: string) {
  emit("update:modelValue", value);
}

function openFilePicker() {
  if (!props.policyCode) {
    ElMessage.warning(t("metaForm.managedFilePolicyMissing"));
    return;
  }
  fileInput.value?.click();
}

async function uploadSelectedFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file || !props.client || !props.policyCode) return;
  uploading.value = true;
  try {
    const uploaded = await props.client.upload(file, props.policyCode);
    selectedFile.value = uploaded;
    emitValue(`managed-file://${uploaded.id}`);
    ElMessage.success(t("metaForm.managedFileUploadSuccess"));
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    uploading.value = false;
  }
}

async function openSelectionDialog() {
  if (!props.policyCode) {
    ElMessage.warning(t("metaForm.managedFilePolicyMissing"));
    return;
  }
  pageNo.value = 1;
  selectionVisible.value = true;
  await loadFiles();
}

async function loadFiles() {
  if (!props.client) return;
  listLoading.value = true;
  try {
    const page = await props.client.queryPage({
      pageNum: pageNo.value,
      pageSize: pageSize.value,
      policyCode: props.policyCode,
      status: "READY",
    });
    availableFiles.value = page.items;
    total.value = page.total;
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    listLoading.value = false;
  }
}

function selectFile(file: ManagedFileView) {
  selectedFile.value = file;
  emitValue(`managed-file://${file.id}`);
  selectionVisible.value = false;
}

async function download(file: ManagedFileView) {
  if (!props.client || file.id == null) return;
  try {
    const blob = await props.client.download(file.id);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = file.fileName;
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function deleteFile(file: ManagedFileView) {
  if (!props.client || file.id == null) return;
  try {
    await ElMessageBox.confirm(
      t("metaForm.managedFileDeleteConfirm", { name: file.fileName }),
      t("common.confirm"),
      { type: "warning" },
    );
    await props.client.delete(file.id);
    if (String(file.id) === managedFileId.value) emitValue(undefined);
    await loadFiles();
  } catch (error) {
    if (error !== "cancel" && error !== "close") ElMessage.error(errorMessage(error));
  }
}

function formatBytes(value?: number) {
  if (value == null || !Number.isFinite(value)) return "-";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function formatDate(value?: string) {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : t("metaForm.managedFileOperationFailed");
}
</script>

<style scoped>
.managed-file-field { width: 100%; display: grid; gap: 10px; }
.managed-file-field__summary { display: flex; justify-content: space-between; gap: 16px; padding: 10px 12px; border: 1px solid var(--studio-border); border-radius: 6px; }
.managed-file-field__identity, .managed-file-field__meta { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.managed-file-field__identity strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.managed-file-field__identity span, .managed-file-field__meta span, .managed-file-field__hint, .managed-file-field__loading { color: var(--studio-text-soft); font-size: 12px; }
.managed-file-field__meta { align-items: flex-end; flex-shrink: 0; }
.managed-file-field__actions { display: flex; flex-wrap: wrap; gap: 8px; }
.managed-file-field__native-input { display: none; }
.managed-file-field__hint { margin: 0; }
.managed-file-field__pagination { justify-content: flex-end; margin-top: 16px; }
@media (max-width: 640px) { .managed-file-field__summary { flex-direction: column; } .managed-file-field__meta { align-items: flex-start; } }
</style>
