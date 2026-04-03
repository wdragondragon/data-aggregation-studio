<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>Plugin inventory</h3>
        <p>Everything here is scanned from the independent studio runtime, not from the DataAggregation Maven reactor.</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-select v-model="categoryFilter" clearable placeholder="Filter by category" style="min-width: 180px">
          <el-option label="Source" value="SOURCE" />
          <el-option label="Reader" value="READER" />
          <el-option label="Writer" value="WRITER" />
          <el-option label="Transformer" value="TRANSFORMER" />
        </el-select>
        <el-button type="primary" plain @click="loadCatalog">Refresh</el-button>
      </div>
    </div>

    <div class="studio-grid columns-3">
      <MetricCard label="Catalog Entries" :value="filteredPlugins.length" hint="Current Filter" />
      <MetricCard label="Executable Sources" :value="capabilityMatrix.executableSourceTypes.length" tone="success" hint="Can enter workflow designer" />
      <MetricCard label="Transformers" :value="transformerCount" tone="accent" hint="Available for field mapping" />
    </div>

    <SectionCard title="Capability Matrix" description="This list defines which source families can flow from management into execution.">
      <div class="soft-panel">
        <div class="tag-row">
          <StatusPill
            v-for="type in capabilityMatrix.executableSourceTypes"
            :key="type"
            :label="type"
            tone="success"
          />
        </div>
      </div>
    </SectionCard>

    <SectionCard title="Catalog Entries" description="Raw scanned plugin assets, templates and executable flags.">
      <el-table :data="filteredPlugins" border>
        <el-table-column prop="pluginCategory" label="Category" width="120" />
        <el-table-column prop="pluginName" label="Plugin" min-width="160" />
        <el-table-column prop="assetType" label="Asset Type" width="140" />
        <el-table-column label="Executable" width="120">
          <template #default="{ row }">
            <StatusPill :label="row.executable ? 'Yes' : 'No'" :tone="row.executable ? 'success' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column prop="assetPath" label="Asset Path" min-width="280" show-overflow-tooltip />
      </el-table>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import type { CapabilityMatrix, PluginCatalogEntry } from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";

const categoryFilter = ref<string>();
const plugins = ref<PluginCatalogEntry[]>([]);
const capabilityMatrix = reactive<CapabilityMatrix>({
  executableSourceTypes: [],
  plugins: [],
});

const filteredPlugins = computed(() => {
  if (!categoryFilter.value) {
    return plugins.value;
  }
  return plugins.value.filter((item) => item.pluginCategory === categoryFilter.value);
});

const transformerCount = computed(() => plugins.value.filter((item) => item.pluginCategory === "TRANSFORMER").length);

async function loadCatalog() {
  try {
    const [catalogData, capabilityData] = await Promise.all([
      studioApi.catalog.plugins(),
      studioApi.catalog.capabilities(),
    ]);
    plugins.value = catalogData;
    capabilityMatrix.executableSourceTypes = capabilityData.executableSourceTypes;
    capabilityMatrix.plugins = capabilityData.plugins;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to load catalog");
  }
}

onMounted(loadCatalog);
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
</style>
