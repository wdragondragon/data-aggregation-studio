<template>
  <section class="browser-panel" :aria-label="title">
    <header class="browser-panel__header">
      <strong>{{ title }}</strong>
      <span>{{ selectedCount }} 项已选</span>
    </header>

    <div class="browser-panel__selectors">
      <el-select
        v-if="showRuntimeClusterSelector"
        :model-value="runtimeClusterId"
        placeholder="运行集群"
        filterable
        @update:model-value="emit('update:runtimeClusterId', $event)"
      >
        <el-option
          v-for="cluster in runtimeClusters"
          :key="String(cluster.id)"
          :label="cluster.name || cluster.code || `#${cluster.id}`"
          :value="cluster.id"
          :disabled="cluster.enabled === false"
        />
      </el-select>
      <el-select
        :model-value="datasourceId"
        placeholder="文件数据源"
        filterable
        clearable
        :disabled="runtimeClusterId == null || runtimeClusterId === ''"
        @update:model-value="emit('update:datasourceId', $event)"
      >
        <el-option
          v-for="datasource in datasources"
          :key="String(datasource.id)"
          :label="`${datasource.name} (${datasource.typeCode})`"
          :value="datasource.id"
          :disabled="datasource.enabled === false || datasource.executable === false"
        />
      </el-select>
    </div>

    <div class="browser-panel__pathbar">
      <el-tooltip content="返回上级" placement="top">
        <el-button :icon="Back" circle :disabled="path === '/' || loading" aria-label="返回上级" @click="emit('parent')" />
      </el-tooltip>
      <el-tooltip content="刷新目录" placement="top">
        <el-button :icon="Refresh" circle :loading="loading" aria-label="刷新目录" @click="emit('refresh')" />
      </el-tooltip>
      <el-input
        :model-value="path"
        class="browser-panel__path-input studio-mono"
        aria-label="当前路径"
        @update:model-value="draftPath = $event"
        @keyup.enter="emit('browse', draftPath || path)"
        @focus="draftPath = path"
      />
    </div>

    <nav class="browser-panel__breadcrumbs" aria-label="路径面包屑">
      <el-button link type="primary" @click="emit('browse', '/')">根目录</el-button>
      <template v-for="segment in pathSegments" :key="segment.path">
        <span>/</span>
        <el-button link type="primary" :title="segment.path" @click="emit('browse', segment.path)">{{ segment.label }}</el-button>
      </template>
    </nav>

    <el-table
      :data="entries"
      v-loading="loading"
      row-key="path"
      size="small"
      table-layout="fixed"
      height="330"
      highlight-current-row
      empty-text="当前目录为空"
      @selection-change="emit('selection-change', $event)"
      @row-dblclick="handleRowDoubleClick"
    >
      <el-table-column type="selection" width="42" />
      <el-table-column label="名称" min-width="210" sortable>
        <template #default="{ row }">
          <div class="browser-panel__name-cell">
            <el-icon :class="row.directory ? 'is-directory' : ''">
              <Folder v-if="row.directory" />
              <Document v-else />
            </el-icon>
            <span :title="row.name || row.path">{{ row.name || row.path }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="96" align="right" header-align="right">
        <template #default="{ row }">{{ row.directory ? '--' : formatBytes(row.size) }}</template>
      </el-table-column>
      <el-table-column label="修改时间" width="164">
        <template #default="{ row }">{{ formatEntryModifiedAt(row) }}</template>
      </el-table-column>
    </el-table>

    <footer class="browser-panel__footer">
      <span>第 {{ pageNumber }} 页</span>
      <div>
        <el-tooltip content="上一页" placement="top">
          <el-button :icon="ArrowLeft" circle :disabled="pageNumber <= 1 || loading" aria-label="上一页" @click="emit('previous-page')" />
        </el-tooltip>
        <el-tooltip content="下一页" placement="top">
          <el-button :icon="ArrowRight" circle :disabled="!hasMore || loading" aria-label="下一页" @click="emit('next-page')" />
        </el-tooltip>
      </div>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { ArrowLeft, ArrowRight, Back, Document, Folder, Refresh } from "@element-plus/icons-vue";
import type {
  DataSourceOptionView,
  EntityId,
  FileTransferFileEntryView,
  RuntimeClusterView,
} from "@studio/api-sdk";
import {
  formatBytes,
  formatEntryModifiedAt,
  transferPathSegments,
} from "@/utils/fileTransfer";

const props = defineProps<{
  title: string;
  runtimeClusterId?: EntityId | "";
  datasourceId?: EntityId | "";
  runtimeClusters: RuntimeClusterView[];
  showRuntimeClusterSelector?: boolean;
  datasources: DataSourceOptionView[];
  path: string;
  entries: FileTransferFileEntryView[];
  loading?: boolean;
  hasMore?: boolean;
  pageNumber: number;
  selectedCount: number;
}>();

const emit = defineEmits<{
  "update:runtimeClusterId": [value?: EntityId];
  "update:datasourceId": [value?: EntityId];
  browse: [path: string];
  parent: [];
  refresh: [];
  "previous-page": [];
  "next-page": [];
  "selection-change": [rows: FileTransferFileEntryView[]];
}>();

const draftPath = ref(props.path);
const pathSegments = computed(() => transferPathSegments(props.path));

function handleRowDoubleClick(row: FileTransferFileEntryView) {
  if (row.directory) emit("browse", row.path);
}
</script>

<style scoped>
.browser-panel {
  display: grid;
  grid-template-rows: auto auto auto auto minmax(0, 1fr) auto;
  gap: 10px;
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: var(--studio-surface-strong);
}

.browser-panel__header,
.browser-panel__footer,
.browser-panel__pathbar,
.browser-panel__selectors {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.browser-panel__header,
.browser-panel__footer {
  justify-content: space-between;
}

.browser-panel__header span,
.browser-panel__footer span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.browser-panel__selectors > * {
  flex: 1 1 0;
  min-width: 0;
}

.browser-panel__path-input {
  min-width: 0;
}

.browser-panel__breadcrumbs {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 28px;
  overflow-x: auto;
  white-space: nowrap;
}

.browser-panel__breadcrumbs .el-button {
  flex: 0 0 auto;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.browser-panel__name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.browser-panel__name-cell .el-icon {
  flex: 0 0 auto;
  color: var(--studio-text-soft);
}

.browser-panel__name-cell .el-icon.is-directory {
  color: var(--studio-warning);
}

.browser-panel__name-cell span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.browser-panel__footer > div {
  display: flex;
  gap: 8px;
}

@media (max-width: 600px) {
  .browser-panel {
    padding: 10px;
  }

  .browser-panel__selectors {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
