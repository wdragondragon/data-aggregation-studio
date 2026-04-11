<template>
  <el-drawer :model-value="modelValue" :title="drawerTitle" size="54%" @close="emit('update:modelValue', false)">
    <template v-if="activeRunRecord">
      <div class="log-section">
        <div class="log-action-row">
          <FollowToggleButton
            v-if="activeRunRecord.id && activeRunRecord.collectionTaskId"
            target-type="COLLECTION_TASK_RUN"
            :target-id="activeRunRecord.id"
          />
        </div>
        <div class="log-summary-grid compact-panel">
          <div><strong>{{ t("web.runs.collectionTask") }}:</strong> {{ activeRunRecord.collectionTaskName ?? t("common.none") }}</div>
          <div v-if="!isCollectionTaskVariant"><strong>{{ t("web.runs.node") }}:</strong> {{ activeRunRecord.nodeCode ?? t("common.none") }}</div>
          <div class="log-status-row">
            <strong>{{ t("web.runs.status") }}:</strong>
            <span class="log-status-chip">{{ formatStatusLabel(t, activeRunRecord.status) }}</span>
          </div>
          <div><strong>{{ t("web.runs.worker") }}:</strong> {{ activeRunRecord.workerCode ?? t("common.none") }}</div>
          <div v-if="isCollectionTaskVariant"><strong>{{ t("web.runs.startedAt") }}:</strong> {{ activeRunRecord.startedAt ?? t("common.none") }}</div>
        </div>

        <SectionCard :title="t('web.runMetrics.summaryTitle')" :description="t('web.runs.detailSummaryDescription')">
          <div class="metric-grid">
            <MetricCard
              v-for="metric in metricCards"
              :key="metric.key"
              :label="metric.label"
              :value="metric.value"
              :tone="metric.tone"
            />
          </div>
        </SectionCard>

        <SectionCard :title="t('web.runs.messageTitle')" :description="t('web.runs.messageDescription')">
          <el-input
            type="textarea"
            readonly
            resize="none"
            :rows="3"
            class="message-summary-input"
            :model-value="activeRunRecord.message || t('common.none')"
          />
        </SectionCard>

        <SectionCard :title="t('web.runs.logContentTitle')" :description="t('web.runs.logContentDescription')">
          <div class="log-toolbar">
            <div class="log-meta">
              <span>{{ t("web.runs.logSize", { size: formatSize(activeRunLog?.sizeBytes) }) }}</span>
              <span>{{ t("web.runs.logCharset", { charset: activeRunLog?.charset || "UTF-8" }) }}</span>
              <span v-if="activeRunLog?.updatedAt">{{ t("web.runs.logUpdatedAt", { time: activeRunLog.updatedAt }) }}</span>
            </div>
            <div class="studio-toolbar-actions">
              <el-button link type="primary" @click="refreshLog">{{ t("common.refresh") }}</el-button>
              <el-button link type="primary" @click="downloadLog">{{ t("web.runs.downloadLog") }}</el-button>
            </div>
          </div>
          <div v-if="activeRunLog?.historicalFallback" class="log-note">
            {{ t("web.runs.fallbackLogNotice") }}
          </div>
          <div v-else-if="activeRunLog?.truncated" class="log-note">
            {{ t("web.runs.truncatedLogNotice") }}
          </div>
          <el-input
            type="textarea"
            readonly
            resize="none"
            :rows="14"
            class="log-content-input"
            :model-value="displayedLogContent || t('web.runs.noLogContent')"
          />
        </SectionCard>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { EntityId, RunLogView, RunRecord } from "@studio/api-sdk";
