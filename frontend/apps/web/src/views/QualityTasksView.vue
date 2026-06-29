<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>质量任务</h3>
        <p>将质量规则绑定到具体数据源、模型和字段，支持发布、手动触发，并跳转到独立运行日志页面。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="router.push('/quality-metrics')">质量指标</el-button>
        <el-button type="primary" @click="router.push('/quality-tasks/new')">新建任务</el-button>
        <el-button plain @click="loadTasks">刷新</el-button>
      </div>
    </div>

    <SectionCard title="筛选条件" description="按任务名称、规则维度、校验粒度和发布状态快速定位质量任务。">
      <div class="task-filter-grid">
        <el-input v-model="filters.keyword" class="task-filter-keyword" clearable placeholder="任务名称、任务编码、规则名称" />
        <el-select v-model="filters.status" class="task-filter-select" clearable placeholder="任务状态">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" :value="STUDIO_RUN_STATUS.ONLINE" />
        </el-select>
        <el-select v-model="filters.ruleDimension" class="task-filter-select" clearable placeholder="规则维度">
          <el-option v-for="option in dimensionOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select v-model="filters.granularity" class="task-filter-select" clearable placeholder="校验粒度">
          <el-option label="表级" value="TABLE" />
          <el-option label="字段级" value="COLUMN" />
        </el-select>
        <div class="task-filter-actions">
          <el-button type="primary" @click="searchTasks">查询</el-button>
          <el-button plain @click="resetFilters">重置</el-button>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="任务列表" description="可在列表中直接发布、执行任务，并跳转到质量任务运行日志页面。">
      <StudioTableShell min-width="1630px">
        <el-table
          :data="tasks"
          border
          size="small"
          table-layout="fixed"
          scrollbar-always-on
          max-height="calc(100vh - 340px)"
          class="quality-task-table"
        >
        <el-table-column label="序号" width="78" align="center" header-align="center">
          <template #default="{ $index }">
            {{ getPaginatedRowNumber(taskPagination, $index) }}
          </template>
        </el-table-column>
        <el-table-column label="任务名称" min-width="260">
          <template #default="{ row }">
            <strong class="task-name-text">{{ row.taskName || "-" }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="任务编码" min-width="220">
          <template #default="{ row }">
            <span class="task-code-text">{{ row.taskCode || "-" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="所属项目" min-width="170">
          <template #default="{ row }">
            <div class="table-entity-cell">
              <span>{{ resolveProjectLabel(row.projectId) }}</span>
              <span class="table-entity-cell__meta">{{ isSharedTask(row) ? "共享来源" : "当前项目" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="规则信息" min-width="210">
          <template #default="{ row }">
            <div class="table-entity-cell">
              <span class="table-entity-cell__title">{{ row.ruleName || "-" }}</span>
              <span class="table-entity-cell__meta">{{ resolveDimensionLabel(row.ruleDimension) }} · {{ row.granularity === "COLUMN" ? "字段级" : "表级" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="数据范围" min-width="220">
          <template #default="{ row }">
            <div class="quality-scope-cell">
              <div class="quality-scope-path">
                <span class="quality-scope-node quality-scope-node--source">{{ row.datasourceName || "-" }}</span>
                <span class="quality-scope-separator">→</span>
                <span class="quality-scope-node quality-scope-node--model">{{ row.modelName || "-" }}</span>
              </div>
              <div class="quality-scope-meta">
                <span class="quality-scope-meta__label">{{ row.granularity === "COLUMN" ? "字段" : "范围" }}</span>
                <span class="quality-scope-meta__value">{{ row.columnName || (row.granularity === "COLUMN" ? "-" : "全表") }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="row.status === STUDIO_RUN_STATUS.ONLINE ? '已发布' : '草稿'" :tone="row.status === STUDIO_RUN_STATUS.ONLINE ? 'success' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="180" />
        <el-table-column prop="updatedAt" label="更新时间" min-width="180" />
        <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
          <template #default="{ row }">
            <OverflowActionGroup :items="buildActions(row)" />
          </template>
        </el-table-column>
        </el-table>
      </StudioTableShell>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="taskPagination.page"
          v-model:page-size="taskPagination.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="taskTotal"
          @current-change="handlePageChange"
          @size-change="handlePageSizeChange"
        />
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import type { EntityId, QualityTaskListView } from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { getPaginatedRowNumber, type ClientPaginationState } from "@/composables/useClientPagination";
import { STUDIO_RUN_STATUS } from "@/constants/studioDomain";
import { isSharedFromAnotherProject, resolveProjectName } from "@/utils/studio";

const router = useRouter();
const authStore = useAuthStore();

const dimensionOptions = [
  { label: "一致性", value: "CONSISTENCY" },
  { label: "准确性", value: "ACCURACY" },
  { label: "唯一性", value: "UNIQUENESS" },
  { label: "及时性", value: "TIMELINESS" },
  { label: "完整性", value: "COMPLETENESS" },
  { label: "有效性", value: "VALIDITY" },
];

const filters = reactive<{
  keyword: string;
  status: string;
  ruleDimension: string;
  granularity: string;
}>({
  keyword: "",
  status: "",
  ruleDimension: "",
  granularity: "",
});

const tasks = ref<QualityTaskListView[]>([]);
const taskTotal = ref(0);
const taskPagination = reactive<ClientPaginationState>({
  page: 1,
  pageSize: 10,
});

async function loadTasks() {
  try {
    const page = await studioApi.qualityTasks.listPage({
      pageNo: taskPagination.page,
      pageSize: taskPagination.pageSize,
      keyword: filters.keyword.trim() || undefined,
      status: filters.status || undefined,
      ruleDimension: filters.ruleDimension || undefined,
      granularity: filters.granularity || undefined,
    });
    const maxPage = Math.max(1, Math.ceil(page.total / taskPagination.pageSize));
    if (taskPagination.page > maxPage) {
      taskPagination.page = maxPage;
      await loadTasks();
      return;
    }
    tasks.value = page.items;
    taskTotal.value = page.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量任务失败");
  }
}

async function searchTasks() {
  taskPagination.page = 1;
  await loadTasks();
}

function resetFilters() {
  filters.keyword = "";
  filters.status = "";
  filters.ruleDimension = "";
  filters.granularity = "";
  taskPagination.page = 1;
  void loadTasks();
}

function handlePageChange() {
  void loadTasks();
}

function handlePageSizeChange() {
  taskPagination.page = 1;
  void loadTasks();
}

function resolveDimensionLabel(value?: string) {
  return dimensionOptions.find((item) => item.value === value)?.label ?? value ?? "-";
}

function resolveProjectLabel(projectId?: EntityId | null) {
  return resolveProjectName(authStore.projects, projectId);
}

function isSharedTask(row: QualityTaskListView) {
  return isSharedFromAnotherProject(authStore.currentProjectId, row.projectId);
}

function buildActions(row: QualityTaskListView) {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => { void router.push(`/quality-tasks/${row.id}/edit`); } },
    { key: "publish", label: row.status === STUDIO_RUN_STATUS.ONLINE ? "重新发布" : "发布", onClick: () => publishTask(row) },
    { key: "trigger", label: "手动执行", onClick: () => triggerTask(row) },
    { key: "runs", label: "运行日志", onClick: () => viewTaskRuns(row) },
    { key: "delete", label: "删除", type: "danger", onClick: () => deleteTask(row) },
  ];
}

async function publishTask(task: QualityTaskListView) {
  if (!task.id) {
    return;
  }
  try {
    const updated = await studioApi.qualityTasks.publish(task.id);
    patchTaskRow(task, updated);
    ElMessage.success("质量任务已发布");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "发布质量任务失败");
  }
}

function patchTaskRow(task: QualityTaskListView, patch: Partial<QualityTaskListView>) {
  const target = tasks.value.find((item) => item.id === task.id);
  if (!target) {
    return;
  }
  const keys: (keyof QualityTaskListView)[] = [
    "updatedAt",
    "taskName",
    "taskCode",
    "status",
    "ruleId",
    "ruleName",
    "ruleDimension",
    "granularity",
    "datasourceId",
    "datasourceName",
    "datasourceTypeCode",
    "modelId",
    "modelName",
    "modelPhysicalLocator",
    "columnName",
  ];
  const next: Partial<QualityTaskListView> = {};
  for (const key of keys) {
    if (Object.prototype.hasOwnProperty.call(patch, key)) {
      (next as Record<string, unknown>)[key] = (patch as Record<string, unknown>)[key];
    }
  }
  Object.assign(target, next);
}

async function triggerTask(task: QualityTaskListView) {
  if (!task.id) {
    return;
  }
  try {
    await studioApi.qualityTasks.trigger(task.id);
    ElMessage.success("已触发质量任务执行");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "触发质量任务失败");
  }
}

async function deleteTask(task: QualityTaskListView) {
  if (!task.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除任务“${task.taskName}”吗？`, "提示", { type: "warning" });
    await studioApi.qualityTasks.delete(task.id);
    ElMessage.success("质量任务已删除");
    removeTaskRow(task);
    if (tasks.value.length === 0 && taskTotal.value > 0) {
      taskPagination.page = Math.max(1, taskPagination.page - 1);
      await loadTasks();
    }
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除质量任务失败");
    }
  }
}

function removeTaskRow(task: QualityTaskListView) {
  const beforeCount = tasks.value.length;
  tasks.value = tasks.value.filter((item) => item.id !== task.id);
  if (tasks.value.length !== beforeCount) {
    taskTotal.value = Math.max(0, taskTotal.value - 1);
  }
}

function viewTaskRuns(task: QualityTaskListView) {
  router.push({
    path: "/quality-task-runs",
    query: task.id ? { qualityTaskId: String(task.id) } : undefined,
  });
}

onMounted(loadTasks);
</script>

<style scoped>
.task-filter-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.task-filter-keyword {
  flex: 0 1 320px;
}

.task-filter-select {
  width: 168px;
}

.task-filter-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.quality-task-table {
  width: 100%;
}

.quality-task-table :deep(.cell) {
  white-space: normal;
}

.task-name-text,
.task-code-text {
  display: inline-block;
  max-width: 100%;
  overflow-wrap: anywhere;
  line-height: 1.35;
}

@media (max-width: 760px) {
  .task-filter-grid > * {
    width: 100%;
    flex-basis: 100%;
  }

  .task-filter-actions {
    justify-content: flex-start;
  }
}

.stack-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.quality-scope-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 7px;
}

.quality-scope-path {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.quality-scope-node {
  min-width: 0;
  max-width: 132px;
  overflow: hidden;
  color: var(--studio-text-strong);
  font-weight: 650;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quality-scope-node--source {
  color: #23507d;
}

.quality-scope-node--model {
  flex: 1 1 auto;
}

.quality-scope-separator {
  flex: 0 0 auto;
  color: rgba(35, 80, 125, 0.42);
  font-size: 13px;
}

.quality-scope-meta {
  display: inline-flex;
  width: fit-content;
  max-width: 100%;
  align-items: center;
  overflow: hidden;
  border: 1px solid rgba(16, 78, 139, 0.1);
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(239, 246, 255, 0.78));
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.4;
}

.quality-scope-meta__label {
  flex: 0 0 auto;
  padding: 3px 7px;
  border-right: 1px solid rgba(16, 78, 139, 0.1);
  background: rgba(16, 78, 139, 0.06);
  color: #386182;
  font-weight: 650;
}

.quality-scope-meta__value {
  min-width: 0;
  overflow: hidden;
  padding: 3px 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
