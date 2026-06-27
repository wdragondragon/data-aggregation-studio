<template>
  <SectionCard title="问题列表" description="同一问题签名会聚合为一条记录，状态处置和评论在右侧抽屉中完成。">
    <StudioTableShell min-width="1720px">
      <el-table :data="pagedIssues" border size="small" table-layout="auto">
        <el-table-column label="问题编号" min-width="140">
          <template #default="{ row }">
            <el-button link type="primary" @click="issueActions.openIssueDrawer(row)">{{ row.issueCode || row.id || "-" }}</el-button>
          </template>
        </el-table-column>
        <el-table-column label="严重级别" width="112" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="issueActions.severityLabel(row.severity)" :tone="issueActions.severityTone(row.severity)" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="132" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="issueActions.issueStatusLabel(row.status)" :tone="issueActions.issueStatusTone(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="资产" min-width="220">
          <template #default="{ row }">
            <div class="table-entity-cell">
              <el-button v-if="row.assetId" link type="primary" class="table-entity-cell__title" @click="issueActions.openAssetByIssue(row)">
                {{ row.modelName || "-" }}
              </el-button>
              <span v-else class="table-entity-cell__title">{{ row.modelName || "-" }}</span>
              <span class="table-entity-cell__meta">{{ row.datasourceName || "-" }} · {{ row.columnName || "全表" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="规则 / 任务" min-width="240">
          <template #default="{ row }">
            <div class="table-entity-cell">
              <span class="table-entity-cell__title">{{ row.ruleName || "-" }}</span>
              <span class="table-entity-cell__meta">{{ row.qualityTaskName || "-" }} · {{ issueActions.dimensionLabel(row.ruleDimension) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="首次 / 最近" min-width="200">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ row.firstSeenAt || "-" }}</span>
              <span class="cell-subtle">{{ row.lastSeenAt || "-" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="负责人 / SLA" min-width="180">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ row.assigneeName || "未认领" }}</span>
              <span class="cell-subtle" :class="{ danger: row.overdue }">{{ row.slaDueAt || "-" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最近证据" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <MessagePreviewText :text="row.latestMessage" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="center" header-align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="issueActions.openIssueDrawer(row)">处置</el-button>
            <el-button link type="primary" :disabled="!row.lastRunRecordId" @click="issueActions.openRunLog(row.lastRunRecordId)">日志</el-button>
          </template>
        </el-table-column>
      </el-table>
    </StudioTableShell>
    <div class="table-pagination">
      <el-pagination
        v-model:current-page="issuePagination.page"
        v-model:page-size="issuePagination.pageSize"
        background
        layout="total, sizes, prev, pager, next"
        :page-sizes="[10, 20, 50, 100]"
        :total="issueTotal"
        @current-change="emit('page-change')"
        @size-change="emit('page-size-change')"
      />
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import type {
  EntityId,
  QualityIssueSeverity,
  QualityIssueStatus,
  QualityIssueView,
} from "@studio/api-sdk";
import { SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import MessagePreviewText from "@/components/MessagePreviewText.vue";

type PillTone = "primary" | "success" | "warning" | "danger" | "neutral";

interface PaginationState {
  page: number;
  pageSize: number;
}

interface IssueActions {
  openIssueDrawer: (issue: QualityIssueView) => void | Promise<void>;
  openAssetByIssue: (issue: QualityIssueView) => void | Promise<void>;
  openRunLog: (runRecordId?: EntityId | null) => void;
  severityLabel: (severity?: QualityIssueSeverity | null) => string;
  severityTone: (severity?: QualityIssueSeverity | null) => PillTone;
  issueStatusLabel: (status?: QualityIssueStatus | null) => string;
  issueStatusTone: (status?: QualityIssueStatus | null) => PillTone;
  dimensionLabel: (value?: string | null) => string;
}

defineProps<{
  pagedIssues: QualityIssueView[];
  issuePagination: PaginationState;
  issueTotal: number;
  issueActions: IssueActions;
}>();

const emit = defineEmits<{
  "page-change": [];
  "page-size-change": [];
}>();
</script>

<style scoped>
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

.cell-subtle.danger {
  color: var(--el-color-danger);
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
