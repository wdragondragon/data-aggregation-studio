<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>Datasource center</h3>
        <p>Schema-driven forms let the connection surface change with plugin type instead of hard-coded screens.</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" @click="openCreate">New Datasource</el-button>
        <el-button plain @click="loadPage">Refresh</el-button>
      </div>
    </div>

    <SectionCard title="Managed Datasources" description="These instances remain independent from DataAggregation Maven modules and only depend on published runtime artifacts.">
      <el-table :data="datasources" border>
        <el-table-column prop="name" label="Name" min-width="180" />
        <el-table-column prop="typeCode" label="Type" width="120" />
        <el-table-column label="Enabled" width="110">
          <template #default="{ row }">
            <StatusPill :label="row.enabled ? 'On' : 'Off'" :tone="row.enabled ? 'success' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column label="Executable" width="130">
          <template #default="{ row }">
            <StatusPill :label="row.executable ? 'Runnable' : 'Catalog Only'" :tone="row.executable ? 'success' : 'warning'" />
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="Updated" min-width="170" />
        <el-table-column label="Actions" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="editDatasource(row)">Edit</el-button>
            <el-button link type="success" @click="testDatasource(row)">Test</el-button>
            <el-button link type="warning" @click="discoverModels(row)">Discover</el-button>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>

    <el-drawer v-model="drawerOpen" size="70%" :title="form.id ? 'Edit Datasource' : 'Create Datasource'">
      <div class="studio-grid columns-2">
        <SectionCard title="Connection Identity" description="Choose the plugin type first, then the metadata form adapts to it.">
          <div class="studio-form-grid">
            <el-form-item label="Datasource Name">
              <el-input v-model="form.name" placeholder="Orders MySQL" />
            </el-form-item>
            <el-form-item label="Plugin Type">
              <el-select v-model="form.typeCode" placeholder="Select type" @change="handleTypeChange">
                <el-option
                  v-for="pluginName in sourceTypeOptions"
                  :key="pluginName"
                  :label="pluginName"
                  :value="pluginName"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="Schema Binding">
              <el-select v-model="form.schemaVersionId" clearable placeholder="Optional schema binding">
                <el-option
                  v-for="schema in datasourceSchemas"
                  :key="schema.id"
                  :label="`${schema.schemaName} v${schema.versionNumber ?? 1}`"
                  :value="schema.currentVersionId ?? schema.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="Execution Surface">
              <el-switch v-model="form.executable" inline-prompt active-text="Run" inactive-text="Catalog" />
            </el-form-item>
            <el-form-item label="Enabled">
              <el-switch v-model="form.enabled" inline-prompt active-text="On" inactive-text="Off" />
            </el-form-item>
          </div>
        </SectionCard>

        <SectionCard title="Execution Support" description="The executable flag follows the capability matrix but can still be adjusted for governance.">
          <div class="soft-panel">
            <p><strong>Executable source families</strong></p>
            <div class="tag-row">
              <StatusPill
                v-for="item in capabilityMatrix.executableSourceTypes"
                :key="item"
                :label="item"
                tone="success"
              />
            </div>
            <p class="capability-note">
              Current type <strong>{{ form.typeCode || "Not selected" }}</strong>
              {{
                form.typeCode && capabilityMatrix.executableSourceTypes.includes(form.typeCode)
                  ? "can enter workflow execution."
                  : "is currently managed as catalog metadata only."
              }}
            </p>
          </div>
        </SectionCard>
      </div>

      <SectionCard title="Technical Metadata" description="Connection and execution essentials are stored separately from business annotations.">
        <MetaFormRenderer
          :fields="technicalFields"
          :model-value="form.technicalMetadata"
          @update:model-value="form.technicalMetadata = $event"
        />
      </SectionCard>

      <SectionCard title="Business Metadata" description="Use this area for ownership, domain tags and governance notes.">
        <MetaFormRenderer
          :fields="businessFields"
          :model-value="form.businessMetadata"
          @update:model-value="form.businessMetadata = $event"
        />
      </SectionCard>

      <div class="drawer-actions">
        <el-button @click="drawerOpen = false">Cancel</el-button>
        <el-button @click="testCurrent">Test Connection</el-button>
        <el-button type="primary" :loading="saving" @click="saveDatasource">Save Datasource</el-button>
      </div>

      <SectionCard v-if="testResult" title="Last Test Result" description="The platform masks secrets on the way back to the browser.">
        <pre class="json-block studio-mono">{{ prettyJson(testResult) }}</pre>
      </SectionCard>
    </el-drawer>

    <el-dialog v-model="discoverDialogOpen" title="Discovered Models" width="62%">
      <el-table :data="discoveredModels" border>
        <el-table-column prop="name" label="Model Name" min-width="180" />
        <el-table-column prop="modelKind" label="Kind" width="120" />
        <el-table-column prop="physicalLocator" label="Physical Locator" min-width="220" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
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
import { cloneDeep, prettyJson } from "@/utils/studio";

