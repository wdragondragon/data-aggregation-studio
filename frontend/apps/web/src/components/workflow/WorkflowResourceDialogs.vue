<template>
  <el-dialog
    v-model="collectionDialogModel"
    :title="t('web.workflows.collectionTaskDialogTitle')"
    width="960px"
    destroy-on-close
    class="workflow-resource-dialog"
  >
    <div class="resource-dialog-toolbar">
      <el-input
        v-model="collectionKeywordModel"
        clearable
        :placeholder="t('web.workflows.searchCollectionTaskPlaceholder')"
        @input="collectionTaskPagination.page = 1"
      />
      <el-button plain :loading="collectionTasksLoading" @click="actions.ensureCollectionTasksLoaded(true)">
        {{ t("common.refresh") }}
      </el-button>
    </div>
    <el-table
      v-loading="collectionTasksLoading"
      :data="pagedCollectionTasks"
      border
      size="small"
      table-layout="fixed"
      scrollbar-always-on
      height="420"
      highlight-current-row
      class="workflow-resource-table"
      @row-click="pendingCollectionTaskIdModel = String($event.id)"
    >
      <el-table-column width="52" align="center">
        <template #default="{ row }">
          <el-radio :model-value="pendingCollectionTaskId" :value="String(row.id)" @change="pendingCollectionTaskIdModel = String(row.id)" />
        </template>
      </el-table-column>
      <el-table-column :label="t('common.sequence')" width="76" align="center">
        <template #default="{ $index }">
          {{ actions.getDialogRowIndex(collectionTaskPagination, $index) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('web.workflows.collectionTaskNameColumn')" min-width="260">
        <template #default="{ row }">
          <span class="resource-task-name">{{ row.name || t("common.none") }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('web.workflows.collectionTaskTypeColumn')" min-width="120">
        <template #default="{ row }">
          {{ formatCollectionTaskType(t, row.taskType) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('web.workflows.sourceCountColumn')" width="110" align="center">
        <template #default="{ row }">
          {{ row.sourceCount ?? 0 }}
        </template>
      </el-table-column>
      <el-table-column :label="t('web.workflows.updatedAtColumn')" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          {{ actions.formatNullableText(row.updatedAt) }}
        </template>
      </el-table-column>
    </el-table>
    <div class="resource-dialog-pagination">
      <el-pagination
        v-model:current-page="collectionTaskPagination.page"
        v-model:page-size="collectionTaskPagination.pageSize"
        :total="filteredCollectionTasks.length"
        :page-sizes="[8, 10, 20, 50]"
        layout="total, sizes, prev, pager, next"
      />
    </div>
    <template #footer>
      <el-button @click="collectionDialogModel = false">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :disabled="!pendingCollectionTaskId" @click="actions.confirmCollectionTaskSelection">
        {{ t("common.confirm") }}
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="qualityDialogModel"
    :title="t('web.workflows.qualityTaskDialogTitle')"
    width="1080px"
    destroy-on-close
    class="workflow-resource-dialog"
  >
    <div class="resource-dialog-toolbar">
      <el-input
        v-model="qualityKeywordModel"
        clearable
        :placeholder="t('web.workflows.searchQualityTaskPlaceholder')"
        @input="actions.handleQualityTaskKeywordInput"
      />
      <el-button plain :loading="qualityTasksLoading" @click="actions.loadQualityTasks">
        {{ t("common.refresh") }}
      </el-button>
    </div>
    <el-table
      v-loading="qualityTasksLoading"
      :data="onlineQualityTasks"
      border
      size="small"
      table-layout="fixed"
      scrollbar-always-on
      height="420"
      highlight-current-row
      class="workflow-resource-table"
      @row-click="pendingQualityTaskIdModel = String($event.id)"
    >
      <el-table-column width="52" align="center">
        <template #default="{ row }">
          <el-radio :model-value="pendingQualityTaskId" :value="String(row.id)" @change="pendingQualityTaskIdModel = String(row.id)" />
        </template>
      </el-table-column>
      <el-table-column :label="t('common.sequence')" width="76" align="center">
        <template #default="{ $index }">
          {{ actions.getDialogRowIndex(qualityTaskPagination, $index) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('web.workflows.qualityTaskNameColumn')" min-width="260">
        <template #default="{ row }">
          <span class="resource-task-name">{{ row.taskName || t("common.none") }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('web.workflows.qualityTaskRuleSummary')" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.ruleName || t("common.none") }} · {{ actions.formatQualityDimension(row.ruleDimension) }} · {{ actions.formatQualityGranularity(row.granularity) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('web.workflows.qualityTaskScope')" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.datasourceName || t("common.none") }} · {{ row.modelName || t("common.none") }} · {{ row.columnName || (row.granularity === "COLUMN" ? t("common.none") : t("web.workflows.qualityTaskWholeTable")) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('web.workflows.updatedAtColumn')" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          {{ actions.formatNullableText(row.updatedAt) }}
        </template>
      </el-table-column>
    </el-table>
    <div class="resource-dialog-pagination">
      <el-pagination
        v-model:current-page="qualityTaskPagination.page"
        v-model:page-size="qualityTaskPagination.pageSize"
        :total="qualityTaskTotal"
        :page-sizes="[8, 10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="actions.handleQualityTaskPageChange"
        @size-change="actions.handleQualityTaskPageSizeChange"
      />
    </div>
    <template #footer>
      <el-button @click="qualityDialogModel = false">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :disabled="!pendingQualityTaskId" @click="actions.confirmQualityTaskSelection">
        {{ t("common.confirm") }}
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="scriptDialogModel"
    :title="t('web.workflows.dataScriptDialogTitle')"
    width="1180px"
    destroy-on-close
    class="workflow-resource-dialog"
  >
    <div class="script-selector-layout">
      <div class="script-selector-tree soft-panel">
        <div class="script-selector-tree__header">
          <strong>{{ t("web.workflows.scriptDirectoryTitle") }}</strong>
          <el-button size="small" plain :loading="scriptTreeLoading" @click="actions.ensureScriptTreeLoaded(true)">
            {{ t("common.refresh") }}
          </el-button>
        </div>
        <el-input
          v-model="scriptKeywordModel"
          clearable
          :placeholder="t('web.workflows.searchDataScriptPlaceholder')"
        />
        <el-tree
          v-loading="scriptTreeLoading"
          class="script-selector-tree__body"
          :data="filteredScriptTreeData"
          node-key="nodeKey"
          :props="{ label: 'name', children: 'children' }"
          default-expand-all
          highlight-current
          @node-click="actions.handleScriptTreeClick"
        >
          <template #default="{ data }">
            <div class="script-tree-node" :class="{ 'is-selected': String(data.scriptId ?? '') === pendingScriptId }">
              <span>{{ data.name }}</span>
              <el-tag v-if="data.nodeType === 'SCRIPT' && data.scriptType" size="small" effect="plain">
                {{ formatScriptType(t, data.scriptType) }}
              </el-tag>
            </div>
          </template>
        </el-tree>
      </div>
      <div class="script-selector-preview">
        <template v-if="previewScript">
          <div class="soft-panel script-preview-meta">
            <div>
              <strong>{{ previewScript.fileName }}</strong>
              <p>
                {{ formatScriptType(t, previewScript.scriptType) }}
                · {{ previewScript.datasourceName || t("common.none") }}
              </p>
            </div>
            <el-tag effect="plain">{{ t("web.workflows.scriptPreviewReadonly") }}</el-tag>
          </div>
          <ScriptEditorPanel
            v-model="previewScriptContentModel"
            :script-type="previewScript.scriptType"
            :placeholder="t('web.dataDevelopment.contentPlaceholder')"
            readonly
          />
        </template>
        <div v-else class="soft-panel script-preview-empty" v-loading="scriptPreviewLoading">
          {{ t("web.workflows.scriptPreviewEmpty") }}
        </div>
      </div>
    </div>
    <template #footer>
      <el-button @click="scriptDialogModel = false">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :disabled="!pendingScriptId" @click="actions.confirmScriptSelection">
        {{ t("common.confirm") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type {
  DataDevelopmentScript,
  DataDevelopmentTreeNode,
  CollectionTaskListView,
  QualityTaskWorkflowOptionView,
} from "@studio/api-sdk";
import ScriptEditorPanel from "@web/components/data-development/ScriptEditorPanel.vue";
import { formatCollectionTaskType, formatScriptType } from "@/utils/studio";

interface DialogPagination {
  page: number;
  pageSize: number;
}

interface WorkflowResourceDialogActions {
  ensureCollectionTasksLoaded: (force?: boolean) => void | Promise<void>;
  getDialogRowIndex: (pagination: DialogPagination, index: number) => number;
  confirmCollectionTaskSelection: () => void;
  handleQualityTaskKeywordInput: () => void;
  loadQualityTasks: () => void | Promise<void>;
  handleQualityTaskPageChange: (page: number) => void;
  handleQualityTaskPageSizeChange: (pageSize: number) => void;
  confirmQualityTaskSelection: () => void;
  ensureScriptTreeLoaded: (force?: boolean) => void | Promise<void>;
  handleScriptTreeClick: (node: DataDevelopmentTreeNode) => void | Promise<void>;
  confirmScriptSelection: () => void | Promise<void>;
  formatNullableText: (value: unknown) => string;
  formatQualityDimension: (value?: string | null) => string;
  formatQualityGranularity: (value?: string | null) => string;
}

const props = defineProps<{
  collectionTaskDialogVisible: boolean;
  qualityTaskDialogVisible: boolean;
  scriptDialogVisible: boolean;
  collectionTaskKeyword: string;
  qualityTaskKeyword: string;
  scriptKeyword: string;
  pendingCollectionTaskId?: string;
  pendingQualityTaskId?: string;
  pendingScriptId?: string;
  previewScriptContent: string;
  collectionTasksLoading: boolean;
  qualityTasksLoading: boolean;
  scriptTreeLoading: boolean;
  scriptPreviewLoading: boolean;
  pagedCollectionTasks: CollectionTaskListView[];
  filteredCollectionTasks: CollectionTaskListView[];
  collectionTaskPagination: DialogPagination;
  onlineQualityTasks: QualityTaskWorkflowOptionView[];
  qualityTaskPagination: DialogPagination;
  qualityTaskTotal: number;
  filteredScriptTreeData: DataDevelopmentTreeNode[];
  previewScript: DataDevelopmentScript | null;
  actions: WorkflowResourceDialogActions;
}>();

const emit = defineEmits<{
  "update:collectionTaskDialogVisible": [value: boolean];
  "update:qualityTaskDialogVisible": [value: boolean];
  "update:scriptDialogVisible": [value: boolean];
  "update:collectionTaskKeyword": [value: string];
  "update:qualityTaskKeyword": [value: string];
  "update:scriptKeyword": [value: string];
  "update:pendingCollectionTaskId": [value?: string];
  "update:pendingQualityTaskId": [value?: string];
  "update:pendingScriptId": [value?: string];
  "update:previewScriptContent": [value: string];
}>();

const { t } = useI18n();

const collectionDialogModel = computed({
  get: () => props.collectionTaskDialogVisible,
  set: (value: boolean) => emit("update:collectionTaskDialogVisible", value),
});
const qualityDialogModel = computed({
  get: () => props.qualityTaskDialogVisible,
  set: (value: boolean) => emit("update:qualityTaskDialogVisible", value),
});
const scriptDialogModel = computed({
  get: () => props.scriptDialogVisible,
  set: (value: boolean) => emit("update:scriptDialogVisible", value),
});
const collectionKeywordModel = computed({
  get: () => props.collectionTaskKeyword,
  set: (value: string) => emit("update:collectionTaskKeyword", value),
});
const qualityKeywordModel = computed({
  get: () => props.qualityTaskKeyword,
  set: (value: string) => emit("update:qualityTaskKeyword", value),
});
const scriptKeywordModel = computed({
  get: () => props.scriptKeyword,
  set: (value: string) => emit("update:scriptKeyword", value),
});
const pendingCollectionTaskIdModel = computed({
  get: () => props.pendingCollectionTaskId,
  set: (value?: string) => emit("update:pendingCollectionTaskId", value),
});
const pendingQualityTaskIdModel = computed({
  get: () => props.pendingQualityTaskId,
  set: (value?: string) => emit("update:pendingQualityTaskId", value),
});
const previewScriptContentModel = computed({
  get: () => props.previewScriptContent,
  set: (value: string) => emit("update:previewScriptContent", value),
});
</script>

<style scoped>
.resource-dialog-toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) auto;
  gap: 12px;
  margin-bottom: 12px;
}

.resource-dialog-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.workflow-resource-table :deep(.cell) {
  white-space: normal;
}

.resource-task-name {
  display: inline-block;
  max-width: 100%;
  overflow-wrap: anywhere;
  line-height: 1.35;
}

.script-selector-layout {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  min-height: 560px;
}

.script-selector-tree {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 12px;
  min-height: 0;
}

.script-selector-tree__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.script-selector-tree__body {
  min-height: 0;
  max-height: 500px;
  overflow: auto;
}

.script-tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-width: 0;
}

.script-tree-node span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.script-selector-preview {
  min-width: 0;
}

.script-preview-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.script-preview-empty {
  display: grid;
  min-height: 520px;
  place-items: center;
  text-align: center;
}

@media (max-width: 1080px) {
  .script-selector-layout {
    grid-template-columns: 1fr;
  }
}
</style>
