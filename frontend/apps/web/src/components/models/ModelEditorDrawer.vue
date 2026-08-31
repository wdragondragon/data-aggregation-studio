<template>
  <el-drawer
    :model-value="modelValue"
    size="72%"
    :title="isEditingModel ? t('web.models.editDialogTitle') : t('web.models.addDialogTitle')"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="editor-description">
      {{ isEditingModel ? t("web.models.editDialogDescription") : t("web.models.addDialogDescription") }}
    </div>

    <SectionCard :title="panelTitle" :description="panelDescription">
      <div class="studio-form-grid">
        <el-form-item :label="t('web.models.datasourceLabel')">
          <el-select
            v-model="form.datasourceId"
            :disabled="isEditingModel"
            clearable
            :placeholder="t('web.models.manualDatasourcePlaceholder')"
            @change="actions.handleModelDatasourceChange"
          >
            <el-option
              v-for="item in datasourceOptions"
              :key="item.id"
              :label="`${item.name} (${item.typeCode})`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('web.models.schemaBinding')">
          <el-select
            v-model="form.schemaVersionId"
            clearable
            :placeholder="t('web.models.schemaBindingPlaceholder')"
            @change="actions.handleModelSchemaChange"
          >
            <el-option
              v-for="schema in modelSchemas"
              :key="schema.id"
              :label="`${schema.schemaName} v${schema.versionNumber ?? 1}`"
              :value="schema.currentVersionId ?? schema.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('web.models.modelKindLabel')">
          <el-input :model-value="form.modelKind" readonly />
        </el-form-item>
        <el-form-item :label="modelNameLabel">
          <el-input v-model="form.name" :placeholder="modelNamePlaceholder" />
        </el-form-item>
        <el-form-item :label="physicalLocatorLabel">
          <el-input v-model="form.physicalLocator" :placeholder="physicalLocatorPlaceholder" />
        </el-form-item>
      </div>

      <div v-if="showManualDatasourceHint" class="soft-panel warning-hint">
        {{ t("web.models.manualDatasourceOnly") }}
      </div>
      <div v-if="!hasSelectedModelSchema" class="soft-panel empty-hint">
        {{ t("web.models.noModelSchema") }}
      </div>
    </SectionCard>

    <template v-for="section in sections" :key="section.key">
      <SectionCard :title="section.title" :description="section.description">
        <template #actions>
          <StatusPill
            :label="section.binding === 'TECHNICAL' ? t('metaForm.technicalTitle') : t('metaForm.businessTitle')"
            :tone="section.binding === 'TECHNICAL' ? 'primary' : 'success'"
          />
        </template>

        <template v-if="section.displayMode === 'MULTIPLE'">
          <div class="multiple-section-toolbar">
            <span v-if="isFieldSection(section)" class="multiple-section-toolbar__summary">
              {{ t("web.models.fieldRowCount", { count: actions.editorSectionRows(section).length }) }}
            </span>
            <div class="multiple-section-actions">
              <el-button
                v-if="canImportFieldJson(section)"
                plain
                :icon="Upload"
                @click="openFieldImport(section)"
              >
                {{ t("web.models.fieldImportAction") }}
              </el-button>
              <el-button type="primary" plain :icon="Plus" @click="appendSectionRow(section)">
                {{ t("common.addRow") }}
              </el-button>
            </div>
          </div>
          <el-table
            :data="visibleSectionRows(section)"
            :max-height="isFieldSection(section) ? 560 : undefined"
            border
          >
            <el-table-column
              v-for="field in section.fields"
              :key="field.fieldKey"
              :label="field.fieldName"
              min-width="150"
            >
              <template #default="{ row, $index }">
                <component
                  :is="actions.resolveRowEditorComponent(field)"
                  v-bind="actions.resolveRowEditorProps(field)"
                  :model-value="row[field.fieldKey]"
                  @update:model-value="actions.updateSectionRowField(section, absoluteSectionRowIndex(section, $index), field.fieldKey, $event)"
                >
                  <template v-if="field.componentType === 'SELECT'">
                    <el-option
                      v-for="option in field.options ?? []"
                      :key="option"
                      :label="option"
                      :value="option"
                    />
                  </template>
                </component>
              </template>
            </el-table-column>
            <el-table-column :label="t('web.metadata.actions')" width="100" fixed="right">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeSectionRow(section, $index)">{{ t("common.remove") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="isFieldSection(section) && actions.editorSectionRows(section).length > fieldPageSize" class="field-table-pagination">
            <el-pagination
              background
              layout="total, prev, pager, next"
              :current-page="sectionPage(section)"
              :page-size="fieldPageSize"
              :total="actions.editorSectionRows(section).length"
              @update:current-page="setSectionPage(section, $event)"
            />
          </div>
        </template>

        <MetaFormRenderer
          v-else-if="!isHttpTechnicalSingleSection(section)"
          :fields="section.fields"
          :model-value="actions.sectionModelValue(section, true)"
          :dynamic-function-fields="dynamicFunctionFields"
          @update:model-value="actions.updateSectionModelValue(section, $event)"
        />
        <HttpWebServiceContractEditor
          v-else
          :fields="section.fields"
          :model-value="actions.sectionModelValue(section, true)"
          :dynamic-function-fields="dynamicFunctionFields"
          @update:model-value="actions.updateSectionModelValue(section, $event)"
        />
      </SectionCard>
    </template>

    <SectionCard
      v-if="isHttpModelEditor"
      title="Reader 默认参数"
      description="这些参数会作为采集任务和 Flink 读取 HTTP 模型时的默认请求参数，采集任务里仍可覆盖。"
    >
      <el-alert
        v-if="httpReaderSchemaError"
        type="warning"
        :closable="false"
        show-icon
        :title="httpReaderSchemaError"
      />
      <HttpWebServiceOptionsEditor
        v-if="isHttpSoapMode && httpReaderFields.length"
        :fields="httpReaderFields"
        :model-value="httpReaderOptions"
        :dynamic-function-fields="httpDynamicFunctionFields"
        :soap-contract="httpSoapContract"
        :soap-field-names="httpSoapFieldNames"
        @update:model-value="updateHttpReaderOptions"
      />
      <HttpRequestOptionsEditor
        v-else-if="httpReaderFields.length"
        :fields="httpReaderFields"
        :model-value="httpReaderOptions"
        :dynamic-function-fields="httpDynamicFunctionFields"
        @update:model-value="updateHttpReaderOptions"
      />
      <el-empty v-else-if="!httpReaderSchemaLoading" description="暂无 Reader 参数元模型" />
    </SectionCard>

    <ModelFieldJsonImportDialog
      v-if="fieldImportSection"
      v-model="fieldImportOpen"
      :fields="fieldImportSection.fields"
      :current-rows="actions.editorSectionRows(fieldImportSection)"
      :is-editing-model="isEditingModel"
      @imported="replaceImportedFieldRows"
    />

    <div class="drawer-actions">
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" @click="actions.saveModel">{{ t("common.save") }}</el-button>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import type { DataSourceOptionView, MetadataFieldDefinition, MetadataSchemaDefinition, PluginRuntimeOptionSchemaView } from "@studio/api-sdk";
import { Plus, Upload } from "@element-plus/icons-vue";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard, StatusPill } from "@studio/ui";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { studioApi } from "@/api/studio";
import HttpRequestOptionsEditor from "@/components/HttpRequestOptionsEditor.vue";
import HttpWebServiceContractEditor from "@/components/HttpWebServiceContractEditor.vue";
import HttpWebServiceOptionsEditor from "@/components/HttpWebServiceOptionsEditor.vue";
import { httpReaderDynamicFunctionFields, mergeRuntimeDefaults } from "@/components/collection-task/collectionTaskEditorSupport";
import ModelFieldJsonImportDialog from "./ModelFieldJsonImportDialog.vue";
import {
  MODEL_FIELD_TABLE_PAGE_SIZE,
  modelFieldPageCount,
  modelFieldRowsForPage,
  normalizeModelFieldPage,
} from "./modelFieldJsonImport";
import type { ModelFormState, ModelMetaSection } from "./modelViewTypes";

