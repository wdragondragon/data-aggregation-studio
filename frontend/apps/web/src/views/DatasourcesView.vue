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
      <el-table :data="datasources" border>
        <el-table-column prop="name" :label="t('web.datasources.nameColumn')" min-width="180" />
        <el-table-column prop="typeCode" :label="t('web.datasources.typeColumn')" width="120" />
        <el-table-column :label="t('web.datasources.enabledColumn')" width="110">
          <template #default="{ row }">
            <StatusPill :label="row.enabled ? t('common.on') : t('common.off')" :tone="row.enabled ? 'success' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.datasources.executableColumn')" width="130">
          <template #default="{ row }">
            <StatusPill :label="row.executable ? t('common.runnable') : t('common.catalogOnly')" :tone="row.executable ? 'success' : 'warning'" />
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" :label="t('web.datasources.updatedColumn')" min-width="170" />
        <el-table-column :label="t('web.datasources.actionsColumn')" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="editDatasource(row)">{{ t("common.edit") }}</el-button>
            <el-button link type="success" @click="testDatasource(row)">{{ t("common.test") }}</el-button>
            <el-button link type="warning" @click="discoverModels(row)">{{ t("common.discover") }}</el-button>
            <el-button link type="danger" @click="deleteDatasource(row)">{{ t("common.delete") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
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
                v-for="item in capabilityMatrix.executableSourceTypes"
                :key="item"
                :label="item"
                tone="success"
              />
            </div>
            <p class="capability-note">
              {{ t("web.datasources.currentTypeLabel") }} <strong>{{ form.typeCode || t("web.datasources.notSelected") }}</strong>
              {{
                form.typeCode && capabilityMatrix.executableSourceTypes.includes(form.typeCode)
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
        <div class="studio-form-grid business-schema-selector">
          <el-form-item :label="t('web.datasources.businessMetaModel')">
            <el-select v-model="selectedBusinessSchemaVersionId" clearable :placeholder="t('web.datasources.businessMetaModelPlaceholder')">
              <el-option
                v-for="schema in businessSchemaOptions"
                :key="schema.id"
                :label="`${parseMetaModelSchema(schema).config.directoryName || parseMetaModelSchema(schema).config.directoryCode || 'business'} / ${schema.schemaName}`"
                :value="schema.currentVersionId ?? schema.id"
              />
            </el-select>
          </el-form-item>
        </div>
        <MetaFormRenderer
          :fields="businessFields"
          :model-value="form.businessMetadata"
          @update:model-value="form.businessMetadata = $event"
        />
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
        <el-table-column prop="modelKind" :label="t('web.datasources.modelKindColumn')" width="120" />
        <el-table-column prop="physicalLocator" :label="t('web.datasources.physicalLocatorColumn')" min-width="220" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
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
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { parseBusinessSchemaVersionId, parseMetaModelSchema, sameEntityId, withBusinessSchemaVersionId } from "@/utils/metaModel";
import { cloneDeep, prettyJson } from "@/utils/studio";

interface DataSourceForm extends DataSourceDefinition {
  name: string;
  typeCode: string;
  technicalMetadata: Record<string, unknown>;
  businessMetadata: Record<string, unknown>;
}

const { t } = useI18n();

const datasources = ref<DataSourceDefinition[]>([]);
const schemas = ref<MetadataSchemaDefinition[]>([]);
const sourcePlugins = ref<PluginCatalogEntry[]>([]);
const capabilityMatrix = reactive<CapabilityMatrix>({
  executableSourceTypes: [],
  plugins: [],
});
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

const fallbackBusinessFields = computed<MetadataFieldDefinition[]>(() => [
  { fieldKey: "owner", fieldName: "Owner", scope: "BUSINESS", componentType: "INPUT", valueType: "STRING" },
  { fieldKey: "domain", fieldName: "Business Domain", scope: "BUSINESS", componentType: "INPUT", valueType: "STRING" },
  { fieldKey: "remark", fieldName: "Remark", scope: "BUSINESS", componentType: "TEXTAREA", valueType: "STRING" },
]);

const datasourceSchemas = computed(() =>
  schemas.value.filter((schema) => {
    if (schema.objectType !== "datasource" || schema.typeCode !== form.typeCode) {
      return false;
    }
    const config = parseMetaModelSchema(schema).config;
    return config.domain === "TECHNICAL" && config.metaModelCode === "source";
  }),
);
const businessSchemaOptions = computed(() =>
  schemas.value.filter((schema) => {
    const config = parseMetaModelSchema(schema).config;
    return config.domain === "BUSINESS" && config.displayMode !== "MULTIPLE";
  }),
);
const selectedBusinessSchemaVersionId = computed({
  get: () => parseBusinessSchemaVersionId(form.businessMetadata),
  set: (value) => {
    form.businessMetadata = withBusinessSchemaVersionId(form.businessMetadata ?? {}, value);
    applyBusinessMetadataDefaults();
  },
});

const matchedSchema = computed(
  () =>
    datasourceSchemas.value.find(
      (schema) => sameEntityId(schema.id, form.schemaVersionId) || sameEntityId(schema.currentVersionId, form.schemaVersionId),
    ) ?? datasourceSchemas.value[0],
);
const matchedBusinessSchema = computed(
  () =>
    businessSchemaOptions.value.find(
      (schema) =>
        sameEntityId(schema.id, selectedBusinessSchemaVersionId.value)
        || sameEntityId(schema.currentVersionId, selectedBusinessSchemaVersionId.value),
    ),
);
const technicalFields = computed(
  () => matchedSchema.value?.fields?.filter((field) => field.scope !== "BUSINESS") ?? fallbackTechnicalFields.value,
);
const businessFields = computed(
  () => matchedBusinessSchema.value?.fields?.filter((field) => field.scope === "BUSINESS")
    ?? matchedSchema.value?.fields?.filter((field) => field.scope === "BUSINESS")
    ?? fallbackBusinessFields.value,
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
  testResult.value = null;
  drawerOpen.value = true;
}

function handleTypeChange() {
  const schema = datasourceSchemas.value[0];
  form.schemaVersionId = schema?.currentVersionId ?? schema?.id;
  form.executable = capabilityMatrix.executableSourceTypes.includes(form.typeCode);
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

function applyBusinessMetadataDefaults() {
  form.businessMetadata = {
    ...buildDefaultMetadata(businessFields.value),
    ...(form.businessMetadata ?? {}),
  };
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
    schemas.value = schemaData;
    capabilityMatrix.executableSourceTypes = capabilityData.executableSourceTypes;
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
  if (!form.id) {
    const saved = await saveDatasource({ closeAfterSave: false });
    if (saved) {
      Object.assign(form, saved);
    }
  }
  if (!form.id) {
    return;
  }
  await testDatasource(cloneDeep(form));
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

.business-schema-selector {
  margin-bottom: 8px;
}
</style>
