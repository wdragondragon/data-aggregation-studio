<template>
  <SectionCard :title="t('web.models.tableTitle')" :description="t('web.models.tableDescription')">
    <div class="models-toolbar">
      <el-select
        v-model="datasourceTypeModel"
        clearable
        :placeholder="t('web.models.datasourceTypePlaceholder')"
        class="models-toolbar__filter"
        @change="actions.handleDatasourceTypeChange"
      >
        <el-option
          v-for="typeCode in queryDatasourceTypes"
          :key="typeCode"
          :label="typeCode"
          :value="typeCode"
        />
      </el-select>
      <el-select
        v-model="datasourceIdModel"
        clearable
        :placeholder="t('web.models.datasourcePlaceholder')"
        class="models-toolbar__filter"
        @change="actions.handleDatasourceChange"
      >
        <el-option
          v-for="item in datasourceOptions"
          :key="item.id"
          :label="`${item.name} (${item.typeCode})`"
          :value="item.id"
        />
      </el-select>
      <el-button type="primary" :disabled="!currentProjectId" @click="actions.openCreateDialog">{{ t("common.newModel") }}</el-button>
      <el-button plain :disabled="!currentProjectId" @click="actions.openSyncDialog">{{ t("common.sync") }}</el-button>
      <el-button plain @click="actions.rebuildQueryIndex">{{ t("common.rebuild") }}</el-button>
      <el-button plain @click="actions.openStatisticsWorkspace">{{ t("common.statistics") }}</el-button>
      <el-button plain @click="actions.refreshModels">{{ t("common.refresh") }}</el-button>
    </div>

    <ModelDynamicFilterPanel
      :groups="queryGroups"
      :active-query-datasource-type="activeQueryDatasourceType"
      :query-schema-options="querySchemaOptions"
      :actions="dynamicFilterActions"
    />

    <StudioTableShell min-width="1120px">
      <el-table
        :data="models"
        border
        :empty-text="emptyText"
        :default-sort="sortState"
        @sort-change="actions.handleModelSortChange"
      >
        <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
          <template #default="{ $index }">
            {{ getPaginatedRowNumber(pagination, $index) }}
          </template>
        </el-table-column>
        <el-table-column prop="name" :label="t('web.models.modelName')" min-width="280" sortable="custom">
          <template #default="{ row }">
            <div class="model-name-cell">
              <el-button
                link
                type="primary"
                class="model-name-link"
                :title="row.name || '-'"
                @click.stop="actions.openModelDetail(row)"
              >
                {{ row.name || "-" }}
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.models.datasourceLabel')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ actions.resolveDatasourceLabel(row.datasourceId) }}
          </template>
        </el-table-column>
        <el-table-column prop="projectId" label="所属项目" min-width="170" sortable="custom">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ actions.resolveProjectLabel(row.projectId) }}</span>
              <span class="cell-subtle">{{ actions.isSharedModel(row) ? "共享来源" : "当前项目" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.models.kind')" width="120" align="center" header-align="center">
          <template #default="{ row }">
            {{ formatModelKind(t, row.modelKind) }}
          </template>
        </el-table-column>
        <el-table-column prop="physicalLocator" :label="t('web.models.physicalLocator')" min-width="220" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" min-width="180" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="updatedAt" label="更新时间" min-width="180" sortable="custom" show-overflow-tooltip />
        <el-table-column :label="t('web.metadata.actions')" width="150" align="center" header-align="center" fixed="right">
          <template #default="{ row }">
            <OverflowActionGroup :items="actions.buildModelActions(row)" />
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
        :total="pagination.total"
        @current-change="actions.handleModelPageChange"
        @size-change="actions.handleModelPageSizeChange"
      />
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { DataModelListView, DataSourceOptionView, EntityId, MetadataSchemaDefinition } from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StudioTableShell } from "@studio/ui";
import type { OverflowActionItem } from "@studio/ui";
import { getPaginatedRowNumber } from "@/composables/useClientPagination";
import { formatModelKind } from "@/utils/studio";
import ModelDynamicFilterPanel from "./ModelDynamicFilterPanel.vue";
import type { ModelDynamicFilterActions, ModelQueryGroupState } from "./modelViewTypes";

interface ModelListPagination {
  page: number;
  pageSize: number;
  total: number;
}

interface ModelSortState {
  prop: string;
  order: "ascending" | "descending";
}

interface ModelSortChange {
  prop?: string;
  order?: "ascending" | "descending" | null;
}

interface ModelListActions {
  handleDatasourceTypeChange: () => void | Promise<void>;
  handleDatasourceChange: () => void | Promise<void>;
  openCreateDialog: () => void;
  openSyncDialog: () => void;
  rebuildQueryIndex: () => void | Promise<void>;
  openStatisticsWorkspace: () => void;
  refreshModels: () => void | Promise<void>;
  openModelDetail: (model: DataModelListView) => void;
  resolveDatasourceLabel: (datasourceId?: EntityId) => string;
  resolveProjectLabel: (projectId?: EntityId | null) => string;
  isSharedModel: (model: DataModelListView) => boolean;
  buildModelActions: (model: DataModelListView) => OverflowActionItem[];
  handleModelPageChange: (page: number) => void | Promise<void>;
  handleModelPageSizeChange: (pageSize: number) => void | Promise<void>;
  handleModelSortChange: (sort: ModelSortChange) => void | Promise<void>;
}

const props = defineProps<{
  datasourceType: string;
  datasourceId?: EntityId;
  queryDatasourceTypes: string[];
  datasourceOptions: DataSourceOptionView[];
  currentProjectId?: EntityId | null;
  queryGroups: ModelQueryGroupState[];
  activeQueryDatasourceType: string;
  querySchemaOptions: MetadataSchemaDefinition[];
  dynamicFilterActions: ModelDynamicFilterActions;
  models: DataModelListView[];
  pagination: ModelListPagination;
  sortState: ModelSortState;
  emptyText?: string;
  actions: ModelListActions;
}>();

const emit = defineEmits<{
  "update:datasourceType": [value: string];
  "update:datasourceId": [value?: EntityId];
}>();

const { t } = useI18n();

const datasourceTypeModel = computed({
  get: () => props.datasourceType,
  set: (value?: string) => emit("update:datasourceType", value ?? ""),
});

const datasourceIdModel = computed({
  get: () => props.datasourceId,
  set: (value?: EntityId) => emit("update:datasourceId", value),
});
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

.model-name-cell {
  display: flex;
  align-items: flex-start;
  width: 100%;
  min-width: 0;
}

.model-name-link {
  justify-content: flex-start;
  height: auto;
  min-height: auto;
  max-width: 100%;
  padding: 0;
  line-height: 1.45;
  text-align: left;
  white-space: normal;
}

.model-name-link :deep(span) {
  display: inline;
  min-width: 0;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.stack-cell {
  display: grid;
  gap: 4px;
  min-width: 0;
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