interface ModelEditorActions {
  handleModelDatasourceChange: () => void;
  handleModelSchemaChange: () => void;
  appendSectionRow: (section: ModelMetaSection) => void;
  editorSectionRows: (section: ModelMetaSection) => Record<string, unknown>[];
  replaceSectionRows: (section: ModelMetaSection, rows: Record<string, unknown>[]) => void;
  resolveRowEditorComponent: (field: MetadataFieldDefinition) => unknown;
  resolveRowEditorProps: (field: MetadataFieldDefinition) => Record<string, unknown>;
  updateSectionRowField: (section: ModelMetaSection, index: number, fieldKey: string, value: unknown) => void;
  removeSectionRow: (section: ModelMetaSection, index: number) => void;
  sectionModelValue: (section: ModelMetaSection, editor?: boolean) => Record<string, unknown>;
  updateSectionModelValue: (section: ModelMetaSection, value: Record<string, unknown>) => void;
  saveModel: () => void | Promise<void>;
}

const props = defineProps<{
  modelValue: boolean;
  isEditingModel: boolean;
  panelTitle: string;
  panelDescription: string;
  form: ModelFormState;
  saving: boolean;
  datasourceOptions: DataSourceOptionView[];
  modelSchemas: MetadataSchemaDefinition[];
  hasSelectedModelSchema: boolean;
  showManualDatasourceHint: boolean;
  modelNameLabel: string;
  modelNamePlaceholder: string;
  physicalLocatorLabel: string;
  physicalLocatorPlaceholder: string;
  sections: ModelMetaSection[];
  dynamicFunctionFields: string[];
  actions: ModelEditorActions;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
}>();

