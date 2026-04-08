<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.datasources.heading") }}</h3>
        <p>{{ t("web.datasources.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" @click="openCreate">{{ t("common.newDatasource") }}</el-button>
        <el-button plain @click="loadPage">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.datasources.tableTitle')" :description="t('web.datasources.tableDescription')">
        <el-table :data="pagedDatasources" border>
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">
              {{ getPaginatedRowNumber(datasourcePagination, $index) }}
            </template>
          </el-table-column>
          <el-table-column prop="name" :label="t('web.datasources.nameColumn')" min-width="180" />
        <el-table-column prop="typeCode" :label="t('web.datasources.typeColumn')" width="120" />
        <el-table-column :label="t('web.datasources.enabledColumn')" width="110" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="row.enabled ? t('common.on') : t('common.off')" :tone="row.enabled ? 'success' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.datasources.executableColumn')" width="130" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="row.executable ? t('common.runnable') : t('common.catalogOnly')" :tone="row.executable ? 'success' : 'warning'" />
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" :label="t('web.datasources.updatedColumn')" min-width="170" align="center" header-align="center" />
        <el-table-column :label="t('web.datasources.actionsColumn')" width="140" align="center" header-align="center">
          <template #default="{ row }">
            <OverflowActionGroup :items="buildDatasourceActions(row)" />
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="datasourcePagination.page"
          v-model:page-size="datasourcePagination.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="datasources.length"
        />
      </div>
    </SectionCard>

    <el-drawer v-model="drawerOpen" size="70%" :title="form.id ? t('web.datasources.drawerEditTitle') : t('web.datasources.drawerCreateTitle')">
      <div class="studio-grid columns-2">
        <SectionCard :title="t('web.datasources.identityTitle')" :description="t('web.datasources.identityDescription')">
          <div class="studio-form-grid">
            <el-form-item :label="t('web.datasources.datasourceName')">
              <el-input v-model="form.name" :placeholder="t('web.datasources.datasourceNamePlaceholder')" />
            </el-form-item>
            <el-form-item :label="t('web.datasources.pluginType')">
              <el-select v-model="form.typeCode" :placeholder="t('web.datasources.pluginTypePlaceholder')" @change="handleTypeChange">
                <el-option
                  v-for="pluginName in sourceTypeOptions"
                  :key="pluginName"
                  :label="pluginName"
                  :value="pluginName"
                />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('web.datasources.schemaBinding')">
              <el-select v-model="form.schemaVersionId" clearable :placeholder="t('web.datasources.schemaBindingPlaceholder')">
                <el-option
                  v-for="schema in datasourceSchemas"
                  :key="schema.id"
                  :label="`${schema.schemaName} v${schema.versionNumber ?? 1}`"
                  :value="schema.currentVersionId ?? schema.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('web.datasources.executionSurface')">
              <el-switch v-model="form.executable" inline-prompt :active-text="t('common.run')" :inactive-text="t('common.catalog')" />
            </el-form-item>
            <el-form-item :label="t('web.datasources.enabled')">
              <el-switch v-model="form.enabled" inline-prompt :active-text="t('common.on')" :inactive-text="t('common.off')" />
            </el-form-item>
          </div>
        </SectionCard>

        <SectionCard :title="t('web.datasources.executionSupportTitle')" :description="t('web.datasources.executionSupportDescription')">
          <div class="soft-panel">
            <p><strong>{{ t("web.datasources.executableSourceFamilies") }}</strong></p>
            <div class="tag-row">
              <StatusPill
                v-for="item in executableDatasourceTypes"
                :key="item"
                :label="item"
                tone="success"
              />
            </div>
            <p class="capability-note">
              {{ t("web.datasources.currentTypeLabel") }} <strong>{{ form.typeCode || t("web.datasources.notSelected") }}</strong>
              {{
                form.typeCode && executableDatasourceTypes.includes(form.typeCode)
                  ? t("web.datasources.currentTypeExecutable")
                  : t("web.datasources.currentTypeCatalogOnly")
              }}
            </p>
          </div>
        </SectionCard>
      </div>

      <SectionCard :title="t('web.datasources.technicalTitle')" :description="t('web.datasources.technicalDescription')">
        <MetaFormRenderer
          :fields="technicalFields"
          :model-value="form.technicalMetadata"
          @update:model-value="form.technicalMetadata = $event"
        />
      </SectionCard>

      <SectionCard :title="t('web.datasources.businessTitle')" :description="t('web.datasources.businessDescription')">
        <div class="meta-section-stack">
          <div v-if="businessSections.length === 0" class="soft-panel empty-hint section-empty">
            {{ t("web.models.metaSectionEmpty") }}
          </div>

          <div
            v-for="section in businessSections"
            :key="section.key"
            class="soft-panel datasource-meta-section"
          >
            <div class="datasource-meta-section__header">
              <div>
                <strong>{{ section.title }}</strong>
                <p>{{ section.description }}</p>
              </div>
              <StatusPill
                :label="section.displayMode === 'MULTIPLE' ? t('web.metadata.displayMultiple') : t('web.metadata.displaySingle')"
                tone="success"
              />
            </div>

            <template v-if="section.displayMode === 'MULTIPLE'">
              <div class="multiple-section-actions">
                <el-button type="primary" plain @click="appendSectionRow(section)">{{ t("common.addRow") }}</el-button>
              </div>
              <el-table :data="sectionRows(section)" border>
                <el-table-column
                  v-for="field in section.fields"
                  :key="field.fieldKey"
                  :label="field.fieldName"
                  min-width="150"
                >
                  <template #default="{ row, $index }">
                    <component
                      :is="resolveRowEditorComponent(field)"
                      v-bind="resolveRowEditorProps(field)"
                      :model-value="row[field.fieldKey]"
                      @update:model-value="updateSectionRowField(section, $index, field.fieldKey, $event)"
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
                <el-table-column :label="t('web.metadata.actions')" width="100">
                  <template #default="{ $index }">
                    <el-button link type="danger" @click="removeSectionRow(section, $index)">{{ t("common.remove") }}</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <MetaFormRenderer
              v-else
              :fields="section.fields"
              :model-value="sectionModelValue(section)"
              @update:model-value="updateSectionModelValue(section, $event)"
            />
          </div>
        </div>
      </SectionCard>

      <div class="drawer-actions">
        <el-button @click="drawerOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button @click="testCurrent">{{ t("web.datasources.testConnection") }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveDatasource">{{ t("common.save") }}</el-button>
      </div>

      <SectionCard v-if="testResult" :title="t('web.datasources.testResultTitle')" :description="t('web.datasources.testResultDescription')">
        <pre class="json-block studio-mono">{{ prettyJson(testResult) }}</pre>
      </SectionCard>
    </el-drawer>

    <el-dialog v-model="discoverDialogOpen" :title="t('web.datasources.discoveredModelsTitle')" width="62%">
      <el-table :data="discoveredModels" border>
        <el-table-column prop="name" :label="t('web.datasources.modelNameColumn')" min-width="180" />
        <el-table-column :label="t('web.datasources.modelKindColumn')" width="120" align="center" header-align="center">
          <template #default="{ row }">
            {{ formatModelKind(t, row.modelKind) }}
          </template>
        </el-table-column>
        <el-table-column prop="physicalLocator" :label="t('web.datasources.physicalLocatorColumn')" min-width="220" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElInput, ElInputNumber, ElMessage, ElMessageBox, ElSelect, ElSwitch } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  CapabilityMatrix,
  ConnectionTestResult,
  DataSourceDefinition,
  MetadataFieldDefinition,
  MetadataSchemaDefinition,
  ModelDiscoveryResult,
  PluginCatalogEntry,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { OverflowActionGroup, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { getPaginatedRowNumber, useClientPagination } from "@/composables/useClientPagination";
import {
  ensureBusinessMetaModelEntries,
  getBusinessMetaModelRows,
  getBusinessMetaModelValues,
  parseMetaModelSchema,
  sameEntityId,
  setBusinessMetaModelRows,
  setBusinessMetaModelValues,
} from "@/utils/metaModel";
import { cloneDeep, formatModelKind, prettyJson } from "@/utils/studio";

interface DataSourceForm extends DataSourceDefinition {
  name: string;
  typeCode: string;
  technicalMetadata: Record<string, unknown>;
  businessMetadata: Record<string, unknown>;
}

interface DatasourceBusinessSection {
  key: string;
  schema: MetadataSchemaDefinition;
  title: string;
  description: string;
  displayMode: "SINGLE" | "MULTIPLE";
  fields: MetadataFieldDefinition[];
}

const { t } = useI18n();

const datasources = ref<DataSourceDefinition[]>([]);
const { pagination: datasourcePagination, pagedItems: pagedDatasources, resetPagination: resetDatasourcePagination } = useClientPagination(datasources);
const schemas = ref<MetadataSchemaDefinition[]>([]);
const sourcePlugins = ref<PluginCatalogEntry[]>([]);
const capabilityMatrix = reactive<CapabilityMatrix>({
  executableSourceTypes: [],
  plugins: [],
});
const executableDatasourceTypes = computed(() => capabilityMatrix.executableDatasourceTypes ?? capabilityMatrix.executableSourceTypes);
const drawerOpen = ref(false);
const saving = ref(false);
const testResult = ref<ConnectionTestResult | null>(null);
const discoverDialogOpen = ref(false);
const discoveredModels = ref<ModelDiscoveryResult["models"]>([]);
const form = reactive<DataSourceForm>({
  name: "",
  typeCode: "",
  enabled: true,
  executable: false,
  schemaVersionId: undefined,
  technicalMetadata: {},
  businessMetadata: {},
});

const sourceTypeOptions = computed(() => Array.from(new Set(sourcePlugins.value.map((item) => item.pluginName))));

const fallbackTechnicalFields = computed<MetadataFieldDefinition[]>(() => {
  const businessDefault: MetadataFieldDefinition[] = [];
  if (["mysql8", "postgres", "oracle", "dm"].includes(form.typeCode)) {
    return [
      { fieldKey: "host", fieldName: "Host", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "port", fieldName: "Port", scope: "TECHNICAL", componentType: "NUMBER", valueType: "INTEGER", required: true, defaultValue: "3306" },
      { fieldKey: "database", fieldName: "Database", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "userName", fieldName: t("web.login.usernameLabel"), scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "password", fieldName: t("web.login.passwordLabel"), scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", required: true },
      { fieldKey: "usePool", fieldName: "Use Connection Pool", scope: "TECHNICAL", componentType: "SWITCH", valueType: "BOOLEAN", defaultValue: "true" },
      ...businessDefault,
    ];
  }
  if (["kafka", "rocketmq", "rabbitmq"].includes(form.typeCode)) {
    return [
      { fieldKey: "brokers", fieldName: "Brokers", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING", required: true },
      { fieldKey: "username", fieldName: t("web.login.usernameLabel"), scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
      { fieldKey: "password", fieldName: t("web.login.passwordLabel"), scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", sensitive: true },
    ];
  }
  if (["minio", "ftp", "sftp"].includes(form.typeCode)) {
    return [
      { fieldKey: "endpoint", fieldName: "Endpoint", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "bucket", fieldName: "Bucket or Path", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "accessKey", fieldName: "Access Key", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
      { fieldKey: "secretKey", fieldName: "Secret Key", scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", sensitive: true },
    ];
  }
  return [
    { fieldKey: "endpoint", fieldName: "Endpoint", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
    { fieldKey: "username", fieldName: t("web.login.usernameLabel"), scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
    { fieldKey: "password", fieldName: t("web.login.passwordLabel"), scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", sensitive: true },
  ];
});

const datasourceSchemas = computed(() =>
  schemas.value.filter((schema) => {
    if (schema.objectType !== "datasource" || schema.typeCode !== form.typeCode) {
      return false;
    }
    const config = parseMetaModelSchema(schema).config;
    return config.domain === "TECHNICAL" && config.metaModelCode === "source";
  }),
);
const businessSchemas = computed(() =>
  schemas.value.filter((schema) => {
    const config = parseMetaModelSchema(schema).config;
    return form.typeCode && config.domain === "BUSINESS" && config.metaModelCode === "source";
  }).sort((left, right) => businessSchemaLabel(left).localeCompare(businessSchemaLabel(right))),
);
const businessSections = computed(() => businessSchemas.value.map(buildBusinessSection));

function buildDatasourceActions(datasource: DataSourceDefinition) {
  return [
    { key: "edit", label: t("common.edit"), type: "primary", onClick: () => editDatasource(datasource) },
    { key: "test", label: t("common.test"), type: "success", onClick: () => testDatasource(datasource) },
    { key: "discover", label: t("common.discover"), type: "warning", onClick: () => discoverModels(datasource) },
    { key: "delete", label: t("common.delete"), type: "danger", onClick: () => deleteDatasource(datasource) },
  ];
}

const matchedSchema = computed(
  () =>
    datasourceSchemas.value.find(
      (schema) => sameEntityId(schema.id, form.schemaVersionId) || sameEntityId(schema.currentVersionId, form.schemaVersionId),
    ) ?? datasourceSchemas.value[0],
);
const technicalFields = computed(
  () => matchedSchema.value?.fields?.filter((field) => field.scope !== "BUSINESS") ?? fallbackTechnicalFields.value,
);

function resetForm() {
  form.id = undefined;
  form.name = "";
  form.typeCode = "";
  form.enabled = true;
  form.executable = false;
  form.schemaVersionId = undefined;
  form.technicalMetadata = {};
  form.businessMetadata = {};
  testResult.value = null;
}

function openCreate() {
  resetForm();
  drawerOpen.value = true;
}

function editDatasource(item: DataSourceDefinition) {
  Object.assign(form, cloneDeep(item));
  applyBusinessMetadataDefaults();
  testResult.value = null;
  drawerOpen.value = true;
}

function handleTypeChange() {
  const schema = datasourceSchemas.value[0];
  form.schemaVersionId = schema?.currentVersionId ?? schema?.id;
  form.executable = executableDatasourceTypes.value.includes(form.typeCode);
  form.technicalMetadata = buildDefaultMetadata(
    schema?.fields?.filter((field) => field.scope !== "BUSINESS") ?? fallbackTechnicalFields.value,
  );
  form.businessMetadata = {};
  applyBusinessMetadataDefaults();
}

function buildDefaultMetadata(fields: MetadataFieldDefinition[]) {
  const defaults: Record<string, unknown> = {};
  for (const field of fields) {
    if (!field.fieldKey || field.defaultValue === undefined || field.defaultValue === null || field.defaultValue === "") {
      continue;
    }
    defaults[field.fieldKey] = parseDefaultValue(field);
  }
  return defaults;
}

function parseDefaultValue(field: MetadataFieldDefinition) {
  const rawValue = field.defaultValue;
  if (rawValue === undefined || rawValue === null) {
    return undefined;
  }
  if (field.valueType === "BOOLEAN") {
    return rawValue === "true";
  }
  if (field.valueType === "INTEGER" || field.valueType === "LONG" || field.valueType === "DECIMAL") {
    const numberValue = Number(rawValue);
    return Number.isNaN(numberValue) ? rawValue : numberValue;
  }
  if (field.valueType === "JSON" || field.valueType === "OBJECT" || field.valueType === "ARRAY") {
    try {
      return JSON.parse(rawValue);
    } catch (error) {
      return rawValue;
    }
  }
  return rawValue;
}

function buildBusinessSection(schema: MetadataSchemaDefinition): DatasourceBusinessSection {
  const parsed = parseMetaModelSchema(schema);
  return {
    key: `business:${schema.id ?? schema.schemaCode}`,
    schema,
    title: `${parsed.config.directoryName || parsed.config.directoryCode || t("web.datasources.businessTitle")} / ${schema.schemaName}`,
    description: parsed.plainDescription || schema.schemaCode,
    displayMode: (parsed.config.displayMode ?? "SINGLE") as "SINGLE" | "MULTIPLE",
    fields: (schema.fields ?? []).filter((field) => field.scope === "BUSINESS"),
  };
}

function businessSchemaLabel(schema: MetadataSchemaDefinition) {
  const config = parseMetaModelSchema(schema).config;
  return `${config.directoryName || config.directoryCode || "business"} / ${schema.schemaName}`;
}

function sectionModelValue(section: DatasourceBusinessSection) {
  return getBusinessMetaModelValues(form.businessMetadata, section.schema);
}

function updateSectionModelValue(section: DatasourceBusinessSection, value: Record<string, unknown>) {
  form.businessMetadata = setBusinessMetaModelValues(form.businessMetadata, section.schema, value);
}

function sectionRows(section: DatasourceBusinessSection) {
  return getBusinessMetaModelRows(form.businessMetadata, section.schema);
}

function setSectionRows(section: DatasourceBusinessSection, rows: Record<string, unknown>[]) {
  form.businessMetadata = setBusinessMetaModelRows(form.businessMetadata, section.schema, rows);
}

function appendSectionRow(section: DatasourceBusinessSection) {
  const rows = sectionRows(section);
  rows.push(buildDefaultMetadata(section.fields));
  setSectionRows(section, rows);
}

function removeSectionRow(section: DatasourceBusinessSection, index: number) {
  const rows = sectionRows(section);
  rows.splice(index, 1);
  setSectionRows(section, rows);
}

function updateSectionRowField(section: DatasourceBusinessSection, index: number, fieldKey: string, value: unknown) {
  const rows = sectionRows(section);
  rows[index] = {
    ...(rows[index] ?? {}),
    [fieldKey]: value,
  };
  setSectionRows(section, rows);
}

function resolveRowEditorComponent(field: MetadataFieldDefinition) {
  switch (field.componentType) {
    case "SWITCH":
      return ElSwitch;
    case "SELECT":
      return ElSelect;
    case "NUMBER":
      return ElInputNumber;
    default:
      return ElInput;
  }
}

function resolveRowEditorProps(field: MetadataFieldDefinition) {
  switch (field.componentType) {
    case "SWITCH":
      return {
        inlinePrompt: true,
        activeText: t("common.on"),
        inactiveText: t("common.off"),
      };
    case "SELECT":
      return {
        clearable: true,
        filterable: true,
        placeholder: field.placeholder ?? field.fieldName,
      };
    case "NUMBER":
      return {
        controlsPosition: "right",
      };
    case "TEXTAREA":
    case "JSON_EDITOR":
    case "SQL_EDITOR":
    case "CODE_EDITOR":
      return {
        type: "textarea",
        rows: 2,
        placeholder: field.placeholder ?? field.fieldName,
      };
    default:
      return {
        placeholder: field.placeholder ?? field.fieldName,
      };
  }
}

function applyBusinessMetadataDefaults() {
  form.businessMetadata = ensureBusinessMetaModelEntries(form.businessMetadata, businessSchemas.value);
  for (const section of businessSections.value) {
    if (section.displayMode === "MULTIPLE") {
      setSectionRows(section, sectionRows(section));
      continue;
    }
    updateSectionModelValue(section, {
      ...buildDefaultMetadata(section.fields),
      ...sectionModelValue(section),
    });
  }
}

async function loadPage() {
  try {
    const [datasourceData, schemaData, capabilityData, pluginData] = await Promise.all([
      studioApi.datasources.list(),
      studioApi.metaSchemas.list(),
      studioApi.catalog.capabilities(),
      studioApi.catalog.plugins("SOURCE"),
    ]);
    datasources.value = datasourceData;
    resetDatasourcePagination();
    schemas.value = schemaData;
    capabilityMatrix.executableSourceTypes = capabilityData.executableSourceTypes;
    capabilityMatrix.executableTargetTypes = capabilityData.executableTargetTypes;
    capabilityMatrix.executableDatasourceTypes = capabilityData.executableDatasourceTypes;
    capabilityMatrix.sourceCapabilities = capabilityData.sourceCapabilities;
    capabilityMatrix.plugins = capabilityData.plugins;
    sourcePlugins.value = pluginData;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.loadFailed"));
  }
}

async function saveDatasource(options: { closeAfterSave?: boolean } = {}) {
  saving.value = true;
  try {
    const saved = await studioApi.datasources.save(cloneDeep(form));
    Object.assign(form, saved);
    ElMessage.success(t("web.datasources.saveSuccess"));
    if (options.closeAfterSave !== false) {
      drawerOpen.value = false;
    }
    await loadPage();
    return saved;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.saveFailed"));
  } finally {
    saving.value = false;
  }
}

async function testDatasource(item: DataSourceDefinition) {
  if (!item.id) {
    return;
  }
  try {
    editDatasource(item);
    drawerOpen.value = true;
    testResult.value = await studioApi.datasources.test(item.id);
    ElMessage.success(t("web.datasources.testSuccess"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.testFailed"));
  }
}

async function testCurrent() {
  try {
    testResult.value = await studioApi.datasources.testCurrent(cloneDeep(form));
    ElMessage.success(t("web.datasources.testSuccess"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.testFailed"));
  }
}

async function discoverModels(item: DataSourceDefinition) {
  if (!item.id) {
    return;
  }
  try {
    const result = await studioApi.datasources.discover(item.id);
    discoveredModels.value = result.models;
    discoverDialogOpen.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.discoverFailed"));
  }
}

async function deleteDatasource(item: DataSourceDefinition) {
  if (!item.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.datasources.deleteConfirmMessage", { name: item.name }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.datasources.delete(item.id);
    if (form.id === item.id) {
      drawerOpen.value = false;
      resetForm();
    }
    ElMessage.success(t("web.datasources.deleteSuccess"));
    await loadPage();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.datasources.deleteFailed"));
    }
  }
}

onMounted(loadPage);
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.capability-note {
  margin-top: 16px;
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.meta-section-stack,
.datasource-meta-section {
  display: grid;
  gap: 10px;
}

.datasource-meta-section__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.datasource-meta-section__header p {
  display: none;
}

.multiple-section-actions {
  display: flex;
  justify-content: flex-end;
}

.section-empty {
  margin-bottom: 0;
}
</style>
