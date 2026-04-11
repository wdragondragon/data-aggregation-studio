<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ task?.name || "模型同步任务详情" }}</h3>
        <p>查看同步任务进度、统计信息和逐表执行结果。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="goBack">返回列表</el-button>
        <el-button plain @click="loadDetail">刷新</el-button>
        <FollowToggleButton v-if="task?.id" target-type="MODEL_SYNC_TASK" :target-id="task.id" />
        <el-button plain type="warning" :disabled="!canStop" @click="stopTask">停止</el-button>
        <el-button plain type="danger" :disabled="!canDelete" @click="deleteTask">删除</el-button>
      </div>
    </div>

    <SectionCard title="任务概览" description="同步任务会按批次后台执行，不会阻塞当前页面。">
      <div class="task-summary">
        <div class="detail-grid">
          <div class="detail-item">
            <span class="detail-label">同步名称</span>
            <strong>{{ task?.name || t("common.none") }}</strong>
          </div>
          <div class="detail-item">
            <span class="detail-label">数据源类型</span>
            <strong>{{ task?.datasourceType || t("common.none") }}</strong>
          </div>
          <div class="detail-item">
            <span class="detail-label">数据源</span>
            <strong>{{ task?.datasourceNameSnapshot || t("common.none") }}</strong>
          </div>
          <div class="detail-item">
            <span class="detail-label">状态</span>
            <StatusPill :label="formatStatusLabel(t, task?.status)" :tone="toneFromStatus(task?.status)" />
          </div>
          <div class="detail-item">
            <span class="detail-label">创建时间</span>
            <strong>{{ task?.createdAt || t("common.none") }}</strong>
          </div>
          <div class="detail-item">
            <span class="detail-label">持续时间</span>
            <strong>{{ formatDurationMs(task?.durationMs) }}</strong>
          </div>
        </div>

        <div class="task-stats">
          <div class="task-stat">
            <span class="detail-label">总数</span>
            <strong>{{ Number(task?.totalCount || 0) }}</strong>
          </div>
          <div class="task-stat">
            <span class="detail-label">成功</span>
            <strong>{{ Number(task?.successCount || 0) }}</strong>
          </div>
          <div class="task-stat">
            <span class="detail-label">失败</span>
            <strong>{{ Number(task?.failedCount || 0) }}</strong>
          </div>
          <div class="task-stat">
            <span class="detail-label">停止</span>
            <strong>{{ Number(task?.stoppedCount || 0) }}</strong>
          </div>
        </div>

        <div class="soft-panel">
          <div class="task-progress__header">
            <strong>同步进度</strong>
            <span class="cell-subtle">{{ Number(task?.progressPercent || 0) }}%</span>
          </div>
          <el-progress :percentage="Number(task?.progressPercent || 0)" />
          <div v-if="task?.lastError" class="task-error">
            <strong>最近错误：</strong>{{ task.lastError }}
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="执行明细" description="按表查看同步结果、耗时和错误信息。">
      <div class="task-filter-grid">
        <el-input v-model="filters.keyword" clearable placeholder="按表名搜索" />
        <el-select v-model="filters.status" clearable placeholder="按状态筛选">
          <el-option v-for="status in statusOptions" :key="status" :label="formatStatusLabel(t, status)" :value="status" />
        </el-select>
        <div class="task-filter-actions">
          <el-button type="primary" @click="reloadItems">查询</el-button>
          <el-button plain @click="resetFilters">重置</el-button>
        </div>
      </div>

      <el-table :data="items" border v-loading="loadingItems">
        <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
          <template #default="{ $index }">
            {{ (pagination.page - 1) * pagination.pageSize + $index + 1 }}
          </template>
        </el-table-column>
        <el-table-column label="表名" min-width="220">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ row.physicalLocator || t("common.none") }}</span>
              <span class="cell-subtle">{{ row.modelNameSnapshot || t("common.none") }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="formatStatusLabel(t, row.status)" :tone="toneFromStatus(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="140">
          <template #default="{ row }">
            {{ formatDurationMs(row.durationMs) }}
          </template>
        </el-table-column>
        <el-table-column label="错误信息" min-width="260">
          <template #default="{ row }">
            <div class="wrap-cell">{{ row.message || t("common.none") }}</div>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          @current-change="loadItems"
          @size-change="handlePageSizeChange"
        />
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { ModelSyncTaskItemView, ModelSyncTaskView } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import FollowToggleButton from "@/components/FollowToggleButton.vue";
import { formatStatusLabel, toneFromStatus } from "@/utils/studio";

const route = useRoute();
const router = useRouter();
const { t } = useI18n();
const task = ref<ModelSyncTaskView | null>(null);
const items = ref<ModelSyncTaskItemView[]>([]);
const total = ref(0);
const loadingItems = ref(false);
const pagination = reactive({
  page: 1,
  pageSize: 20,
});
const filters = reactive({
  keyword: "",
  status: "",
});
const statusOptions = ["PENDING", "RUNNING", "SUCCESS", "FAILED", "STOPPED"];

const taskId = computed(() => route.params.taskId as string | undefined);
const canStop = computed(() => ["PENDING", "RUNNING", "STOPPING"].includes(String(task.value?.status || "").toUpperCase()));
const canDelete = computed(() => ["SUCCESS", "FAILED", "STOPPED"].includes(String(task.value?.status || "").toUpperCase()));

async function loadTask() {
  if (!taskId.value) {
    return;
  }
  task.value = await studioApi.modelSyncTasks.get(taskId.value);
}

async function loadItems() {
  if (!taskId.value) {
    return;
  }
  loadingItems.value = true;
  try {
    const result = await studioApi.modelSyncTasks.listItems(taskId.value, {
      pageNo: pagination.page,
      pageSize: pagination.pageSize,
      keyword: filters.keyword.trim() || undefined,
      status: filters.status || undefined,
    });
    items.value = result.items;
    total.value = result.total;
  } finally {
    loadingItems.value = false;
  }
}

async function loadDetail() {
  try {
    await Promise.all([loadTask(), loadItems()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载同步任务详情失败");
  }
}

function reloadItems() {
  pagination.page = 1;
  void loadItems();
}

function resetFilters() {
  filters.keyword = "";
  filters.status = "";
  pagination.page = 1;
  void loadItems();
}

function handlePageSizeChange(pageSize: number) {
  pagination.pageSize = pageSize;
  pagination.page = 1;
  void loadItems();
}

async function stopTask() {
  if (!taskId.value || !canStop.value) {
    return;
  }
  try {
    await studioApi.modelSyncTasks.stop(taskId.value);
    ElMessage.success("已请求停止同步任务");
    await loadDetail();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "停止同步任务失败");
  }
}

async function deleteTask() {
  if (!taskId.value || !canDelete.value) {
    return;
  }
  try {
    await ElMessageBox.confirm("删除该同步任务吗？已同步的模型不会回滚。", t("common.confirm"), { type: "warning" });
    await studioApi.modelSyncTasks.delete(taskId.value);
    ElMessage.success("同步任务已删除");
    goBack();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除同步任务失败");
    }
  }
}

function goBack() {
  router.push({ name: "models", query: { tab: "sync-tasks" } });
}

function formatDurationMs(durationMs?: number) {
  if (durationMs == null || Number.isNaN(Number(durationMs))) {
    return t("common.none");
  }
  if (durationMs < 1000) {
    return `${durationMs} ms`;
  }
  const seconds = durationMs / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(seconds >= 10 ? 1 : 2)} s`;
  }
  const minutes = Math.floor(seconds / 60);
  const remainderSeconds = Math.round(seconds % 60);
  return `${minutes}m ${remainderSeconds}s`;
}

watch(taskId, () => {
  pagination.page = 1;
  void loadDetail();
}, { immediate: true });
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.task-summary {
  display: grid;
  gap: 12px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.detail-item,
.task-stat {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid var(--studio-border);
  background: rgba(255, 255, 255, 0.78);
}

.detail-label {
  font-size: 12px;
  color: var(--studio-text-soft);
}

.task-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.task-progress__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.task-error {
  margin-top: 8px;
  color: var(--el-color-danger);
  word-break: break-word;
}

.task-filter-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(180px, 0.8fr) auto;
  gap: 10px;
  margin-bottom: 12px;
}

.task-filter-actions {
  display: flex;
  gap: 10px;
}

.stack-cell {
  display: grid;
  gap: 4px;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.wrap-cell {
  white-space: normal;
  word-break: break-word;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

@media (max-width: 980px) {
  .detail-grid,
  .task-stats,
  .task-filter-grid {
    grid-template-columns: 1fr;
  }

  .table-pagination {
    justify-content: flex-start;
  }
}
</style>