const { t } = useI18n();
const httpReaderSchema = ref<PluginRuntimeOptionSchemaView | undefined>();
const httpReaderSchemaLoading = ref(false);
const httpReaderSchemaError = ref("");
const loadedHttpReaderSchemaKey = ref("");
let httpReaderSchemaRequestId = 0;
const httpDynamicFunctionFields = httpReaderDynamicFunctionFields;
const httpReaderContractFields = new Set([
  "url",
  "mode",
  "protocolmode",
  "soapversion",
  "soapaction",
  "resulttype",
  "responsestatus",
  "totalcodepath",
  "columns",
]);

const selectedDatasource = computed(() =>
  props.datasourceOptions.find((item) => String(item.id ?? "") === String(props.form.datasourceId ?? "")));
const selectedDatasourceType = computed(() => String(selectedDatasource.value?.typeCode ?? "").trim().toLowerCase());
const isHttpModelEditor = computed(() => selectedDatasourceType.value === "http");
const httpProtocolMode = computed(() => String(props.form.technicalMetadata?.protocolMode ?? "REST_JSON").trim().toUpperCase());
const isHttpSoapMode = computed(() => httpProtocolMode.value === "SOAP");
const httpReaderFields = computed(() => (httpReaderSchema.value?.fields ?? []).filter((field) =>
  !isHttpReaderContractField(field.fieldKey)));
const httpReaderOptions = computed(() => {
  const value = props.form.technicalMetadata?.readerOptions;
  return sanitizeHttpReaderOptions(value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {});
});
const httpColumnOptions = computed(() => {
  const columns = props.form.technicalMetadata?.columns;
  if (!Array.isArray(columns)) {
    return [] as string[];
  }
  const seen = new Set<string>();
  const result: string[] = [];
  for (const item of columns) {
    if (!item || typeof item !== "object") {
      continue;
    }
    const name = String((item as Record<string, unknown>).name ?? (item as Record<string, unknown>).columnName ?? "").trim();
    if (!name || seen.has(name)) {
      continue;
    }
    seen.add(name);
    result.push(name);
  }
  return result;
});
const httpSoapFieldNames = computed(() => httpColumnOptions.value);
const httpSoapContract = computed(() => ({
  soapVersion: String(props.form.technicalMetadata?.soapVersion ?? "SOAP_11"),
  namespaceUri: String(props.form.technicalMetadata?.namespaceUri ?? "urn:studio"),
  operationName: String(props.form.technicalMetadata?.operationName ?? ""),
  requestRootName: String(props.form.technicalMetadata?.requestRootName ?? props.form.technicalMetadata?.operationName ?? "Operation"),
}));

watch(
  () => [props.modelValue, isHttpModelEditor.value, isHttpSoapMode.value],
  () => {
    void ensureHttpReaderSchema();
  },
  { immediate: true },
);

watch(
  httpProtocolMode,
  (protocolMode, previousProtocolMode) => {
    if (!props.modelValue || !isHttpModelEditor.value || !previousProtocolMode || protocolMode === previousProtocolMode) {
      return;
    }
    const next: Record<string, unknown> = {
      ...httpReaderOptions.value,
      contentType: defaultHttpContentType(protocolMode),
    };
    if ((protocolMode === "SOAP") !== (previousProtocolMode === "SOAP")) {
      next.requestBody = "";
    }
    if (protocolMode !== "SOAP") {
      next.header = removeHeaderIgnoreCase(next.header, "SOAPAction");
    }
    updateHttpReaderOptions(next);
  },
);

