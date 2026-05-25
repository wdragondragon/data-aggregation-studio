<template>
  <div class="detail-toolbar">
    <el-button plain @click="actions.goBackToList">{{ t("common.backToList") }}</el-button>
    <div class="detail-toolbar__actions">
      <el-button plain :disabled="!model?.datasourceId" @click="actions.openStatisticsWorkspace(model?.datasourceId)">{{ t("common.statistics") }}</el-button>
      <el-button plain @click="actions.refreshDetail">{{ t("common.refresh") }}</el-button>
      <el-button type="primary" :disabled="!model || shared" @click="actions.openDetailEdit">{{ t("common.edit") }}</el-button>
    </div>
  </div>

  <el-tabs :model-value="activeTab" class="model-detail-tabs" @update:model-value="emit('update:activeTab', String($event))">
    <el-tab-pane :label="t('web.models.detailTabOverview')" name="overview">
      <ModelDetailOverview
        :model="model"
        :shared="shared"
        :sections="sections"
        :preview-rows="previewRows"
        :preview-columns="previewColumns"
        :resolve-project-label="resolveProjectLabel"
        :preview-section-rows="previewSectionRows"
        :section-value="sectionValue"
        :format-display-value="formatDisplayValue"
      />
    </el-tab-pane>

    <el-tab-pane :label="t('web.models.detailTabLineage')" name="lineage">
      <ModelLineagePanel :model-id="modelId" :model="model" />
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import type { DataModelDefinition, EntityId } from "@studio/api-sdk";
import ModelLineagePanel from "@/components/ModelLineagePanel.vue";
import ModelDetailOverview from "./ModelDetailOverview.vue";
import type { ModelMetaSection } from "./modelViewTypes";

defineProps<{
  activeTab: string;
  modelId?: EntityId;
  model?: DataModelDefinition;
  shared: boolean;
  sections: ModelMetaSection[];
  previewRows: Record<string, unknown>[];
  previewColumns: string[];
  resolveProjectLabel: (projectId?: EntityId | null) => string;
  previewSectionRows: (section: ModelMetaSection) => Record<string, unknown>[];
  sectionValue: (section: ModelMetaSection, fieldKey?: string, editor?: boolean) => unknown;
  formatDisplayValue: (value: unknown) => string;
  actions: {
    goBackToList: () => void;
    openStatisticsWorkspace: (datasourceId?: EntityId) => void;
    refreshDetail: () => void | Promise<void>;
    openDetailEdit: () => void;
  };
}>();

const emit = defineEmits<{
  "update:activeTab": [value: string];
}>();

const { t } = useI18n();
</script>

<style scoped>
.detail-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.detail-toolbar__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.model-detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
}

.model-detail-tabs :deep(.el-tabs__nav-wrap::after) {
  background: rgba(148, 163, 184, 0.22);
}

@media (max-width: 980px) {
  .detail-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .detail-toolbar__actions {
    justify-content: flex-start;
  }
}
</style>
