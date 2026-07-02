<template>
  <el-drawer :model-value="modelValue" :title="title || '调用完整日志'" size="54%" @close="emit('update:modelValue', false)">
    <SectionCard title="日志内容" description="按本次开放调用请求隔离采集，完整正文从对象存储按需读取。">
      <div class="log-toolbar">
        <div class="log-meta">
          <span>大小：{{ formatSize(activeDisplayLog?.sizeBytes) }}</span>
          <span>字符集：{{ activeDisplayLog?.charset || "UTF-8" }}</span>
          <span v-if="activeDisplayLog?.paged">分页：{{ formatSize(activeDisplayLog?.pageSizeBytes) }}</span>
          <span v-if="activeDisplayLog?.updatedAt">更新时间：{{ activeDisplayLog.updatedAt }}</span>
          <span v-if="activeSection">当前目标：{{ sectionTitle(activeSection) }}</span>
        </div>
        <div class="studio-toolbar-actions">
          <el-button link type="primary" :loading="activeLoading" @click="refreshLog">刷新</el-button>
          <el-button link type="primary" @click="downloadLog">下载</el-button>
        </div>
      </div>
      <div v-if="activeDisplayLog?.historicalFallback" class="log-note">
        当前记录没有可用归档文件，已展示数据库中的摘要或归档错误信息。
      </div>
      <div v-else-if="activeDisplayLog?.paged" class="log-note">
        日志较大，当前按页读取；需要完整内容请使用下载。
      </div>
      <div v-else-if="activeDisplayLog?.truncated" class="log-note">
        日志内容已截断，请使用下载查看完整内容。
      </div>
      <div v-if="hasSections" class="section-log-shell">
        <div class="section-log-tabs">
          <el-button :type="!activeSectionKey ? 'primary' : 'default'" size="small" @click="selectTotalLog">总日志</el-button>
        </div>
        <el-table :data="sections" border size="small" class="section-log-table" @row-click="selectSection">
          <el-table-column label="目标" min-width="180">
            <template #default="{ row }">
              <div class="section-target-cell">
                <strong>{{ sectionTitle(row) }}</strong>
                <span>{{ row.sectionKey || "-" }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="targetDatasourceName" label="数据源" min-width="140" show-overflow-tooltip />
          <el-table-column prop="targetModelName" label="模型" min-width="140" show-overflow-tooltip />
          <el-table-column prop="jobId" label="Job" min-width="130" show-overflow-tooltip />
          <el-table-column label="结果" width="90" align="center" header-align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status || "-" }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="归档" width="92" align="center" header-align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="archiveStatusType(row.archiveStatus)" :title="row.archiveError || ''">
                {{ archiveStatusText(row.archiveStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="接收/成功/失败" min-width="130">
            <template #default="{ row }">
              {{ formatNumber(row.receivedCount) }} / {{ formatNumber(row.successCount) }} / {{ formatNumber(row.failedCount) }}
            </template>
          </el-table-column>
          <el-table-column prop="message" label="消息" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="90" align="center" header-align="center">
            <template #default="{ row }">
              <el-button link type="primary" :loading="sectionLoading && activeSectionKey === row.sectionKey" @click.stop="selectSection(row)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
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
          第 {{ currentLogPage }} / {{ totalLogPages }} 页，每页 {{ formatSize(activeDisplayLog?.pageSizeBytes) }}
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
import type { EntityId, InvocationLogSectionView, RunLogView } from "@studio/api-sdk";
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
const activeSectionLog = ref<RunLogView | null>(null);
const logLoading = ref(false);
const sectionLoading = ref(false);
const logPageNo = ref(1);
const sectionPageNo = ref(1);
const activeSectionKey = ref("");

const activeDisplayLog = computed(() => (activeSectionKey.value ? activeSectionLog.value : activeLog.value));
const displayedLogContent = computed(() => {
  const content = activeDisplayLog.value?.content;
  if (!content) {
    return "";
  }
  return String(content).replace(/\r\n/g, "\n").replace(/\r/g, "\n");
});
const sections = computed(() => activeLog.value?.sections?.filter((item) => item.sectionKey) || []);
const hasSections = computed(() => sections.value.length > 0);
const activeSection = computed(() => sections.value.find((item) => item.sectionKey === activeSectionKey.value) || null);
const activeLoading = computed(() => (activeSectionKey.value ? sectionLoading.value : logLoading.value));
const totalLogPages = computed(() => Math.max(1, activeDisplayLog.value?.totalPages || 1));
const currentLogPage = computed(() => Math.max(1, activeDisplayLog.value?.pageNo || (activeSectionKey.value ? sectionPageNo.value : logPageNo.value) || 1));
const showLogPagination = computed(() => totalLogPages.value > 1);

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
    if (activeSectionKey.value && !sections.value.some((item) => item.sectionKey === activeSectionKey.value)) {
      activeSectionKey.value = "";
      activeSectionLog.value = null;
    }
  } finally {
    logLoading.value = false;
  }
}

async function loadSectionLog(sectionKey: string, pageNo?: number) {
  if (!props.modelValue || !props.domain || !props.accessLogId || !sectionKey) {
    activeSectionLog.value = null;
    return;
  }
  sectionLoading.value = true;
  try {
    const log = await studioApi.invocationLogs.getSection(props.domain, props.accessLogId, sectionKey, {
      pageNo,
      pageSizeBytes: DEFAULT_LOG_PAGE_SIZE_BYTES,
    });
    activeSectionLog.value = log;
    sectionPageNo.value = log.pageNo || 1;
  } finally {
    sectionLoading.value = false;
  }
}

async function refreshLog() {
  try {
    if (activeSectionKey.value) {
      await loadSectionLog(activeSectionKey.value, currentLogPage.value);
    } else {
      await loadLog(currentLogPage.value);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载调用日志失败");
  }
}

async function handleLogPageChange(page: number) {
  if (page === currentLogPage.value) {
    return;
  }
  try {
    if (activeSectionKey.value) {
      await loadSectionLog(activeSectionKey.value, page);
    } else {
      await loadLog(page);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载调用日志失败");
  }
}

async function downloadLog() {
  if (!props.domain || !props.accessLogId) {
    return;
  }
  try {
    const log = activeSectionKey.value
      ? await studioApi.invocationLogs.downloadSection(props.domain, props.accessLogId, activeSectionKey.value)
      : await studioApi.invocationLogs.download(props.domain, props.accessLogId);
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

async function selectSection(section: InvocationLogSectionView) {
  const sectionKey = section.sectionKey || "";
  if (!sectionKey) {
    return;
  }
  activeSectionKey.value = sectionKey;
  sectionPageNo.value = 1;
  try {
    await loadSectionLog(sectionKey);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载目标日志失败");
  }
}

function selectTotalLog() {
  activeSectionKey.value = "";
  activeSectionLog.value = null;
  logPageNo.value = activeLog.value?.pageNo || 1;
}

function sectionTitle(section?: InvocationLogSectionView | null) {
  if (!section) {
    return "-";
  }
  return section.sourceName || section.sourceCode || section.targetModelName || section.sectionKey || "-";
}

function formatNumber(value?: number | string) {
  return Number(value || 0).toLocaleString();
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

function archiveStatusText(status?: string) {
  const normalized = (status || "").toUpperCase();
  if (normalized === "AVAILABLE") {
    return "正常";
  }
  if (normalized === "FAILED") {
    return "失败";
  }
  if (normalized === "SKIPPED") {
    return "跳过";
  }
  if (normalized === "DISABLED") {
    return "关闭";
  }
  return "-";
}

function archiveStatusType(status?: string) {
  const normalized = (status || "").toUpperCase();
  if (normalized === "AVAILABLE") {
    return "success";
  }
  if (normalized === "FAILED") {
    return "danger";
  }
  if (normalized === "SKIPPED" || normalized === "DISABLED") {
    return "warning";
  }
  return "info";
}

watch(
  () => [props.modelValue, props.domain, props.accessLogId],
  () => {
    logPageNo.value = 1;
    sectionPageNo.value = 1;
    activeSectionKey.value = "";
    activeSectionLog.value = null;
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

.section-log-shell {
  margin-bottom: 12px;
}

.section-log-tabs {
  display: flex;
  justify-content: flex-start;
  margin-bottom: 8px;
}

.section-log-table {
  margin-bottom: 10px;
}

.section-target-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.section-target-cell strong,
.section-target-cell span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-target-cell span {
  color: var(--studio-text-soft);
  font-size: 12px;
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