watch(
  () => String(props.form.technicalMetadata?.soapVersion ?? "SOAP_11").trim().toUpperCase(),
  (soapVersion, previousSoapVersion) => {
    if (!props.modelValue || !isHttpSoapMode.value || !previousSoapVersion || soapVersion === previousSoapVersion) {
      return;
    }
    updateHttpReaderOptions({
      ...httpReaderOptions.value,
      contentType: soapVersion === "SOAP_12"
        ? "application/soap+xml;charset=UTF-8"
        : "text/xml;charset=UTF-8",
    });
  },
);

watch(
  () => String(props.form.technicalMetadata?.soapAction ?? ""),
  (soapAction, previousSoapAction) => {
    if (!props.modelValue || !isHttpSoapMode.value || previousSoapAction === undefined || soapAction === previousSoapAction) {
      return;
    }
    updateHttpReaderOptions({
      ...httpReaderOptions.value,
      header: removeHeaderIgnoreCase(httpReaderOptions.value.header, "SOAPAction"),
    });
  },
);

const fieldPageSize = MODEL_FIELD_TABLE_PAGE_SIZE;
const sectionPages = reactive<Record<string, number>>({});
const fieldImportOpen = ref(false);
const fieldImportSection = ref<ModelMetaSection>();

function isFieldSection(section: ModelMetaSection) {
  return section.binding === "TECHNICAL" && String(section.metaModelCode ?? "").trim().toLowerCase() === "field";
}

function canImportFieldJson(section: ModelMetaSection) {
  return isFieldSection(section);
}

function sectionPage(section: ModelMetaSection) {
  return normalizeModelFieldPage(sectionPages[section.key] ?? 1, actionsRowCount(section), fieldPageSize);
}

function setSectionPage(section: ModelMetaSection, page: number) {
  sectionPages[section.key] = normalizeModelFieldPage(page, actionsRowCount(section), fieldPageSize);
}

function actionsRowCount(section: ModelMetaSection) {
  return props.actions.editorSectionRows(section).length;
}

function visibleSectionRows(section: ModelMetaSection) {
  const rows = props.actions.editorSectionRows(section);
  return isFieldSection(section) ? modelFieldRowsForPage(rows, sectionPage(section), fieldPageSize) : rows;
}

function absoluteSectionRowIndex(section: ModelMetaSection, visibleIndex: number) {
  return isFieldSection(section) ? (sectionPage(section) - 1) * fieldPageSize + visibleIndex : visibleIndex;
}

function appendSectionRow(section: ModelMetaSection) {
  props.actions.appendSectionRow(section);
  if (isFieldSection(section)) {
    sectionPages[section.key] = modelFieldPageCount(actionsRowCount(section), fieldPageSize);
  }
}

function removeSectionRow(section: ModelMetaSection, visibleIndex: number) {
  props.actions.removeSectionRow(section, absoluteSectionRowIndex(section, visibleIndex));
  if (isFieldSection(section)) {
    sectionPages[section.key] = normalizeModelFieldPage(sectionPage(section), actionsRowCount(section), fieldPageSize);
  }
}

function openFieldImport(section: ModelMetaSection) {
  fieldImportSection.value = section;
  fieldImportOpen.value = true;
}

function replaceImportedFieldRows(rows: Record<string, unknown>[]) {
  if (!fieldImportSection.value) {
    return;
  }
  props.actions.replaceSectionRows(fieldImportSection.value, rows);
  sectionPages[fieldImportSection.value.key] = 1;
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      Object.keys(sectionPages).forEach((key) => delete sectionPages[key]);
      fieldImportOpen.value = false;
      fieldImportSection.value = undefined;
    }
  },
);

function isHttpTechnicalSingleSection(section: ModelMetaSection) {
  if (section.binding !== "TECHNICAL" || section.displayMode === "MULTIPLE") {
    return false;
  }
  return isHttpModelEditor.value;
}

