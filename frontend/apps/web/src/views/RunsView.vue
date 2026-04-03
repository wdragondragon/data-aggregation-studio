<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.runs.heading") }}</h3>
        <p>{{ t("web.runs.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" plain @click="loadRuns">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <div class="studio-grid columns-3">
      <MetricCard :label="t('web.runs.queued')" :value="runData.queuedTasks.length" tone="warning" :hint="t('web.runs.queuedHint')" />
      <MetricCard :label="t('web.runs.runRecords')" :value="runData.runRecords.length" tone="accent" :hint="t('web.runs.runRecordsHint')" />
      <MetricCard :label="t('web.runs.failed')" :value="failedCount" tone="primary" :hint="t('web.runs.failedHint')" />
    </div>

    <div class="studio-grid columns-2">
      <SectionCard :title="t('web.runs.queuedTitle')" :description="t('web.runs.queuedDescription')">
        <el-table :data="runData.queuedTasks" border>
          <el-table-column prop="workflowDefinitionId" :label="t('web.runs.workflow')" width="100" />
          <el-table-column prop="nodeCode" :label="t('web.runs.node')" min-width="150" />
          <el-table-column prop="status" :label="t('web.runs.status')" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.status ?? t('common.unknown')" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column prop="attempts" :label="t('web.runs.attempts')" width="100" />
          <el-table-column prop="maxRetries" :label="t('web.runs.maxRetries')" width="110" />
        </el-table>
      </SectionCard>

      <SectionCard :title="t('web.runs.runRecordsTitle')" :description="t('web.runs.runRecordsDescription')">
        <el-table :data="runData.runRecords" border>
          <el-table-column prop="workflowDefinitionId" :label="t('web.runs.workflow')" width="100" />
          <el-table-column prop="nodeCode" :label="t('web.runs.node')" min-width="150" />
          <el-table-column prop="workerCode" :label="t('web.runs.worker')" min-width="150" />
          <el-table-column prop="status" :label="t('web.runs.status')" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.status ?? t('common.unknown')" :tone="toneFromStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column prop="message" :label="t('web.runs.message')" min-width="240" show-overflow-tooltip />
        </el-table>
      </SectionCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { RunListResponse } from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { toneFromStatus } from "@/utils/studio";

const { t } = useI18n();
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
    ElMessage.error(error instanceof Error ? error.message : t("web.runs.loadFailed"));
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
