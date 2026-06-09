<template>
  <SectionCard title="模型同步任务" description="大批量表同步会自动转入后台任务执行，避免页面长时间等待。">
    <div class="models-toolbar">
      <el-select
        v-model="filters.datasourceType"
        clearable
        placeholder="按数据源类型筛选"
        class="models-toolbar__filter"
        @change="actions.handleDatasourceTypeChange"
      >
        <el-option
          v-for="typeCode in datasourceTypes"
          :key="typeCode"
          :label="typeCode"
          :value="typeCode"
        />
      </el-select>
      <el-select
        v-model="filters.datasourceId"
        clearable
        placeholder="按数据源筛选"
        class="models-toolbar__filter"
        @change="actions.loadSyncTasks"
      >
        <el-option
          v-for="item in datasourceOptions"
          :key="item.id"
          :label="`${item.name} (${item.typeCode})`"
          :value="item.id"
        />
      </el-select>
      <el-select
        v-model="filters.status"
        clearable
        placeholder="按状态筛选"
        class="models-toolbar__filter"
        @change="actions.loadSyncTasks"
      >
        <el-option v-for="status in statusOptions" :key="status" :label="formatStatusLabel(t, status)" :value="status" />
      </el-select>
      <el-button type="primary" :disabled="!currentProjectId" @click="actions.openCreateSyncTaskDialog">新建同步任务</el-button>
      <el-button plain @click="actions.loadSyncTasks">刷新任务</el-button>
    </div>

    <StudioTableShell min-width="1360px">
      <el-table :data="tasks" border v-loading="loading">
        <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
          <template #default="{ $index }">
            {{ (pagination.page - 1) * pagination.pageSize + $index + 1 }}
          </template>
        </el-table-column>
        <el-table-column label="同步名称" min-width="220">
          <template #default="{ row }">
            <el-button link type="primary" @click="actions.openSyncTaskDetail(row)">{{ row.name }}</el-button>
          </template>
        </el-table-column>
        <el-table-column label="同步进度" min-width="220">
          <template #default="{ row }">
            <div class="sync-task-progress">
              <el-progress :percentage="Number(row.progressPercent || 0)" :stroke-width="10" />
              <span class="cell-subtle">
                {{ Number(row.successCount || 0) }}/{{ Number(row.totalCount || 0) }} 成功，失败 {{ Number(row.failedCount || 0) }}，停止 {{ Number(row.stoppedCount || 0) }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="180">
          <template #default="{ row }">
            {{ row.createdAt || t("common.none") }}
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="180">
          <template #default="{ row }">
            {{ row.updatedAt || t("common.none") }}
          </template>
        </el-table-column>
        <el-table-column label="持续时间" min-width="140">
          <template #default="{ row }">
            {{ actions.formatDurationMs(row.durationMs) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="formatStatusLabel(t, row.status)" :tone="toneFromStatus(row.status)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.metadata.actions')" width="150" align="center" header-align="center" fixed="right">
          <template #default="{ row }">
            <div class="sync-task-actions">
              <el-button link type="primary" @click="actions.openSyncTaskDetail(row)">查看</el-button>
              <el-button link type="warning" :disabled="!actions.canStopSyncTask(row)" @click="actions.stopSyncTask(row)">停止</el-button>
              <el-button link type="danger" :disabled="!actions.canDeleteSyncTask(row)" @click="actions.deleteSyncTask(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </StudioTableShell>

    <div class="table-pagination">
      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.pageSize"
        background
        layout="total, sizes, prev, pager, next"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        @current-change="actions.loadSyncTasks"
        @size-change="actions.handlePageSizeChange"
      />
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import type { DataSourceDefinition, EntityId, ModelSyncTaskView } from "@studio/api-sdk";
import { SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { formatStatusLabel, toneFromStatus } from "@/utils/studio";

interface SyncTaskFilters {
  datasourceType: string;
  datasourceId?: EntityId;
  status: string;
}

interface SyncTaskPagination {
  page: number;
  pageSize: number;
}

interface SyncTaskActions {
  handleDatasourceTypeChange: () => void | Promise<void>;
  loadSyncTasks: () => void | Promise<void>;
  openCreateSyncTaskDialog: () => void;
  openSyncTaskDetail: (task: ModelSyncTaskView) => void;
  canStopSyncTask: (task: ModelSyncTaskView) => boolean;
  canDeleteSyncTask: (task: ModelSyncTaskView) => boolean;
  stopSyncTask: (task: ModelSyncTaskView) => void | Promise<void>;
  deleteSyncTask: (task: ModelSyncTaskView) => void | Promise<void>;
  handlePageSizeChange: (pageSize: number) => void | Promise<void>;
  formatDurationMs: (durationMs?: number) => string;
}

defineProps<{
  datasourceTypes: string[];
  datasourceOptions: DataSourceDefinition[];
  statusOptions: string[];
  filters: SyncTaskFilters;
  tasks: ModelSyncTaskView[];
  loading: boolean;
  pagination: SyncTaskPagination;
  total: number;
  currentProjectId?: EntityId | null;
  actions: SyncTaskActions;
}>();

const { t } = useI18n();
</script>

<style scoped>
.models-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.models-toolbar__filter {
  min-width: 280px;
}

.sync-task-progress {
  display: grid;
  gap: 6px;
}

.sync-task-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
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

@media (max-width: 980px) {
  .models-toolbar__filter {
    min-width: 100%;
  }

  .table-pagination {
    justify-content: flex-start;
  }
}
</style>
