<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>Model center</h3>
        <p>Models are the abstraction layer over tables, files and queue subjects that workflows bind to.</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-select v-model="selectedDatasourceId" clearable placeholder="Choose datasource" style="min-width: 260px" @change="handleDatasourceChange">
          <el-option
            v-for="item in datasources"
            :key="item.id"
            :label="`${item.name} (${item.typeCode})`"
            :value="item.id"
          />
        </el-select>
        <el-button plain :disabled="!selectedDatasourceId" @click="syncModels">Sync Models</el-button>
      </div>
    </div>

    <div class="studio-grid columns-2">
      <SectionCard title="Models" description="Use sync to reflect the datasource's physical items into the platform abstraction.">
        <el-table :data="models" border @row-click="selectModel">
          <el-table-column prop="name" label="Model Name" min-width="180" />
          <el-table-column prop="modelKind" label="Kind" width="120" />
          <el-table-column prop="physicalLocator" label="Physical Locator" min-width="220" show-overflow-tooltip />
        </el-table>
      </SectionCard>

      <SectionCard title="Preview" description="Preview data comes from the active datasource capability provider.">
        <div v-if="selectedModel" class="soft-panel preview-head">
          <div>
            <strong>{{ selectedModel.name }}</strong>
            <p>{{ selectedModel.physicalLocator }}</p>
          </div>
          <StatusPill :label="selectedModel.modelKind ?? 'UNKNOWN'" tone="primary" />
        </div>
        <el-table :data="previewRows" border>
          <el-table-column
            v-for="column in previewColumns"
            :key="column"
            :prop="column"
            :label="column"
            min-width="140"
            show-overflow-tooltip
          />
        </el-table>
      </SectionCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import type { DataModelDefinition, DataSourceDefinition } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";

const datasources = ref<DataSourceDefinition[]>([]);
const models = ref<DataModelDefinition[]>([]);
const previewRows = ref<Record<string, unknown>[]>([]);
const selectedDatasourceId = ref<number>();
const selectedModel = ref<DataModelDefinition>();

const previewColumns = computed(() => Object.keys(previewRows.value[0] ?? {}));

async function loadDatasources() {
  try {
    datasources.value = await studioApi.datasources.list();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to load datasources");
  }
}

async function handleDatasourceChange() {
  selectedModel.value = undefined;
  previewRows.value = [];
  if (!selectedDatasourceId.value) {
    models.value = [];
    return;
  }
  try {
    models.value = await studioApi.models.listByDatasource(selectedDatasourceId.value);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to load models");
  }
}

async function syncModels() {
  if (!selectedDatasourceId.value) {
    return;
  }
  try {
    models.value = await studioApi.models.sync(selectedDatasourceId.value);
    ElMessage.success("Models synchronized");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to sync models");
  }
}

async function selectModel(model: DataModelDefinition) {
  selectedModel.value = model;
  if (!model.id) {
    return;
  }
  try {
    previewRows.value = await studioApi.models.preview(model.id);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to preview model");
  }
}

onMounted(loadDatasources);
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
</style>
