<template>
  <div class="studio-page">
    <div class="metrics-grid">
      <MetricCard label="Plugins" :value="plugins.length" hint="Catalog Assets" description="Scanned from plugin roots and templates." />
      <MetricCard label="Datasources" :value="datasources.length" tone="accent" hint="Managed Connections" description="Schema-bound datasource instances." />
      <MetricCard label="Workflows" :value="workflows.length" tone="success" hint="Graph Drafts" description="Saved orchestration definitions ready to publish." />
      <MetricCard label="Queued Tasks" :value="runData.queuedTasks.length" tone="warning" hint="Dispatch Queue" description="Tasks waiting for worker leases." />
    </div>

    <div class="studio-grid columns-2">
      <SectionCard title="Capability Snapshot" description="Use this to gauge what can be managed and what can be executed right now.">
        <template #actions>
          <el-button type="primary" plain @click="loadDashboard">Refresh</el-button>
        </template>

        <div class="soft-panel">
          <p><strong>Executable source types</strong></p>
          <div class="tag-row">
            <StatusPill
              v-for="type in capabilityMatrix.executableSourceTypes"
              :key="type"
              :label="type"
              tone="primary"
            />
            <span v-if="capabilityMatrix.executableSourceTypes.length === 0">No executable source mapping found yet.</span>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Recent Workflows" description="Published state and schedule posture for the newest graph definitions.">
        <el-table :data="workflows.slice(0, 6)" border>
          <el-table-column prop="code" label="Code" min-width="120" />
          <el-table-column prop="name" label="Workflow" min-width="180" />
          <el-table-column label="Published" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.published ? 'Published' : 'Draft'" :tone="row.published ? 'success' : 'warning'" />
            </template>
          </el-table-column>
          <el-table-column label="Schedule" min-width="160">
            <template #default="{ row }">
              {{ row.schedule?.cronExpression ?? "Manual trigger" }}
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>
    </div>

    <div class="studio-grid columns-2">
      <SectionCard title="Dispatch Queue" description="The server has already normalized run and queue data for the console.">
        <el-table :data="runData.queuedTasks.slice(0, 8)" border>
          <el-table-column prop="nodeCode" label="Node" min-width="140" />
          <el-table-column prop="status" label="Status" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.status ?? 'UNKNOWN'" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column prop="attempts" label="Attempts" width="100" />
          <el-table-column prop="createdAt" label="Created" min-width="170" />
        </el-table>
      </SectionCard>

      <SectionCard title="Run Records" description="Worker feedback lands here and becomes the basis for retries and recovery.">
        <el-table :data="runData.runRecords.slice(0, 8)" border>
          <el-table-column prop="nodeCode" label="Node" min-width="140" />
          <el-table-column prop="workerCode" label="Worker" min-width="140" />
          <el-table-column prop="status" label="Status" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.status ?? 'UNKNOWN'" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column prop="message" label="Message" min-width="220" show-overflow-tooltip />
        </el-table>
      </SectionCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import type { CapabilityMatrix, DataSourceDefinition, PluginCatalogEntry, RunListResponse, WorkflowDefinitionView } from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { toneFromStatus } from "@/utils/studio";

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
    ElMessage.error(error instanceof Error ? error.message : "Failed to load dashboard");
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
