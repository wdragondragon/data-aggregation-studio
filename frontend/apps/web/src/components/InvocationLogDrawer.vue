<template>
  <el-drawer :model-value="modelValue" :title="title || '调用完整日志'" size="54%" @close="emit('update:modelValue', false)">
    <SectionCard title="日志内容" description="按本次开放调用请求隔离采集，完整正文从对象存储按需读取。">
      <div class="log-toolbar">
        <div class="log-meta">
          <span>大小：{{ formatSize(activeLog?.sizeBytes) }}</span>
          <span>字符集：{{ activeLog?.charset || "UTF-8" }}</span>
          <span v-if="activeLog?.paged">分页：{{ formatSize(activeLog?.pageSizeBytes) }}</span>
          <span v-if="activeLog?.updatedAt">更新时间：{{ activeLog.updatedAt }}</span>
        </div>
        <div class="studio-toolbar-actions">
          <el-button link type="primary" :loading="logLoading" @click="refreshLog">刷新</el-button>
          <el-button link type="primary" @click="downloadLog">下载</el-button>
        </div>
      </div>
      <div v-if="activeLog?.historicalFallback" class="log-note">
        当前记录没有可用归档文件，已展示数据库中的摘要或归档错误信息。
      </div>
      <div v-else-if="activeLog?.paged" class="log-note">
        日志较大，当前按页读取；需要完整内容请使用下载。
      </div>
      <div v-else-if="activeLog?.truncated" class="log-note">
        日志内容已截断，请使用下载查看完整内容。
      </div>
      <el-input
        type="textarea"
        readonly
        resize="none"
        :rows="16"
        class="log-content-input"
        :model-value="displayedLogContent || '暂无日志内容'"
      />
      <div v-if="showLogPagination" class="log-pagination">
        <span class="log-pagination-summary">
          第 {{ currentLogPage }} / {{ totalLogPages }} 页，每页 {{ formatSize(activeLog?.pageSizeBytes) }}
        </span>
        <el-pagination
          :current-page="currentLogPage"
          :page-size="1"
          small
          background
          layout="prev, pager, next"
          :total="totalLogPages"
          @current-change="handleLogPageChange"
        />
      </div>
    </SectionCard>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import type { EntityId, RunLogView } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";

const props = defineProps<{
  modelValue: boolean;
  domain?: string | null;
  accessLogId?: EntityId | null;
  title?: string;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
}>();

const DEFAULT_LOG_PAGE_SIZE_BYTES = 64 * 1024;
const activeLog = ref<RunLogView | null>(null);
const logLoading = ref(false);
const logPageNo = ref(1);

const displayedLogContent = computed(() => {
  const content = activeLog.value?.content;
  if (!content) {
    return "";
  }
  return String(content).replace(/\r\n/g, "\n").replace(/\r/g, "\n");
});
const totalLogPages = computed(() => Math.max(1, activeLog.value?.totalPages || 1));
const currentLogPage = computed(() => Math.max(1, activeLog.value?.pageNo || logPageNo.value || 1));
const showLogPagination = computed(() => !activeLog.value?.historicalFallback && totalLogPages.value > 1);

async function loadLog(pageNo?: number) {
  if (!props.modelValue || !props.domain || !props.accessLogId) {
    activeLog.value = null;
    return;
  }
  logLoading.value = true;
  try {
    const log = await studioApi.invocationLogs.get(props.domain, props.accessLogId, {
      pageNo,
      pageSizeBytes: DEFAULT_LOG_PAGE_SIZE_BYTES,
    });
    activeLog.value = log;
    logPageNo.value = log.pageNo || 1;
  } finally {
    logLoading.value = false;
  }
}

async function refreshLog() {
  try {
    await loadLog(currentLogPage.value);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载调用日志失败");
  }
}

async function handleLogPageChange(page: number) {
  if (page === currentLogPage.value) {
    return;
  }
  try {
    await loadLog(page);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载调用日志失败");
  }
}

async function downloadLog() {
  if (!props.domain || !props.accessLogId) {
    return;
  }
  try {
    const log = await studioApi.invocationLogs.download(props.domain, props.accessLogId);
    const blob = new Blob([log.content || ""], { type: log.contentType || "text/plain;charset=UTF-8" });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = log.downloadName || `${props.domain}-${props.accessLogId}.log`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "下载调用日志失败");
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
  () => [props.modelValue, props.domain, props.accessLogId],
  () => {
    logPageNo.value = 1;
    void loadLog();
  },
  { immediate: true },
);
</script>

<style scoped>
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

.log-content-input {
  width: 100%;
}

.log-content-input :deep(.el-textarea__inner) {
  min-height: 320px !important;
  max-height: 58vh;
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

.log-pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.log-pagination-summary {
  color: var(--studio-text-soft);
  font-size: 12px;
}
</style>