interface DataSourceForm extends DataSourceDefinition {
  name: string;
  typeCode: string;
  technicalMetadata: Record<string, unknown>;
  businessMetadata: Record<string, unknown>;
}

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
      { fieldKey: "port", fieldName: "Port", scope: "TECHNICAL", componentType: "NUMBER", valueType: "INTEGER", required: true },
      { fieldKey: "database", fieldName: "Database", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "username", fieldName: "Username", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "password", fieldName: "Password", scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", required: true },
      ...businessDefault,
    ];
  }
  if (["kafka", "rocketmq", "rabbitmq"].includes(form.typeCode)) {
    return [
      { fieldKey: "brokers", fieldName: "Brokers", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING", required: true },
      { fieldKey: "username", fieldName: "Username", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
      { fieldKey: "password", fieldName: "Password", scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", sensitive: true },
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
    { fieldKey: "username", fieldName: "Username", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
    { fieldKey: "password", fieldName: "Password", scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", sensitive: true },
  ];
});

const fallbackBusinessFields = computed<MetadataFieldDefinition[]>(() => [
  { fieldKey: "owner", fieldName: "Owner", scope: "BUSINESS", componentType: "INPUT", valueType: "STRING" },
  { fieldKey: "domain", fieldName: "Business Domain", scope: "BUSINESS", componentType: "INPUT", valueType: "STRING" },
  { fieldKey: "remark", fieldName: "Remark", scope: "BUSINESS", componentType: "TEXTAREA", valueType: "STRING" },
]);

const datasourceSchemas = computed(() =>
  schemas.value.filter((schema) => schema.objectType === "datasource" && schema.typeCode === form.typeCode),
);

const matchedSchema = computed(() => datasourceSchemas.value.find((schema) => schema.id === form.schemaVersionId) ?? datasourceSchemas.value[0]);
const technicalFields = computed(
  () => matchedSchema.value?.fields?.filter((field) => field.scope !== "BUSINESS") ?? fallbackTechnicalFields.value,
);
const businessFields = computed(
  () => matchedSchema.value?.fields?.filter((field) => field.scope === "BUSINESS") ?? fallbackBusinessFields.value,
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
  form.schemaVersionId = datasourceSchemas.value[0]?.currentVersionId ?? datasourceSchemas.value[0]?.id;
  form.executable = capabilityMatrix.executableSourceTypes.includes(form.typeCode);
  form.technicalMetadata = {};
  form.businessMetadata = {};
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
    ElMessage.error(error instanceof Error ? error.message : "Failed to load datasource page");
  }
}

async function saveDatasource(options: { closeAfterSave?: boolean } = {}) {
  saving.value = true;
  try {
    const saved = await studioApi.datasources.save(cloneDeep(form));
    Object.assign(form, saved);
    ElMessage.success("Datasource saved");
    if (options.closeAfterSave !== false) {
      drawerOpen.value = false;
    }
    await loadPage();
    return saved;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to save datasource");
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
    ElMessage.success("Connection test finished");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Datasource test failed");
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
    ElMessage.error(error instanceof Error ? error.message : "Model discovery failed");
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
</style>
