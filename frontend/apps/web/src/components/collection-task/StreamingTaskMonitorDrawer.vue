<template>
  <el-drawer v-model="visibleModel" :title="`${task?.name || ''} · ${t('web.collectionTasks.streamingMonitor')}`" size="min(92vw, 980px)" destroy-on-close>
    <template v-if="task?.id">
      <div class="monitor-toolbar">
        <div class="monitor-state">
          <StatusPill :label="formatStatusLabel(t, task.observedState || task.status)" :tone="toneFromStatus(task.observedState || task.status)" />
          <span class="cell-subtle">{{ task.executionMode }} · {{ task.desiredState || t("common.none") }}</span>
        </div>
        <div class="monitor-actions">
          <el-button plain :loading="loading" @click="refresh">{{ t("common.refresh") }}</el-button>
          <el-button v-if="runtime?.currentAttempt?.runRecordId" plain @click="downloadArchive">{{ t("web.collectionTasks.downloadArchive") }}</el-button>
        </div>
      </div>

      <el-alert v-if="runtime?.deployment?.lastErrorSummary" type="error" :closable="false" show-icon :title="runtime.deployment.lastErrorSummary" />

      <div class="summary-grid">
        <div class="metric-card"><span>{{ t("web.collectionTasks.logicalRun") }}</span><strong>{{ runtime?.currentRun?.id || t("common.none") }}</strong></div>
        <div class="metric-card"><span>{{ t("web.collectionTasks.attempt") }}</span><strong>#{{ runtime?.currentAttempt?.attemptNo || t("common.none") }}</strong></div>
        <div class="metric-card"><span>{{ t("web.collectionTasks.groupId") }}</span><strong class="studio-mono">{{ runtime?.currentRun?.groupId || t("common.none") }}</strong></div>
        <div class="metric-card"><span>{{ t("web.collectionTasks.checkpoint") }}</span><strong class="studio-mono">{{ checkpointLabel }}</strong></div>
      </div>

      <el-tabs v-model="activeTab">
        <el-tab-pane :label="t('web.collectionTasks.metricsTab')" name="metrics">
          <div class="metrics-toolbar">
            <el-checkbox v-model="onlyWithRecords">{{ t("web.collectionTasks.onlyWithRecords") }}</el-checkbox>
          </div>
          <StudioTableShell min-width="960px">
            <el-table :data="displayMetrics" border size="small" table-layout="fixed">
              <el-table-column :label="t('web.collectionTasks.bucket')" min-width="240">
                <template #default="{ row }">
                  <span>{{ row.bucketLabel }}</span>
                  <span v-if="row.idleBucketCount > 1" class="cell-subtle">
                    · {{ row.idleBucketCount }} {{ t("web.collectionTasks.idleMinutes") }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column :label="t('web.collectionTasks.recordsRead')" width="120">
                <template #default="{ row }">{{ row.metric.recordsRead ?? 0 }}</template>
              </el-table-column>
              <el-table-column :label="t('web.collectionTasks.writeSuccess')" width="120">
                <template #default="{ row }">{{ row.metric.writeSucceedRecords ?? 0 }}</template>
              </el-table-column>
              <el-table-column :label="t('web.collectionTasks.writeFailed')" width="120">
                <template #default="{ row }">{{ row.metric.writeFailedRecords ?? 0 }}</template>
              </el-table-column>
              <el-table-column :label="t('web.collectionTasks.lag')" width="100">
                <template #default="{ row }">{{ row.metric.currentLag ?? 0 }}</template>
              </el-table-column>
              <el-table-column :label="t('web.collectionTasks.batches')" width="100">
                <template #default="{ row }">{{ row.metric.batchCount ?? 0 }}</template>
              </el-table-column>
            </el-table>
            <div class="table-pagination">
              <el-pagination
                v-model:current-page="metricPagination.pageNo"
                v-model:page-size="metricPagination.pageSize"
                :total="metrics.total"
                :page-sizes="[10, 20, 50, 100]"
                layout="total, sizes, prev, pager, next"
                @current-change="handleMetricPageChange"
                @size-change="handleMetricPageSizeChange"
              />
            </div>
          </StudioTableShell>
        </el-tab-pane>
        <el-tab-pane :label="t('web.collectionTasks.attemptsTab')" name="attempts">
          <StudioTableShell min-width="900px">
            <el-table :data="runtime?.recentAttempts || []" border size="small" table-layout="fixed">
              <el-table-column prop="attemptNo" :label="t('web.collectionTasks.attempt')" width="90" />
              <el-table-column prop="status" :label="t('web.collectionTasks.status')" width="130" />
              <el-table-column prop="startedAt" :label="t('web.collectionTasks.startedAt')" min-width="180" />
              <el-table-column prop="endedAt" :label="t('web.collectionTasks.endedAt')" min-width="180" />
              <el-table-column prop="errorSummary" :label="t('web.collectionTasks.error')" min-width="260" show-overflow-tooltip />
            </el-table>
          </StudioTableShell>
        </el-tab-pane>
        <el-tab-pane :label="t('web.collectionTasks.eventsTab')" name="events">
          <StudioTableShell min-width="980px">
            <el-table :data="events.items" border size="small" table-layout="fixed">
              <el-table-column prop="occurredAt" :label="t('web.collectionTasks.occurredAt')" min-width="180" />
              <el-table-column prop="eventType" :label="t('web.collectionTasks.eventType')" width="140" />
              <el-table-column prop="fromState" :label="t('web.collectionTasks.fromState')" width="130" />
              <el-table-column prop="toState" :label="t('web.collectionTasks.toState')" width="130" />
              <el-table-column prop="message" :label="t('web.collectionTasks.message')" min-width="320" show-overflow-tooltip />
            </el-table>
          </StudioTableShell>
        </el-tab-pane>
        <el-tab-pane :label="t('web.collectionTasks.logChunksTab')" name="logs">
          <StudioTableShell min-width="980px">
            <el-table :data="logChunks.items" border size="small" table-layout="fixed">
              <el-table-column prop="sequenceNo" :label="t('web.collectionTasks.sequence')" width="90" />
              <el-table-column prop="status" :label="t('web.collectionTasks.status')" width="120" />
              <el-table-column prop="sizeBytes" :label="t('web.collectionTasks.sizeBytes')" width="120" />
              <el-table-column prop="chunkStartedAt" :label="t('web.collectionTasks.startedAt')" min-width="180" />
              <el-table-column prop="uploadedAt" :label="t('web.collectionTasks.uploadedAt')" min-width="180" />
              <el-table-column prop="objectKey" :label="t('web.collectionTasks.objectKey')" min-width="280" show-overflow-tooltip />
              <el-table-column :label="t('web.collectionTasks.actions')" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button type="primary" link :loading="previewLoading && previewChunk?.id === row.id" :disabled="!row.id" @click="previewChunkLog(row)">
                    {{ t("web.collectionTasks.previewChunk") }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </StudioTableShell>
        </el-tab-pane>
      </el-tabs>
    </template>
  </el-drawer>
  <el-dialog v-model="previewVisible" :title="previewTitle" width="min(92vw, 900px)" destroy-on-close>
    <el-alert v-if="previewLog?.truncated" type="warning" :closable="false" show-icon :title="t('web.collectionTasks.previewTruncated')" />
    <div class="preview-meta">
      <span>{{ previewLog?.sizeBytes ?? 0 }} {{ t("web.collectionTasks.sizeBytes") }}</span>
      <span v-if="previewLog?.paged">{{ previewLog?.pageNo ?? 1 }} / {{ previewLog?.totalPages ?? 1 }}</span>
    </div>
    <pre class="log-preview-content">{{ previewLog?.content || t("web.collectionTasks.previewChunkEmpty") }}</pre>
    <template #footer>
      <el-pagination
        v-if="previewLog?.paged"
        v-model:current-page="previewPageNo"
        :page-size="previewPageSizeBytes"
        :total="previewLog?.sizeBytes ?? 0"
        layout="prev, pager, next"
        @current-change="handlePreviewPageChange"
      />
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { CollectionTaskListView, EntityId, CollectionTaskStreamingRuntimeView, StreamingMetricBucketView, StreamingTaskEventView, RunLogChunkView, RunLogView, PageResult } from "@studio/api-sdk";
import { StudioTableShell, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { formatStatusLabel, toneFromStatus } from "@/utils/studio";
import { compactIdleMetrics, hasMetricRecords } from "./streamingMetricsDisplay";

const props = defineProps<{ modelValue: boolean; task: CollectionTaskListView | null }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();
const { t } = useI18n();
const activeTab = ref("metrics");
const loading = ref(false);
const runtime = ref<CollectionTaskStreamingRuntimeView | null>(null);
const metrics = ref<PageResult<StreamingMetricBucketView>>({ pageNo: 1, pageSize: 20, total: 0, items: [] });
const events = ref<PageResult<StreamingTaskEventView>>({ pageNo: 1, pageSize: 20, total: 0, items: [] });
const logChunks = ref<PageResult<RunLogChunkView>>({ pageNo: 1, pageSize: 20, total: 0, items: [] });
const metricPagination = reactive({ pageNo: 1, pageSize: 20 });
const onlyWithRecords = ref(false);
const previewVisible = ref(false);
const previewLoading = ref(false);
const previewChunk = ref<RunLogChunkView | null>(null);
const previewLog = ref<RunLogView | null>(null);
const previewPageNo = ref(1);
const previewPageSizeBytes = 64 * 1024;
let pollTimer: number | undefined;

const visibleModel = computed({ get: () => props.modelValue, set: (value: boolean) => emit("update:modelValue", value) });
const checkpointLabel = computed(() => {
  const checkpoint = runtime.value?.deployment?.lastCheckpoint;
  if (!checkpoint || !Object.keys(checkpoint).length) return t("common.none");
  return JSON.stringify(checkpoint);
});

const displayMetrics = computed(() => compactIdleMetrics(
  onlyWithRecords.value ? metrics.value.items.filter(hasMetricRecords) : metrics.value.items,
));

const previewTitle = computed(() => {
  const sequence = previewChunk.value?.sequenceNo;
  return sequence == null
    ? t("web.collectionTasks.previewChunk")
    : `${t("web.collectionTasks.previewChunk")} · ${t("web.collectionTasks.sequence")} ${sequence}`;
});

async function refresh() {
  const id = props.task?.id;
  if (!id) return;
  loading.value = true;
  try {
    const [nextRuntime, nextMetrics, nextEvents, nextChunks] = await Promise.all([
      studioApi.collectionTasks.streamingRuntime(id),
      studioApi.collectionTasks.streamingMetrics(id, {
        pageNo: metricPagination.pageNo,
        pageSize: metricPagination.pageSize,
        onlyWithRecords: onlyWithRecords.value,
      }),
      studioApi.collectionTasks.streamingEvents(id, { pageNo: 1, pageSize: 50 }),
      studioApi.collectionTasks.streamingLogChunks(id, { pageNo: 1, pageSize: 50 }),
    ]);
    runtime.value = nextRuntime;
    metrics.value = nextMetrics;
    events.value = nextEvents;
    logChunks.value = nextChunks;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.monitorLoadFailed"));
  } finally {
    loading.value = false;
  }
}

async function previewChunkLog(chunk: RunLogChunkView) {
  if (!chunk.id || !props.task?.id) return;
  previewChunk.value = chunk;
  previewPageNo.value = 1;
  previewLog.value = null;
  previewVisible.value = true;
  await loadChunkPreview();
}

async function loadChunkPreview() {
  const taskId = props.task?.id;
  const chunkId = previewChunk.value?.id;
  if (!taskId || !chunkId) return;
  previewLoading.value = true;
  try {
    previewLog.value = await studioApi.collectionTasks.streamingLogChunkPreview(taskId, chunkId, {
      pageNo: previewPageNo.value,
      pageSizeBytes: previewPageSizeBytes,
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.previewChunkFailed"));
  } finally {
    previewLoading.value = false;
  }
}

function handlePreviewPageChange(pageNo: number) {
  previewPageNo.value = pageNo;
  void loadChunkPreview();
}

function handleMetricPageChange(pageNo: number) {
  metricPagination.pageNo = pageNo;
  void refresh();
}

function handleMetricPageSizeChange(pageSize: number) {
  metricPagination.pageSize = pageSize;
  metricPagination.pageNo = 1;
  void refresh();
}

async function downloadArchive() {
  const runRecordId = runtime.value?.currentAttempt?.runRecordId;
  if (!runRecordId) return;
  try {
    const blob = await studioApi.runs.downloadLogArchive(runRecordId);
    const url = window.URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `run-${runRecordId}.zip`;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    window.URL.revokeObjectURL(url);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.downloadArchiveFailed"));
  }
}

function stopPolling() {
  if (pollTimer != null) window.clearInterval(pollTimer);
  pollTimer = undefined;
}

watch(() => [props.modelValue, props.task?.id] as const, ([visible]) => {
  stopPolling();
  if (!visible) return;
  metricPagination.pageNo = 1;
  void refresh();
  pollTimer = window.setInterval(() => void refresh(), 10000);
}, { immediate: true });

watch(onlyWithRecords, () => {
  if (!props.modelValue) return;
  metricPagination.pageNo = 1;
  void refresh();
});

onBeforeUnmount(stopPolling);
</script>

<style scoped>
.monitor-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.monitor-state, .monitor-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin: 14px 0; }
.metric-card { min-width: 0; padding: 12px; border: 1px solid var(--studio-border); border-radius: 8px; background: var(--studio-surface, #fff); }
.metric-card span, .metric-card strong { display: block; overflow-wrap: anywhere; }
.metric-card span { color: var(--studio-text-soft); font-size: 12px; }
.metric-card strong { margin-top: 6px; font-size: 15px; }
.cell-subtle { color: var(--studio-text-soft); font-size: 12px; }
.metrics-toolbar { display: flex; justify-content: flex-end; margin-bottom: 10px; }
.preview-meta { display: flex; justify-content: space-between; color: var(--studio-text-soft); font-size: 12px; margin-bottom: 8px; }
.log-preview-content { max-height: min(60vh, 560px); overflow: auto; margin: 0; padding: 12px; border: 1px solid var(--studio-border); border-radius: 4px; background: var(--studio-surface-soft, #f7f8fa); white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.5 ui-monospace, SFMono-Regular, Consolas, monospace; }
@media (max-width: 760px) { .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .monitor-toolbar { align-items: flex-start; flex-direction: column; } }
</style>
