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
          <div class="multiple-section-actions">
            <el-button type="primary" plain @click="actions.appendSectionRow(section)">{{ t("common.addRow") }}</el-button>
          </div>
          <el-table :data="actions.editorSectionRows(section)" border>
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
                  @update:model-value="actions.updateSectionRowField(section, $index, field.fieldKey, $event)"
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
                <el-button link type="danger" @click="actions.removeSectionRow(section, $index)">{{ t("common.remove") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
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

    <div class="drawer-actions">
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" @click="actions.saveModel">{{ t("common.save") }}</el-button>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import type { DataSourceDefinition, MetadataFieldDefinition, MetadataSchemaDefinition } from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard, StatusPill } from "@studio/ui";
import { useI18n } from "vue-i18n";
import HttpWebServiceContractEditor from "@/components/HttpWebServiceContractEditor.vue";
import type { ModelFormState, ModelMetaSection } from "./modelViewTypes";

interface ModelEditorActions {
  handleModelDatasourceChange: () => void;
  handleModelSchemaChange: () => void;
  appendSectionRow: (section: ModelMetaSection) => void;
  editorSectionRows: (section: ModelMetaSection) => Record<string, unknown>[];
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
  datasourceOptions: DataSourceDefinition[];
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

.multiple-section-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 10px;
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 14px;
}
</style>
