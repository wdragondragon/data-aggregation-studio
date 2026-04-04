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
      <el-table :data="tasks" border>
        <el-table-column :label="t('web.collectionTasks.name')" min-width="220">
          <template #default="{ row }">
            <el-button link type="primary" class="task-name-link" @click="viewTask(row)">
              {{ row.name }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.collectionTasks.targetDatasource')" min-width="180">
          <template #default="{ row }">
            <span>{{ row.targetBinding?.datasourceName ?? t('common.none') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.collectionTasks.targetModel')" min-width="180">
          <template #default="{ row }">
            <span>{{ row.targetBinding?.modelPhysicalLocator ?? row.targetBinding?.modelName ?? t('common.none') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="taskType" :label="t('web.collectionTasks.type')" width="140" />
        <el-table-column prop="sourceCount" :label="t('web.collectionTasks.sourceCount')" width="120" />
        <el-table-column :label="t('web.collectionTasks.schedule')" min-width="180">
          <template #default="{ row }">
            <span>{{ row.schedule?.enabled ? row.schedule?.cronExpression || t('common.on') : t('common.off') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.collectionTasks.status')" width="120">
          <template #default="{ row }">
            <StatusPill :label="row.status ?? t('common.unknown')" :tone="row.status === 'ONLINE' ? 'success' : 'warning'" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.metadata.actions')" width="430">
          <template #default="{ row }">
            <el-button link type="primary" @click="editTask(row)">{{ t("common.edit") }}</el-button>
            <el-button link @click="manageSchedule(row)">{{ t("web.collectionTasks.scheduleManage") }}</el-button>
            <el-button link @click="openLogs(row)">{{ t("web.collectionTasks.runRecords") }}</el-button>
            <el-button link type="success" :disabled="row.status === 'ONLINE'" @click="publishTask(row)">{{ t("web.collectionTasks.online") }}</el-button>
            <el-button link type="warning" @click="triggerTask(row)">{{ t("common.trigger") }}</el-button>
            <el-button link type="danger" @click="deleteTask(row)">{{ t("common.delete") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>

    <el-drawer v-model="logsVisible" :title="t('web.collectionTasks.logsTitle', { name: activeTask?.name || '' })" size="68%">
      <template v-if="activeTask">
        <SectionCard :title="t('web.collectionTasks.logsListTitle')" :description="t('web.collectionTasks.logsListDescription')">
          <el-table :data="taskRunRecords" border>
            <el-table-column :label="t('web.runs.workflow')" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.workflowName || "--" }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="nodeCode" :label="t('web.runs.node')" min-width="160" show-overflow-tooltip />
            <el-table-column :label="t('web.runs.startedAt')" min-width="180">
              <template #default="{ row }">
                <span>{{ row.startedAt || t("common.none") }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('web.runs.endedAt')" min-width="180">
              <template #default="{ row }">
                <span>{{ row.endedAt || t("common.none") }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('web.runs.duration')" width="140">
              <template #default="{ row }">
                <span>{{ formatDuration(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('web.runs.status')" width="120">
              <template #default="{ row }">
                <StatusPill :label="row.status ?? t('common.unknown')" :tone="toneFromStatus(row.status)" />
              </template>
            </el-table-column>
            <el-table-column prop="message" :label="t('web.runs.message')" min-width="320" show-overflow-tooltip />
            <el-table-column :label="t('web.runs.actions')" width="120" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="!row.id" @click="activeRunRecordId = row.id">
                  {{ t("web.runs.viewLog") }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
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
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import RunLogDrawer from "../components/RunLogDrawer.vue";
import { toneFromStatus } from "@/utils/studio";

const { t } = useI18n();
const router = useRouter();
const tasks = ref<CollectionTaskDefinitionView[]>([]);
const activeTask = ref<CollectionTaskDefinitionView | null>(null);
const taskRunRecords = ref<RunRecord[]>([]);
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
  gap: 12px;
  align-items: center;
}

.task-filter-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.task-name-link {
  padding: 0;
  font-weight: 600;
}

@media (max-width: 1200px) {
  .task-filter-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .task-filter-actions {
    justify-content: flex-start;
  }
}
</style>
