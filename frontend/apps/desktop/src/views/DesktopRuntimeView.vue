<template>
  <div class="studio-page">
    <SectionCard :title="t('desktop.runtime.title')" :description="t('desktop.runtime.description')">
      <div class="endpoint-list">
        <div class="soft-panel">
          <strong>{{ t("desktop.runtime.renderer") }}</strong>
          <p class="studio-mono">http://localhost:5174</p>
        </div>
        <div class="soft-panel">
          <strong>{{ t("desktop.runtime.runtimeApi") }}</strong>
          <p class="studio-mono">http://localhost:18180/api/v1</p>
        </div>
        <div class="soft-panel">
          <strong>{{ t("desktop.runtime.knife4j") }}</strong>
          <p class="studio-mono">http://localhost:18180/doc.html</p>
        </div>
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import { SectionCard } from "@studio/ui";

const { t } = useI18n();
</script>

<style scoped>
.endpoint-list {
  display: grid;
  gap: 14px;
}
</style>