import { MetricCard, SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import FollowToggleButton from "@/components/FollowToggleButton.vue";
import { formatStatusLabel } from "@/utils/studio";
import { formatMetricNumber, metricLabel, metricSummaryValue } from "@/utils/runMetrics";

const props = defineProps<{
  modelValue: boolean;
  runRecordId?: EntityId | null;
  variant?: "default" | "collection-task";
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
}>();

const { t } = useI18n();
const activeRunRecord = ref<RunRecord | null>(null);
const activeRunLog = ref<RunLogView | null>(null);
const isCollectionTaskVariant = computed(() => props.variant === "collection-task");
const drawerTitle = computed(() => (isCollectionTaskVariant.value ? t("web.collectionTaskRuns.heading") : t("web.runs.logTitle")));

const displayedLogContent = computed(() => {
  const content = activeRunLog.value?.content;
  if (!content) {
    return "";
  }
  return String(content).replace(/\r\n/g, "\n").replace(/\r/g, "\n");
});

const metricCards = computed(() => {
  const summary = activeRunRecord.value?.metricSummary;
  return [
    { key: "collected", label: metricLabel(t, "collectedRecords"), value: formatMetricNumber(metricSummaryValue(summary, "collectedRecords")), tone: "primary" as const },
    { key: "success", label: metricLabel(t, "successRecords"), value: formatMetricNumber(metricSummaryValue(summary, "successRecords")), tone: "success" as const },
    { key: "failed", label: metricLabel(t, "failedRecords"), value: formatMetricNumber(metricSummaryValue(summary, "failedRecords")), tone: "warning" as const },
    { key: "transformerTotal", label: metricLabel(t, "transformerTotalRecords"), value: formatMetricNumber(metricSummaryValue(summary, "transformerTotalRecords")), tone: "accent" as const },
    { key: "transformerSuccess", label: metricLabel(t, "transformerSuccessRecords"), value: formatMetricNumber(metricSummaryValue(summary, "transformerSuccessRecords")), tone: "success" as const },
    { key: "transformerFailed", label: metricLabel(t, "transformerFailedRecords"), value: formatMetricNumber(metricSummaryValue(summary, "transformerFailedRecords")), tone: "warning" as const },
    { key: "transformerFilter", label: metricLabel(t, "transformerFilterRecords"), value: formatMetricNumber(metricSummaryValue(summary, "transformerFilterRecords")), tone: "primary" as const },
  ];
});

async function load() {
  if (!props.runRecordId || !props.modelValue) {
    activeRunRecord.value = null;
    activeRunLog.value = null;
    return;
  }
  try {
    const [runRecord, runLog] = await Promise.all([
      studioApi.runs.get(props.runRecordId),
      studioApi.runs.getLog(props.runRecordId),
    ]);
    activeRunRecord.value = runRecord;
    activeRunLog.value = runLog;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.runs.loadLogFailed"));
  }
}

async function refreshLog() {
  if (!props.runRecordId) {
    return;
  }
  try {
    activeRunLog.value = await studioApi.runs.getLog(props.runRecordId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.runs.loadLogFailed"));
  }
}

async function downloadLog() {
  if (!props.runRecordId) {
    return;
  }
  try {
    const log = await studioApi.runs.downloadLog(props.runRecordId);
    const blob = new Blob([log.content || ""], { type: log.contentType || "text/plain;charset=UTF-8" });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = log.downloadName || `run-${props.runRecordId}.log`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.runs.loadLogFailed"));
  }
}

function formatSize(value?: number) {
  if (!value || value <= 0) {
    return "0 B";
  }
  if (value < 1024) {
    return `${value} B`;
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`;
  }
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

watch(
  () => [props.modelValue, props.runRecordId],
  () => {
    void load();
  },
  { immediate: true },
);
</script>

<style scoped>
.log-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.log-action-row {
  display: flex;
  justify-content: flex-end;
}

.log-summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 14px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 14px;
}

.compact-panel {
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  background: rgba(16, 78, 139, 0.05);
}

.log-status-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.log-status-chip {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  background: rgba(16, 78, 139, 0.12);
  color: var(--studio-text);
  font-size: 12px;
  font-weight: 600;
}

.message-summary-input,
.log-content-input {
  width: 100%;
}

.message-summary-input :deep(.el-textarea__inner) {
  min-height: 84px !important;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  background: rgba(16, 78, 139, 0.06);
  color: var(--studio-text);
  font-family: Consolas, "JetBrains Mono", "Courier New", monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.log-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 10px;
}

.log-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.log-note {
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(16, 78, 139, 0.08);
  color: var(--studio-text-soft);
}

.log-content-input :deep(.el-textarea__inner) {
  min-height: 260px !important;
  max-height: 52vh;
  overflow: auto;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  background: rgba(16, 78, 139, 0.06);
  color: var(--studio-text);
  font-family: Consolas, "JetBrains Mono", "Courier New", monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre;
}

@media (max-width: 960px) {
  .log-summary-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
