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

    <ModelFieldJsonImportDialog
      v-if="fieldImportSection"
      v-model="fieldImportOpen"
      :fields="fieldImportSection.fields"
      :current-count="actions.editorSectionRows(fieldImportSection).length"
      @imported="replaceImportedFieldRows"
    />

    <div class="drawer-actions">
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" @click="actions.saveModel">{{ t("common.save") }}</el-button>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import type { DataSourceOptionView, MetadataFieldDefinition, MetadataSchemaDefinition } from "@studio/api-sdk";
import { Plus, Upload } from "@element-plus/icons-vue";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard, StatusPill } from "@studio/ui";
import { reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import HttpWebServiceContractEditor from "@/components/HttpWebServiceContractEditor.vue";
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
const fieldPageSize = MODEL_FIELD_TABLE_PAGE_SIZE;
const sectionPages = reactive<Record<string, number>>({});
const fieldImportOpen = ref(false);
const fieldImportSection = ref<ModelMetaSection>();

function isFieldSection(section: ModelMetaSection) {
  return section.binding === "TECHNICAL" && String(section.metaModelCode ?? "").trim().toLowerCase() === "field";
}

function canImportFieldJson(section: ModelMetaSection) {
  return !props.isEditingModel && isFieldSection(section);
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
  const datasource = props.datasourceOptions.find((item) => String(item.id ?? "") === String(props.form.datasourceId ?? ""));
  return String(datasource?.typeCode ?? "").trim().toLowerCase() === "http";
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
