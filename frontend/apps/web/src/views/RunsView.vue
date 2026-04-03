<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>Runtime center</h3>
        <p>Queued tasks and run records are split so the dispatching lifecycle stays visible before and after execution.</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" plain @click="loadRuns">Refresh</el-button>
      </div>
    </div>

    <div class="studio-grid columns-3">
      <MetricCard label="Queued" :value="runData.queuedTasks.length" tone="warning" hint="Waiting for lease" />
      <MetricCard label="Run Records" :value="runData.runRecords.length" tone="accent" hint="Completed or active runs" />
      <MetricCard label="Failed" :value="failedCount" tone="primary" hint="Needs retry attention" />
    </div>

    <div class="studio-grid columns-2">
      <SectionCard title="Queued Tasks" description="These tasks are ready for workers to lease and execute.">
        <el-table :data="runData.queuedTasks" border>
          <el-table-column prop="workflowDefinitionId" label="Workflow" width="100" />
          <el-table-column prop="nodeCode" label="Node" min-width="150" />
          <el-table-column prop="status" label="Status" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.status ?? 'UNKNOWN'" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column prop="attempts" label="Attempts" width="100" />
          <el-table-column prop="maxRetries" label="Max Retries" width="110" />
        </el-table>
      </SectionCard>

      <SectionCard title="Run Records" description="This is the worker-facing history surface for audit, retries and troubleshooting.">
        <el-table :data="runData.runRecords" border>
          <el-table-column prop="workflowDefinitionId" label="Workflow" width="100" />
          <el-table-column prop="nodeCode" label="Node" min-width="150" />
          <el-table-column prop="workerCode" label="Worker" min-width="150" />
          <el-table-column prop="status" label="Status" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.status ?? 'UNKNOWN'" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column prop="message" label="Message" min-width="240" show-overflow-tooltip />
        </el-table>
      </SectionCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive } from "vue";
import { ElMessage } from "element-plus";
import type { RunListResponse } from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { toneFromStatus } from "@/utils/studio";

const runData = reactive<RunListResponse>({
  queuedTasks: [],
  runRecords: [],
});

const failedCount = computed(() =>
  runData.runRecords.filter((item) => String(item.status ?? "").toUpperCase().includes("FAIL")).length,
);

async function loadRuns() {
  try {
    const result = await studioApi.runs.list();
    runData.queuedTasks = result.queuedTasks;
    runData.runRecords = result.runRecords;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to load runs");
  }
}

onMounted(loadRuns);
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}
</style>
