<template>
  <SectionCard :title="t('web.collectionTasks.reviewTitle')" :description="t('web.collectionTasks.reviewDescription')">
    <div class="review-grid">
      <div class="soft-panel">
        <strong>{{ form.name || t("common.none") }}</strong>
        <p>{{ taskTypeLabel }}</p>
        <p>{{ t("web.collectionTasks.executionMode") }}: {{ form.executionMode === "STREAMING" ? t("web.collectionTasks.executionModeStreaming") : t("web.collectionTasks.executionModeBatch") }}</p>
        <p>{{ t("web.collectionTasks.sourceCount") }}: {{ form.sourceBindings.length }}</p>
      </div>
      <div class="soft-panel">
        <strong>{{ t("web.collectionTasks.mappingTitle") }}</strong>
        <p>{{ t("common.fields", { count: form.fieldMappings.length }) }}</p>
        <p>{{ form.schedule.enabled ? form.schedule.cronExpression || t("common.on") : t("common.off") }}</p>
      </div>
    </div>

    <div class="soft-panel preview-panel">
      <div class="section-toolbar">
        <div>
          <strong>{{ t("web.collectionTasks.previewTitle") }}</strong>
          <p>{{ t("web.collectionTasks.previewDescription") }}</p>
        </div>
        <el-button plain :loading="previewLoading" :disabled="!canPreviewConfig" @click="loadPreviewConfig">
          {{ t("web.collectionTasks.refreshPreview") }}
        </el-button>
      </div>

      <pre v-if="previewConfig" class="json-block studio-mono preview-json">{{ prettyJson(previewConfig) }}</pre>
      <div v-else class="soft-panel">
        {{ t("web.collectionTasks.previewEmpty") }}
      </div>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import type { JobContainerConfig } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { prettyJson } from "@/utils/studio";

interface ReviewForm {
  name: string;
  executionMode?: "BATCH" | "STREAMING";
  sourceBindings: unknown[];
  fieldMappings: unknown[];
  schedule: {
    enabled?: boolean;
    cronExpression?: string;
  };
}

defineProps<{
  form: ReviewForm;
  taskTypeLabel: string;
  previewLoading: boolean;
  canPreviewConfig: boolean;
  previewConfig: JobContainerConfig | null;
  loadPreviewConfig: () => void | Promise<void>;
}>();

const { t } = useI18n();
</script>

<style scoped>
.review-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}

.preview-panel {
  margin-top: 12px;
}

.preview-json {
  max-height: 520px;
  overflow: auto;
}
</style>
