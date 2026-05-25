<template>
  <div class="soft-panel index-queue-card">
    <div class="index-queue-card__header">
      <div>
        <strong>{{ t("web.models.indexQueueTitle") }}</strong>
        <p>{{ t("web.models.indexQueueDescription") }}</p>
      </div>
      <StatusPill
        :label="busy ? t('web.models.indexQueueBusy') : t('web.models.indexQueueIdle')"
        :tone="busy ? 'warning' : 'success'"
      />
    </div>
    <div class="index-queue-card__metrics">
      <div class="index-queue-card__metric">
        <span class="index-queue-card__metric-label">{{ t("web.models.indexQueuePending") }}</span>
        <strong class="index-queue-card__metric-value">{{ pendingRebuildCount }}</strong>
      </div>
      <div class="index-queue-card__metric">
        <span class="index-queue-card__metric-label">{{ t("web.models.indexQueueQueuedCommands") }}</span>
        <strong class="index-queue-card__metric-value">{{ queuedCommandCount }}</strong>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import { StatusPill } from "@studio/ui";

defineProps<{
  busy: boolean;
  pendingRebuildCount: number;
  queuedCommandCount: number;
}>();

const { t } = useI18n();
</script>

<style scoped>
.index-queue-card {
  min-width: 280px;
  max-width: 360px;
  display: grid;
  gap: 12px;
}

.index-queue-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.index-queue-card__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.index-queue-card__metric {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(248, 250, 252, 0.92);
  border: 1px solid rgba(148, 163, 184, 0.18);
}

.index-queue-card__metric-label {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.index-queue-card__metric-value {
  color: var(--studio-text);
  font-size: 20px;
}

@media (max-width: 960px) {
  .index-queue-card {
    width: 100%;
    max-width: none;
  }

  .index-queue-card__header {
    flex-direction: column;
  }
}
</style>
