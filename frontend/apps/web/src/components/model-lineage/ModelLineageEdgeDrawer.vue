<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('web.models.lineageEdgeDetailTitle')"
    size="46%"
    @close="emit('update:modelValue', false)"
  >
    <div v-loading="edgeLoading" class="lineage-edge-drawer">
      <template v-if="edgeDetail">
        <div class="lineage-edge-detail-grid soft-panel">
          <div>
            <strong>{{ t("web.models.lineageSourceNode") }}:</strong>
            {{ edgeDetail.sourceNodeTitle || t("common.none") }}
          </div>
          <div>
            <strong>{{ t("web.models.lineageTargetNode") }}:</strong>
            {{ edgeDetail.targetNodeTitle || t("common.none") }}
          </div>
          <div>
            <strong>{{ t("web.models.lineageCreationType") }}:</strong>
            {{ edgeActions.formatSourceTypeLabel(edgeDetail.sourceTypeLabel || edgeDetail.sourceType) }}
          </div>
          <div class="lineage-edge-detail-grid__status">
            <strong>{{ t("web.models.lineageLatestRun") }}:</strong>
            <StatusPill :label="edgeActions.formatDisplayStatus(edgeDetail.displayStatus ?? edgeDetail.latestRunStatus)" :tone="edgeActions.displayStatusTone(edgeDetail.displayStatus ?? edgeDetail.latestRunStatus)" />
          </div>
          <div v-if="activeLevel === 'FIELD'">
            <strong>{{ t("web.models.lineageSourceField") }}:</strong>
            {{ edgeDetail.sourceField || t("common.none") }}
          </div>
          <div v-if="activeLevel === 'FIELD'">
            <strong>{{ t("web.models.lineageTargetField") }}:</strong>
            {{ edgeDetail.targetField || t("common.none") }}
          </div>
          <div>
            <strong>{{ t("web.models.lineageContributorTime") }}:</strong>
            {{ edgeDetail.latestRunAt || t("web.models.lineageNoRun") }}
          </div>
        </div>

        <SectionCard
          :title="t('web.models.lineageContributorsTitle')"
          :description="t('web.models.lineageContributorsDescription')"
        >
          <div class="lineage-contributors">
            <div
              v-for="(contributor, index) in edgeDetail.contributors"
              :key="`${contributor.relationId ?? contributor.collectionTaskId ?? 'item'}-${index}`"
              class="soft-panel lineage-contributor-card"
            >
              <div class="lineage-contributor-card__header">
                <div>
                  <strong>{{ contributor.collectionTaskName || edgeActions.formatManualContributorTitle(contributor) }}</strong>
                  <p>{{ edgeActions.formatSourceTypeLabel(contributor.sourceTypeLabel || contributor.sourceType) }}</p>
                </div>
                <StatusPill
                  :label="edgeActions.formatDisplayStatus(contributor.displayStatus ?? contributor.latestRunStatus)"
                  :tone="edgeActions.displayStatusTone(contributor.displayStatus ?? contributor.latestRunStatus)"
                />
              </div>
              <div class="lineage-contributor-card__body">
                <div>
                  <span>{{ t("web.models.lineageContributorTime") }}</span>
                  <strong>{{ contributor.latestRunAt || contributor.updatedAt || t("web.models.lineageNoRun") }}</strong>
                </div>
                <div>
                  <span>{{ t("web.models.lineageContributorMappingMode") }}</span>
                  <strong>{{ contributor.mappingMode || t("common.none") }}</strong>
                </div>
                <div v-if="contributor.expression">
                  <span>{{ t("web.models.lineageContributorExpression") }}</span>
                  <strong>{{ contributor.expression }}</strong>
                </div>
                <div v-if="contributor.maintainer">
                  <span>{{ t("web.models.lineageMaintainer") }}</span>
                  <strong>{{ contributor.maintainer }}</strong>
                </div>
              </div>
              <div class="lineage-contributor-card__actions">
                <el-button v-if="contributor.taskPath" link type="primary" @click="edgeActions.jumpToPath(contributor.taskPath)">
                  {{ t("web.models.lineageOpenTask") }}
                </el-button>
                <el-button v-if="contributor.runPath" link type="primary" @click="edgeActions.jumpToPath(contributor.runPath)">
                  {{ t("web.models.lineageOpenRun") }}
                </el-button>
                <el-button v-if="contributor.editable" link type="primary" @click="edgeActions.openEditManualRelation(contributor)">
                  {{ t("common.edit") }}
                </el-button>
                <el-button
                  v-if="contributor.editable && contributor.relationId != null"
                  link
                  type="danger"
                  @click="edgeActions.deleteManualRelation(contributor)"
                >
                  {{ t("common.delete") }}
                </el-button>
              </div>
            </div>
          </div>
        </SectionCard>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import type { DataModelLineageContributorView, DataModelLineageEdgeDetailView, DataModelLineageLevel } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";

interface EdgeDrawerActions {
  formatSourceTypeLabel: (value?: string) => string;
  formatDisplayStatus: (value?: string) => string;
  displayStatusTone: (value?: string) => "success" | "warning" | "danger" | "neutral";
  formatManualContributorTitle: (contributor: DataModelLineageContributorView) => string;
  jumpToPath: (path: string) => void | Promise<void>;
  openEditManualRelation: (contributor: DataModelLineageContributorView) => void | Promise<void>;
  deleteManualRelation: (contributor: DataModelLineageContributorView) => void | Promise<void>;
}

defineProps<{
  modelValue: boolean;
  edgeLoading: boolean;
  edgeDetail?: DataModelLineageEdgeDetailView;
  activeLevel: DataModelLineageLevel;
  edgeActions: EdgeDrawerActions;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
}>();

const { t } = useI18n();
</script>

<style scoped>
p {
  margin: 0;
  color: var(--studio-text-soft);
}

.lineage-edge-drawer {
  min-height: 320px;
  display: grid;
  gap: 14px;
}

.lineage-edge-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
}

.lineage-edge-detail-grid__status {
  display: flex;
  align-items: center;
  gap: 8px;
}

.lineage-contributors {
  display: grid;
  gap: 12px;
}

.lineage-contributor-card {
  display: grid;
  gap: 12px;
}

.lineage-contributor-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.lineage-contributor-card__header p {
  margin-top: 4px;
}

.lineage-contributor-card__body {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 12px;
}

.lineage-contributor-card__body div {
  display: grid;
  gap: 3px;
}

.lineage-contributor-card__body span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.lineage-contributor-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

@media (max-width: 960px) {
  .lineage-edge-detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
