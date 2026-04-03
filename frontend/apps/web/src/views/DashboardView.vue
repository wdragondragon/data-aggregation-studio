<template>
  <div class="studio-page">
    <div class="metrics-grid">
      <MetricCard :label="t('web.dashboard.plugins')" :value="pluginCount" :hint="t('web.dashboard.pluginsHint')" :description="t('web.dashboard.pluginsDescription')" />
      <MetricCard :label="t('web.dashboard.datasources')" :value="datasources.length" tone="accent" :hint="t('web.dashboard.datasourcesHint')" :description="t('web.dashboard.datasourcesDescription')" />
      <MetricCard :label="t('web.dashboard.workflows')" :value="workflows.length" tone="success" :hint="t('web.dashboard.workflowsHint')" :description="t('web.dashboard.workflowsDescription')" />
      <MetricCard :label="t('web.dashboard.queuedTasks')" :value="runData.queuedTasks.length" tone="warning" :hint="t('web.dashboard.queuedTasksHint')" :description="t('web.dashboard.queuedTasksDescription')" />
    </div>

    <div class="studio-grid columns-2">
      <SectionCard :title="t('web.dashboard.capabilityTitle')" :description="t('web.dashboard.capabilityDescription')">
        <template #actions>
          <el-button type="primary" plain @click="loadDashboard">{{ t("common.refresh") }}</el-button>
        </template>

        <div class="soft-panel">
          <p><strong>{{ t("web.dashboard.executableSourceTypes") }}</strong></p>
          <div class="tag-row">
            <StatusPill
              v-for="type in capabilityMatrix.executableSourceTypes"
              :key="type"
              :label="type"
              tone="primary"
            />
            <span v-if="capabilityMatrix.executableSourceTypes.length === 0">{{ t("web.dashboard.noExecutableSourceMapping") }}</span>
          </div>
        </div>
      </SectionCard>

      <SectionCard :title="t('web.dashboard.recentWorkflowsTitle')" :description="t('web.dashboard.recentWorkflowsDescription')">
        <el-table :data="workflows.slice(0, 6)" border>
          <el-table-column prop="code" :label="t('web.workflows.code')" min-width="120" />
          <el-table-column prop="name" :label="t('web.dashboard.workflowColumn')" min-width="180" />
          <el-table-column :label="t('web.dashboard.publishedColumn')" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.published ? t('common.published') : t('common.draft')" :tone="row.published ? 'success' : 'warning'" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.dashboard.scheduleColumn')" min-width="160">
            <template #default="{ row }">
              {{ row.schedule?.cronExpression ?? t("common.manualTrigger") }}
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>
    </div>

    <div class="studio-grid columns-2">
      <SectionCard :title="t('web.dashboard.dispatchTitle')" :description="t('web.dashboard.dispatchDescription')">
        <el-table :data="runData.queuedTasks.slice(0, 8)" border>
          <el-table-column prop="nodeCode" :label="t('web.dashboard.nodeColumn')" min-width="140" />
          <el-table-column prop="status" :label="t('web.dashboard.statusColumn')" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.status ?? t('common.unknown')" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column prop="attempts" :label="t('web.dashboard.attemptsColumn')" width="100" />
          <el-table-column prop="createdAt" :label="t('web.dashboard.createdColumn')" min-width="170" />
        </el-table>
      </SectionCard>

      <SectionCard :title="t('web.dashboard.runRecordsTitle')" :description="t('web.dashboard.runRecordsDescription')">
        <el-table :data="runData.runRecords.slice(0, 8)" border>
          <el-table-column prop="nodeCode" :label="t('web.dashboard.nodeColumn')" min-width="140" />
          <el-table-column prop="workerCode" :label="t('web.dashboard.workerColumn')" min-width="140" />
          <el-table-column prop="status" :label="t('web.dashboard.statusColumn')" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.status ?? t('common.unknown')" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column prop="message" :label="t('web.dashboard.messageColumn')" min-width="220" show-overflow-tooltip />
        </el-table>
      </SectionCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { CapabilityMatrix, DataSourceDefinition, PluginCatalogEntry, RunListResponse, WorkflowDefinitionView } from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { toneFromStatus } from "@/utils/studio";

const { t } = useI18n();
const plugins = ref<PluginCatalogEntry[]>([]);
const datasources = ref<DataSourceDefinition[]>([]);
const workflows = ref<WorkflowDefinitionView[]>([]);
const capabilityMatrix = reactive<CapabilityMatrix>({
  executableSourceTypes: [],
  plugins: [],
});
const runData = reactive<RunListResponse>({
  queuedTasks: [],
  runRecords: [],
});
const pluginCount = computed(() => new Set(plugins.value.map((item) => `${item.pluginCategory}::${item.pluginName}`)).size);

async function loadDashboard() {
  try {
    const [pluginsData, datasourceData, workflowData, capabilityData, runsData] = await Promise.all([
      studioApi.catalog.plugins(),
      studioApi.datasources.list(),
      studioApi.workflows.list(),
      studioApi.catalog.capabilities(),
      studioApi.runs.list(),
    ]);
    plugins.value = pluginsData;
    datasources.value = datasourceData;
    workflows.value = workflowData;
    capabilityMatrix.executableSourceTypes = capabilityData.executableSourceTypes;
    capabilityMatrix.plugins = capabilityData.plugins;
    runData.queuedTasks = runsData.queuedTasks;
    runData.runRecords = runsData.runRecords;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.dashboard.loadFailed"));
  }
}

onMounted(loadDashboard);
</script>

<style scoped>
.metrics-grid {
  display: grid;
  gap: 20px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

@media (max-width: 1200px) {
  .metrics-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .metrics-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
