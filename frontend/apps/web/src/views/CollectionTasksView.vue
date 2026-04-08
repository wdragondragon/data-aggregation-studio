<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.collectionTasks.heading") }}</h3>
        <p>{{ t("web.collectionTasks.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" @click="router.push('/collection-tasks/new')">{{ t("web.collectionTasks.newTask") }}</el-button>
        <el-button plain @click="loadTasks">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.collectionTasks.filterTitle')" :description="t('web.collectionTasks.filterDescription')">
      <div class="task-filter-grid">
        <el-input v-model="filters.name" :placeholder="t('web.collectionTasks.filterNamePlaceholder')" clearable />
        <el-input v-model="filters.targetDatasource" :placeholder="t('web.collectionTasks.filterDatasourcePlaceholder')" clearable />
        <el-input v-model="filters.targetModel" :placeholder="t('web.collectionTasks.filterModelPlaceholder')" clearable />
        <div class="task-filter-actions">
          <el-button type="primary" @click="loadTasks">{{ t("common.search") }}</el-button>
          <el-button plain @click="resetFilters">{{ t("common.reset") }}</el-button>
        </div>
      </div>
    </SectionCard>

    <SectionCard :title="t('web.collectionTasks.listTitle')" :description="t('web.collectionTasks.listDescription')">
        <el-table :data="pagedTasks" border size="small" table-layout="fixed" class="task-table">
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">
              {{ getPaginatedRowNumber(taskPagination, $index) }}
            </template>
          </el-table-column>
          <el-table-column :label="t('web.collectionTasks.name')" min-width="180">
          <template #default="{ row }">
            <div class="task-primary-cell">
              <el-button link type="primary" class="task-name-link" @click="viewTask(row)">
                {{ row.name }}
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="`${t('web.collectionTasks.targetDatasource')} / ${t('web.collectionTasks.targetModel')}`" min-width="200">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ row.targetBinding?.datasourceName ?? t('common.none') }}</span>
              <span class="cell-subtle">{{ row.targetBinding?.modelPhysicalLocator ?? row.targetBinding?.modelName ?? t('common.none') }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="`${t('web.collectionTasks.type')} / ${t('web.collectionTasks.sourceCount')}`" min-width="150">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ formatCollectionTaskType(t, row.taskType) }}</span>
              <span class="cell-subtle">{{ row.sourceCount || 0 }} {{ t("web.collectionTasks.sourceCountUnit") }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.collectionTasks.schedule')" min-width="180">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ row.schedule?.enabled ? t("common.on") : t("common.off") }}</span>
              <span class="cell-subtle">{{ row.schedule?.enabled ? row.schedule?.cronExpression || t('common.none') : t('common.none') }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.collectionTasks.status')" width="100" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="formatStatusLabel(t, row.status)" :tone="row.status === 'ONLINE' ? 'success' : 'warning'" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.metadata.actions')" width="120" align="center" header-align="center">
          <template #default="{ row }">
            <OverflowActionGroup :items="buildTaskActions(row)" />
          </template>
        </el-table-column>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="taskPagination.page"
          v-model:page-size="taskPagination.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="tasks.length"
        />
      </div>
    </SectionCard>

    <el-drawer v-model="logsVisible" :title="t('web.collectionTasks.logsTitle', { name: activeTask?.name || '' })" size="68%">
      <template v-if="activeTask">
        <SectionCard :title="t('web.collectionTasks.logsListTitle')" :description="t('web.collectionTasks.logsListDescription')">
          <el-table :data="pagedTaskRunRecords" border size="small" table-layout="fixed" class="task-run-table">
            <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
              <template #default="{ $index }">
                {{ getPaginatedRowNumber(taskRunPagination, $index) }}
              </template>
            </el-table-column>
            <el-table-column :label="`${t('web.runs.workflow')} / ${t('web.runs.node')}`" min-width="180">
              <template #default="{ row }">
                <div class="stack-cell">
                  <span>{{ row.workflowName || "--" }}</span>
                  <span class="cell-subtle">{{ row.nodeCode || t("common.none") }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="`${t('web.runs.startedAt')} / ${t('web.runs.duration')}`" min-width="180">
              <template #default="{ row }">
                <div class="stack-cell">
                  <span>{{ row.startedAt || t("common.none") }}</span>
                  <span class="cell-subtle">{{ row.endedAt || t("common.none") }} · {{ formatDuration(row) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="t('web.runs.status')" width="120" align="center" header-align="center">
              <template #default="{ row }">
                <StatusPill :label="formatStatusLabel(t, row.status)" :tone="toneFromStatus(row.status)" />
              </template>
            </el-table-column>
            <el-table-column :label="t('web.runs.message')" min-width="220">
              <template #default="{ row }">
                <div class="wrap-cell">{{ row.message || t("common.none") }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="t('web.runs.actions')" width="120" align="center" header-align="center">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="!row.id" @click="activeRunRecordId = row.id">
                  {{ t("web.runs.viewLog") }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="taskRunPagination.page"
              v-model:page-size="taskRunPagination.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="[10, 20, 50, 100]"
              :total="taskRunRecords.length"
            />
          </div>
        </SectionCard>
      </template>
    </el-drawer>

    <RunLogDrawer v-model="logDrawerVisible" :run-record-id="activeRunRecordId" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { CollectionTaskDefinitionView, CollectionTaskListQuery, RunRecord } from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import RunLogDrawer from "../components/RunLogDrawer.vue";
import { getPaginatedRowNumber, useClientPagination } from "@/composables/useClientPagination";
import { formatCollectionTaskType, formatStatusLabel, toneFromStatus } from "@/utils/studio";

const { t } = useI18n();
const router = useRouter();
const tasks = ref<CollectionTaskDefinitionView[]>([]);
const activeTask = ref<CollectionTaskDefinitionView | null>(null);
const taskRunRecords = ref<RunRecord[]>([]);
const { pagination: taskPagination, pagedItems: pagedTasks, resetPagination: resetTaskPagination } = useClientPagination(tasks);
const { pagination: taskRunPagination, pagedItems: pagedTaskRunRecords, resetPagination: resetTaskRunPagination } = useClientPagination(taskRunRecords);
const logsVisible = ref(false);
const activeRunRecordId = ref<string | number | undefined>(undefined);
const logDrawerVisible = ref(false);
const filters = ref<CollectionTaskListQuery>({
  name: "",
  targetDatasource: "",
  targetModel: "",
});

async function loadTasks() {
  try {
    tasks.value = await studioApi.collectionTasks.list({
      name: filters.value.name?.trim() || undefined,
      targetDatasource: filters.value.targetDatasource?.trim() || undefined,
      targetModel: filters.value.targetModel?.trim() || undefined,
    });
    resetTaskPagination();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.loadFailed"));
  }
}

function resetFilters() {
  filters.value = {
    name: "",
    targetDatasource: "",
    targetModel: "",
  };
  void loadTasks();
}

function editTask(task: CollectionTaskDefinitionView) {
  router.push(`/collection-tasks/${task.id}/edit`);
}

function viewTask(task: CollectionTaskDefinitionView) {
  router.push(`/collection-tasks/${task.id}/edit`);
}

function manageSchedule(task: CollectionTaskDefinitionView) {
  router.push(`/collection-tasks/${task.id}/edit?step=3`);
}

function buildTaskActions(task: CollectionTaskDefinitionView) {
  return [
    { key: "edit", label: t("common.edit"), type: "primary", onClick: () => editTask(task) },
    { key: "schedule", label: t("web.collectionTasks.scheduleManage"), onClick: () => manageSchedule(task) },
    { key: "logs", label: t("web.collectionTasks.runRecords"), onClick: () => openLogs(task) },
    { key: "online", label: t("web.collectionTasks.online"), type: "success", disabled: task.status === "ONLINE", onClick: () => publishTask(task) },
    { key: "trigger", label: t("common.trigger"), type: "warning", onClick: () => triggerTask(task) },
    { key: "delete", label: t("common.delete"), type: "danger", onClick: () => deleteTask(task) },
  ];
}

async function openLogs(task: CollectionTaskDefinitionView) {
  activeTask.value = task;
  logsVisible.value = true;
  try {
    const result = await studioApi.runs.list({
      collectionTaskId: task.id,
    });
    taskRunRecords.value = [...result.runRecords].sort((left, right) => {
      const leftTime = left.startedAt || left.createdAt || "";
      const rightTime = right.startedAt || right.createdAt || "";
      return rightTime.localeCompare(leftTime);
    });
    resetTaskRunPagination();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.runs.loadFailed"));
  }
}

async function publishTask(task: CollectionTaskDefinitionView) {
  if (!task.id) {
    return;
  }
  try {
    await studioApi.collectionTasks.publish(task.id);
    ElMessage.success(t("web.collectionTasks.onlineSuccess"));
    await loadTasks();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.onlineFailed"));
  }
}

async function triggerTask(task: CollectionTaskDefinitionView) {
  if (!task.id) {
    return;
  }
  try {
    await studioApi.collectionTasks.trigger(task.id);
    ElMessage.success(t("web.collectionTasks.triggerSuccess"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.triggerFailed"));
  }
}

async function deleteTask(task: CollectionTaskDefinitionView) {
  if (!task.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.collectionTasks.deleteConfirmMessage", { name: task.name }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.collectionTasks.delete(task.id);
    ElMessage.success(t("web.collectionTasks.deleteSuccess"));
    await loadTasks();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.collectionTasks.deleteFailed"));
    }
  }
}

function formatDuration(record: RunRecord) {
  const durationMs = Number(record.resultJson?.durationMs ?? record.payloadJson?.durationMs);
  if (Number.isFinite(durationMs) && durationMs >= 0) {
    return humanizeDuration(durationMs);
  }
  if (!record.startedAt || !record.endedAt) {
    return t("common.none");
  }
  const started = Date.parse(record.startedAt.replace(" ", "T"));
  const ended = Date.parse(record.endedAt.replace(" ", "T"));
  if (Number.isNaN(started) || Number.isNaN(ended) || ended < started) {
    return t("common.none");
  }
  return humanizeDuration(ended - started);
}

function humanizeDuration(durationMs: number) {
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

watch(activeRunRecordId, (value) => {
  logDrawerVisible.value = value != null;
});

watch(logDrawerVisible, (value) => {
  if (!value) {
    activeRunRecordId.value = undefined;
  }
});

onMounted(loadTasks);
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.task-filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  align-items: center;
}

.task-filter-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.task-name-link {
  padding: 0;
  font-weight: 600;
}

.task-table :deep(.cell),
.task-run-table :deep(.cell) {
  white-space: normal;
}

.task-table,
.task-run-table {
  width: 100%;
}

.task-primary-cell,
.stack-cell,
.wrap-cell {
  display: grid;
  gap: 4px;
  min-width: 0;
  line-height: 1.45;
  word-break: break-word;
}

.stack-cell--center {
  justify-items: center;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.table-pagination :deep(.el-pagination) {
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 1200px) {
  .task-filter-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .task-filter-actions {
    justify-content: flex-start;
  }

  .table-pagination {
    justify-content: flex-start;
  }

  .table-pagination :deep(.el-pagination) {
    justify-content: flex-start;
  }
}
</style>
