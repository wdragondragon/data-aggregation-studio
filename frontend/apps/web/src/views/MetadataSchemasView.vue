<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>Metadata schema center</h3>
        <p>Draft fields here and they become the source of truth for dynamic datasource and model forms.</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" @click="openCreate">New Schema</el-button>
        <el-button plain @click="loadSchemas">Refresh</el-button>
      </div>
    </div>

    <SectionCard title="Published and Draft Schemas" description="Each save creates a new draft version, and publish flips the schema into the active version for new instances.">
      <el-table :data="schemas" border>
        <el-table-column prop="schemaCode" label="Schema Code" min-width="180" />
        <el-table-column prop="schemaName" label="Schema Name" min-width="180" />
        <el-table-column prop="objectType" label="Object Type" width="130" />
        <el-table-column prop="typeCode" label="Type Code" width="130" />
        <el-table-column prop="versionNumber" label="Version" width="90" />
        <el-table-column label="Status" width="120">
          <template #default="{ row }">
            <StatusPill :label="row.status ?? 'UNKNOWN'" :tone="toneFromStatus(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="Fields" width="90">
          <template #default="{ row }">
            {{ row.fields?.length ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column label="Actions" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="editSchema(row)">Edit</el-button>
            <el-button link type="success" @click="publishSchema(row)" :disabled="!row.id">Publish</el-button>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>

    <el-drawer v-model="drawerOpen" size="72%" :title="form.schemaId ? 'Edit Schema Draft' : 'Create Schema Draft'">
      <div class="studio-grid columns-2">
        <SectionCard title="Schema Basics" description="Code, type and version metadata for this draft.">
          <div class="studio-form-grid">
            <el-form-item label="Schema Code">
              <el-input v-model="form.schemaCode" placeholder="datasource:mysql8" />
            </el-form-item>
            <el-form-item label="Schema Name">
              <el-input v-model="form.schemaName" placeholder="MySQL Datasource" />
            </el-form-item>
            <el-form-item label="Object Type">
              <el-select v-model="form.objectType" placeholder="Select object type">
                <el-option label="Datasource" value="datasource" />
                <el-option label="Model" value="model" />
                <el-option label="Reader" value="reader" />
                <el-option label="Transformer" value="transformer" />
              </el-select>
            </el-form-item>
            <el-form-item label="Type Code">
              <el-input v-model="form.typeCode" placeholder="mysql8" />
            </el-form-item>
            <el-form-item label="Description" style="grid-column: 1 / -1">
              <el-input v-model="form.description" type="textarea" :rows="4" placeholder="Describe this schema" />
            </el-form-item>
          </div>
        </SectionCard>

        <SectionCard title="Preview Form" description="What the dynamic schema-driven form renderer will produce for the current draft.">
          <MetaFormRenderer :fields="form.fields" :model-value="previewModel" @update:model-value="previewModel = $event" />
        </SectionCard>
      </div>

      <SectionCard title="Field Definitions" description="Add and refine fields for technical and business metadata.">
        <template #actions>
          <el-button type="primary" plain @click="appendField">Add Field</el-button>
        </template>

        <el-table :data="form.fields" border>
          <el-table-column label="Field Key" min-width="150">
            <template #default="{ row }">
              <el-input v-model="row.fieldKey" placeholder="host" />
            </template>
          </el-table-column>
          <el-table-column label="Field Name" min-width="160">
            <template #default="{ row }">
              <el-input v-model="row.fieldName" placeholder="Host" />
            </template>
          </el-table-column>
          <el-table-column label="Scope" width="140">
            <template #default="{ row }">
              <el-select v-model="row.scope">
                <el-option label="Technical" value="TECHNICAL" />
                <el-option label="Business" value="BUSINESS" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="Component" width="150">
            <template #default="{ row }">
              <el-select v-model="row.componentType">
                <el-option v-for="item in componentTypes" :key="item" :label="item" :value="item" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="Value Type" width="150">
            <template #default="{ row }">
              <el-select v-model="row.valueType">
                <el-option v-for="item in valueTypes" :key="item" :label="item" :value="item" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="Required" width="100">
            <template #default="{ row }">
              <el-switch v-model="row.required" />
            </template>
          </el-table-column>
          <el-table-column label="Sensitive" width="100">
            <template #default="{ row }">
              <el-switch v-model="row.sensitive" />
            </template>
          </el-table-column>
          <el-table-column label="Options" min-width="180">
            <template #default="{ row }">
              <el-select
                v-model="row.options"
                multiple
                filterable
                allow-create
                default-first-option
                placeholder="Optional values"
              />
            </template>
          </el-table-column>
          <el-table-column label="Actions" width="100">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeField($index)">Remove</el-button>
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>

      <div class="drawer-actions">
        <el-button @click="drawerOpen = false">Cancel</el-button>
        <el-button type="primary" :loading="saving" @click="saveDraft">Save Draft</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type { MetadataFieldDefinition, MetadataSchemaDefinition } from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { cloneDeep, toneFromStatus } from "@/utils/studio";

interface SchemaDraftForm {
  schemaId?: number;
  schemaCode: string;
  schemaName: string;
  objectType: string;
  typeCode: string;
  description?: string;
  fields: MetadataFieldDefinition[];
}

const componentTypes = ["INPUT", "PASSWORD", "NUMBER", "TEXTAREA", "SELECT", "SWITCH", "JSON_EDITOR", "SQL_EDITOR", "CODE_EDITOR", "CRON"];
const valueTypes = ["STRING", "BOOLEAN", "INTEGER", "LONG", "DECIMAL", "ARRAY", "OBJECT", "JSON"];

const schemas = ref<MetadataSchemaDefinition[]>([]);
const drawerOpen = ref(false);
const saving = ref(false);
const previewModel = ref<Record<string, unknown>>({});
const form = reactive<SchemaDraftForm>({
  schemaCode: "",
  schemaName: "",
  objectType: "datasource",
  typeCode: "",
  description: "",
  fields: [],
});

function resetForm() {
  form.schemaId = undefined;
  form.schemaCode = "";
  form.schemaName = "";
  form.objectType = "datasource";
  form.typeCode = "";
  form.description = "";
  form.fields = [];
}

function openCreate() {
  resetForm();
  appendField();
  drawerOpen.value = true;
}

function editSchema(schema: MetadataSchemaDefinition) {
  const copied = cloneDeep(schema);
  form.schemaId = copied.id;
  form.schemaCode = copied.schemaCode;
  form.schemaName = copied.schemaName;
  form.objectType = copied.objectType;
  form.typeCode = copied.typeCode;
  form.description = copied.description;
  form.fields = copied.fields ?? [];
  previewModel.value = {};
  drawerOpen.value = true;
}

async function loadSchemas() {
  try {
    schemas.value = await studioApi.metaSchemas.list();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to load metadata schemas");
  }
}

function appendField() {
  form.fields.push({
    fieldKey: "",
    fieldName: "",
    scope: "TECHNICAL",
    componentType: "INPUT",
    valueType: "STRING",
    required: false,
    sensitive: false,
    options: [],
  });
}

function removeField(index: number) {
  form.fields.splice(index, 1);
}

async function saveDraft() {
  saving.value = true;
  try {
    await studioApi.metaSchemas.saveDraft(cloneDeep(form));
    ElMessage.success("Metadata schema draft saved");
    drawerOpen.value = false;
    await loadSchemas();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to save metadata schema");
  } finally {
    saving.value = false;
  }
}

async function publishSchema(schema: MetadataSchemaDefinition) {
  if (!schema.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(`Publish schema ${schema.schemaCode}?`, "Confirm");
    await studioApi.metaSchemas.publish(schema.id);
    ElMessage.success("Schema published");
    await loadSchemas();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "Failed to publish schema");
    }
  }
}

onMounted(loadSchemas);
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
}
</style>
