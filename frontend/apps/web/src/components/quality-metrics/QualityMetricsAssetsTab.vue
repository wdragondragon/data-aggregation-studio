<template>
  <SectionCard title="资产风险列表" description="资产等价于当前项目中的数据源 + 模型，字段级问题会归属到对应模型。">
    <div class="quality-section-actions">
      <el-checkbox v-model="assetFilters.onlyProblemAssets" @change="assetActions.loadAssets">仅看有问题资产</el-checkbox>
      <el-checkbox v-model="assetFilters.onlyLowCoverageAssets" @change="assetActions.loadAssets">仅看低覆盖资产</el-checkbox>
    </div>
    <StudioTableShell min-width="1300px">
      <el-table :data="pagedAssets" border size="small">
        <el-table-column label="资产名称" min-width="240">
          <template #default="{ row }">
            <div class="table-entity-cell">
              <el-button link type="primary" class="table-entity-cell__title" @click="assetActions.openAssetDrawer(row)">
                {{ assetActions.assetTitle(row) }}
              </el-button>
              <span v-if="row.modelPhysicalLocator && row.modelPhysicalLocator !== assetActions.assetTitle(row)" class="table-entity-cell__meta">
                {{ row.modelPhysicalLocator }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="数据源" min-width="190">
          <template #default="{ row }">{{ row.datasourceName || "-" }} / {{ row.datasourceTypeCode || "-" }}</template>
        </el-table-column>
        <el-table-column label="执行健康分" width="130">
          <template #default="{ row }">
            <el-progress :percentage="assetActions.scorePercent(row.executionHealthScore)" :stroke-width="8" :show-text="false" />
            <span>{{ assetActions.formatNumber(row.executionHealthScore) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="治理风险指数" width="130">
          <template #default="{ row }">
            <el-progress :percentage="assetActions.scorePercent(row.governanceRiskScore)" :stroke-width="8" :show-text="false" status="warning" />
            <span>{{ assetActions.formatNumber(row.governanceRiskScore) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="活跃/超时" width="110" align="right" header-align="right">
          <template #default="{ row }">{{ assetActions.formatNumber(row.activeIssueCount) }} / {{ assetActions.formatNumber(row.overdueIssueCount) }}</template>
        </el-table-column>
        <el-table-column label="覆盖维度" min-width="220">
          <template #default="{ row }">
            <div class="tag-row">
              <el-tag v-for="item in row.coverageDimensions" :key="item" size="small">{{ assetActions.dimensionLabel(item) }}</el-tag>
              <span v-if="!row.coverageDimensions?.length" class="cell-subtle">未覆盖</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最近执行" min-width="180">
          <template #default="{ row }">
            <div class="stack-cell">
              <StatusPill :label="row.latestRunStatus || '无运行'" :tone="assetActions.runStatusTone(row.latestRunStatus)" />
              <span class="cell-subtle">{{ row.latestRunAt || "-" }}</span>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </StudioTableShell>
    <div class="table-pagination">
      <el-pagination
        v-model:current-page="assetPagination.page"
        v-model:page-size="assetPagination.pageSize"
        background
        layout="total, sizes, prev, pager, next"
        :page-sizes="[10, 20, 50, 100]"
        :total="assetTotal"
        @current-change="$emit('page-change')"
        @size-change="$emit('page-size-change')"
      />
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import type { QualityAssetRiskView } from "@studio/api-sdk";
import { SectionCard, StatusPill, StudioTableShell } from "@studio/ui";

type PillTone = "primary" | "success" | "warning" | "danger" | "neutral";

interface AssetFilters {
  onlyProblemAssets: boolean;
  onlyLowCoverageAssets: boolean;
}

interface PaginationState {
  page: number;
  pageSize: number;
}

interface AssetActions {
  loadAssets: () => void | Promise<void>;
  openAssetDrawer: (asset: QualityAssetRiskView) => void | Promise<void>;
  assetTitle: (asset?: Pick<QualityAssetRiskView, "modelName" | "modelPhysicalLocator" | "assetId"> | null) => string;
  scorePercent: (value?: number) => number;
  formatNumber: (value: unknown) => string;
  dimensionLabel: (value?: string | null) => string;
  runStatusTone: (status?: string | null) => PillTone;
}

defineProps<{
  assetFilters: AssetFilters;
  pagedAssets: QualityAssetRiskView[];
  assetPagination: PaginationState;
  assetTotal: number;
  assetActions: AssetActions;
}>();

defineEmits<{
  (event: "page-change"): void;
  (event: "page-size-change"): void;
}>();
</script>

<style scoped>
.quality-section-actions {
  display: flex;
  justify-content: flex-start;
  gap: 10px;
  margin: 0 0 12px;
}

.table-entity-cell {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.table-entity-cell__title {
  min-width: 0;
  justify-content: flex-start;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-entity-cell__meta,
.cell-subtle {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.stack-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