async function ensureHttpReaderSchema() {
  if (!isHttpModelEditor.value || !props.modelValue) {
    httpReaderSchemaRequestId++;
    httpReaderSchemaLoading.value = false;
    httpReaderSchema.value = undefined;
    loadedHttpReaderSchemaKey.value = "";
    return;
  }
  const protocolMode = isHttpSoapMode.value ? "SOAP" : "";
  const key = `http-reader:${protocolMode}`;
  if (loadedHttpReaderSchemaKey.value === key && httpReaderSchema.value) {
    applyHttpReaderDefaults(httpReaderFields.value);
    return;
  }
  const requestId = ++httpReaderSchemaRequestId;
  httpReaderSchemaLoading.value = true;
  httpReaderSchemaError.value = "";
  httpReaderSchema.value = undefined;
  loadedHttpReaderSchemaKey.value = "";
  try {
    const schema = await studioApi.catalog.runtimeOptionSchema({
      role: "reader",
      datasourceType: "http",
      protocolMode: protocolMode || undefined,
    });
    if (requestId !== httpReaderSchemaRequestId) {
      return;
    }
    httpReaderSchema.value = schema;
    loadedHttpReaderSchemaKey.value = key;
    applyHttpReaderDefaults((schema.fields ?? []).filter((field) =>
      !isHttpReaderContractField(field.fieldKey)));
  } catch (error) {
    if (requestId === httpReaderSchemaRequestId) {
      httpReaderSchemaError.value = error instanceof Error ? error.message : "Reader 参数元模型加载失败";
    }
  } finally {
    if (requestId === httpReaderSchemaRequestId) {
      httpReaderSchemaLoading.value = false;
    }
  }
}

function applyHttpReaderDefaults(fields: MetadataFieldDefinition[]) {
  const merged = mergeRuntimeDefaults(httpReaderOptions.value, fields);
  if (JSON.stringify(merged) !== JSON.stringify(httpReaderOptions.value)) {
    updateHttpReaderOptions(merged);
  }
}

function updateHttpReaderOptions(value: Record<string, unknown>) {
  props.form.technicalMetadata = {
    ...(props.form.technicalMetadata ?? {}),
    readerOptions: sanitizeHttpReaderOptions(value),
  };
}

function sanitizeHttpReaderOptions(value: Record<string, unknown>) {
  const result = { ...(value ?? {}) };
  Object.keys(result).forEach((key) => {
    if (isHttpReaderContractField(key)) {
      delete result[key];
    }
  });
  return result;
}

function isHttpReaderContractField(fieldKey: unknown) {
  return httpReaderContractFields.has(String(fieldKey ?? "").trim().toLowerCase());
}

function defaultHttpContentType(protocolMode: string) {
  if (protocolMode === "SOAP") {
    return String(props.form.technicalMetadata?.soapVersion ?? "SOAP_11").toUpperCase() === "SOAP_12"
      ? "application/soap+xml;charset=UTF-8"
      : "text/xml;charset=UTF-8";
  }
  return protocolMode === "REST_XML"
    ? "application/xml;charset=UTF-8"
    : "application/json;charset=utf-8";
}

function removeHeaderIgnoreCase(value: unknown, headerName: string) {
  try {
    const parsed = typeof value === "string" ? JSON.parse(value || "{}") : value;
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return value;
    }
    const result = { ...(parsed as Record<string, unknown>) };
    for (const key of Object.keys(result)) {
      if (key.toLowerCase() === headerName.toLowerCase()) {
        delete result[key];
      }
    }
    return JSON.stringify(result);
  } catch {
    return value;
  }
}
</script>

<style scoped>
.editor-description {
  margin-bottom: 12px;
}

.warning-hint,
.empty-hint {
  margin-bottom: 12px;
}

.warning-hint {
  border-color: rgba(36, 99, 235, 0.2);
  background: rgba(219, 234, 254, 0.7);
}

.multiple-section-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.multiple-section-toolbar__summary {
  color: var(--studio-text-soft);
  font-size: 13px;
}

.multiple-section-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.field-table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
  overflow-x: auto;
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 14px;
}

@media (max-width: 640px) {
  .multiple-section-toolbar,
  .multiple-section-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .multiple-section-actions,
  .multiple-section-actions > .el-button {
    width: 100%;
  }

  .multiple-section-actions > .el-button + .el-button {
    margin-left: 0;
  }

  .field-table-pagination {
    justify-content: flex-start;
  }

  .field-table-pagination :deep(.el-pagination) {
    --el-pagination-button-height: 24px;
    --el-pagination-button-width: 20px;
  }

  .field-table-pagination :deep(.el-pagination__total) {
    display: none;
  }

  .field-table-pagination :deep(.el-pagination.is-background .btn-next),
  .field-table-pagination :deep(.el-pagination.is-background .btn-prev),
  .field-table-pagination :deep(.el-pagination.is-background .el-pager li) {
    margin: 0 1px;
  }
}
</style>
