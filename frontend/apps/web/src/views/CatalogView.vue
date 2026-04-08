<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.catalog.heading") }}</h3>
        <p>{{ t("web.catalog.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" plain @click="loadCatalog">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.catalog.capabilityTitle')" :description="t('web.catalog.capabilityDescription')">
      <div class="soft-panel">
        <div class="capability-header">
          <div class="capability-overview">
            <div class="capability-overview__item">
              <span class="capability-overview__label">{{ t("web.catalog.sourceTypes") }}</span>
              <strong class="capability-overview__value">{{ capabilityRows.length }}</strong>
              <span class="capability-overview__hint">{{ t("web.catalog.sourceTypesHint") }}</span>
            </div>
            <div class="capability-overview__item">
              <span class="capability-overview__label">{{ t("web.catalog.readableTypes") }}</span>
              <strong class="capability-overview__value">{{ readableTypeCount }}</strong>
              <span class="capability-overview__hint">{{ t("web.catalog.readableTypesHint") }}</span>
            </div>
            <div class="capability-overview__item">
              <span class="capability-overview__label">{{ t("web.catalog.writableTypes") }}</span>
              <strong class="capability-overview__value">{{ writableTypeCount }}</strong>
              <span class="capability-overview__hint">{{ t("web.catalog.writableTypesHint") }}</span>
            </div>
            <div class="capability-overview__item">
              <span class="capability-overview__label">{{ t("web.catalog.executableTypes") }}</span>
              <strong class="capability-overview__value">{{ executableTypeBadges.length }}</strong>
              <span class="capability-overview__hint">{{ t("web.catalog.executableTypesHint") }}</span>
            </div>
          </div>

          <div class="capability-summary">
            <span class="capability-summary__label">{{ t("web.catalog.executableTypes") }}</span>
            <div class="capability-summary__badges">
              <StatusPill
                v-for="type in executableTypeBadges"
                :key="type"
                :label="type"
                tone="success"
              />
              <span v-if="executableTypeBadges.length === 0">{{ t("common.none") }}</span>
            </div>
          </div>
        </div>
      </div>

      <el-table :data="capabilityRows" border size="small" style="margin-top: 12px">
        <el-table-column prop="typeCode" :label="t('web.catalog.sourceTypeColumn')" min-width="140" />
        <el-table-column :label="t('web.catalog.readableColumn')" width="110" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="row.readable ? t('common.yes') : t('common.no')" :tone="row.readable ? 'success' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.catalog.writableColumn')" width="110" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="row.writable ? t('common.yes') : t('common.no')" :tone="row.writable ? 'primary' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.catalog.executionColumn')" width="120" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="row.executable ? t('common.runnable') : t('common.catalogOnly')" :tone="row.executable ? 'success' : 'warning'" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.catalog.readerPluginsColumn')" min-width="180">
          <template #default="{ row }">
            <div class="tag-row compact">
              <StatusPill v-for="plugin in row.readerPlugins" :key="`${row.typeCode}-reader-${plugin}`" :label="plugin" tone="primary" />
              <span v-if="!row.readerPlugins?.length">{{ t("common.none") }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.catalog.writerPluginsColumn')" min-width="180">
          <template #default="{ row }">
            <div class="tag-row compact">
              <StatusPill v-for="plugin in row.writerPlugins" :key="`${row.typeCode}-writer-${plugin}`" :label="plugin" tone="warning" />
              <span v-if="!row.writerPlugins?.length">{{ t("common.none") }}</span>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { CapabilityMatrix, SourceCapabilityEntry } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";

const { t } = useI18n();

const capabilityMatrix = reactive<CapabilityMatrix>({
  executableSourceTypes: [],
});

const capabilityRows = computed<SourceCapabilityEntry[]>(() => capabilityMatrix.sourceCapabilities ?? []);
const readableTypeCount = computed(() => capabilityRows.value.filter((item) => item.readable).length);
const writableTypeCount = computed(() => capabilityRows.value.filter((item) => item.writable).length);
const executableTypeBadges = computed(() => capabilityMatrix.executableDatasourceTypes ?? capabilityMatrix.executableSourceTypes ?? []);

async function loadCatalog() {
  try {
    const capabilityData = await studioApi.catalog.capabilities();
    capabilityMatrix.executableSourceTypes = capabilityData.executableSourceTypes;
    capabilityMatrix.executableTargetTypes = capabilityData.executableTargetTypes;
    capabilityMatrix.executableDatasourceTypes = capabilityData.executableDatasourceTypes;
    capabilityMatrix.sourceCapabilities = capabilityData.sourceCapabilities;
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

.capability-header {
  display: grid;
  gap: 12px;
}

.capability-overview {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.capability-overview__item {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid rgba(64, 113, 187, 0.14);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.6);
  min-width: 0;
}

.capability-overview__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--studio-text);
}

.capability-overview__value {
  font-size: 26px;
  line-height: 1;
  color: var(--studio-primary-deep);
}

.capability-overview__hint {
  font-size: 11px;
  color: var(--studio-text-soft);
  overflow-wrap: anywhere;
}

.capability-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.capability-summary__label {
  flex: 0 0 auto;
  color: var(--studio-text-soft);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.capability-summary__badges {
  display: flex;
  flex: 1 1 auto;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.capability-summary__badges :deep(.status-pill) {
  padding: 3px 8px;
  font-size: 11px;
  letter-spacing: 0.02em;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.tag-row.compact {
  gap: 6px;
}

@media (max-width: 1100px) {
  .capability-overview {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .capability-overview {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
