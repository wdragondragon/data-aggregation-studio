<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.catalog.heading") }}</h3>
        <p>{{ t("web.catalog.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-select v-model="categoryFilter" clearable :placeholder="t('web.catalog.filterPlaceholder')" style="min-width: 180px">
          <el-option :label="t('web.catalog.categorySource')" value="SOURCE" />
          <el-option :label="t('web.catalog.categoryReader')" value="READER" />
          <el-option :label="t('web.catalog.categoryWriter')" value="WRITER" />
          <el-option :label="t('web.catalog.categoryTransformer')" value="TRANSFORMER" />
        </el-select>
        <el-button type="primary" plain @click="loadCatalog">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <div class="studio-grid columns-3">
      <MetricCard :label="t('web.catalog.entries')" :value="pluginCount" :hint="t('web.catalog.entriesHint')" />
      <MetricCard :label="t('web.catalog.executableSources')" :value="capabilityMatrix.executableSourceTypes.length" tone="success" :hint="t('web.catalog.executableSourcesHint')" />
      <MetricCard :label="t('web.catalog.transformers')" :value="transformerCount" tone="accent" :hint="t('web.catalog.transformersHint')" />
    </div>

    <SectionCard :title="t('web.catalog.capabilityTitle')" :description="t('web.catalog.capabilityDescription')">
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

    <SectionCard :title="t('web.catalog.tableTitle')" :description="t('web.catalog.tableDescription')">
      <el-table :data="filteredPlugins" border>
        <el-table-column prop="pluginCategory" :label="t('web.catalog.categoryColumn')" width="120" />
        <el-table-column prop="pluginName" :label="t('web.catalog.pluginColumn')" min-width="160" />
        <el-table-column prop="assetType" :label="t('web.catalog.assetTypeColumn')" width="140" />
        <el-table-column :label="t('web.catalog.executableColumn')" width="120">
          <template #default="{ row }">
            <StatusPill :label="row.executable ? t('common.yes') : t('common.no')" :tone="row.executable ? 'success' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column prop="assetPath" :label="t('web.catalog.assetPathColumn')" min-width="280" show-overflow-tooltip />
      </el-table>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { CapabilityMatrix, PluginCatalogEntry } from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";

const { t } = useI18n();
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

const pluginCount = computed(() => new Set(filteredPlugins.value.map((item) => `${item.pluginCategory}::${item.pluginName}`)).size);
const transformerCount = computed(() => new Set(plugins.value.filter((item) => item.pluginCategory === "TRANSFORMER").map((item) => item.pluginName)).size);

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
    ElMessage.error(error instanceof Error ? error.message : t("web.catalog.loadFailed"));
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
