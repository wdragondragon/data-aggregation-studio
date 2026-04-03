<template>
  <div class="studio-page">
    <div class="studio-grid columns-3">
      <MetricCard :label="t('desktop.home.mode')" :value="mode.mode || 'DESKTOP'" :hint="t('desktop.home.modeHint')" />
      <MetricCard :label="t('desktop.home.offlineExecution')" :value="mode.offlineExecution ? t('desktop.home.enabled') : t('desktop.home.disabled')" tone="success" :hint="t('desktop.home.offlineExecutionHint')" />
      <MetricCard :label="t('desktop.home.syncBoundary')" :value="mode.syncStrategy || 'IMPORT_EXPORT'" tone="accent" :hint="t('desktop.home.syncBoundaryHint')" />
    </div>

    <SectionCard :title="t('desktop.home.title')" :description="t('desktop.home.description')">
      <div class="soft-panel">
        <p><strong>{{ t("desktop.home.currentMode") }}</strong></p>
        <pre class="json-block studio-mono">{{ JSON.stringify(mode, null, 2) }}</pre>
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { RuntimeModeResponse } from "@studio/api-sdk";
import { MetricCard, SectionCard } from "@studio/ui";
import { desktopApi } from "@/api/studio";

const { t } = useI18n();
const mode = reactive<Partial<RuntimeModeResponse>>({});

async function loadMode() {
  try {
    Object.assign(mode, await desktopApi.runtime.mode());
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("desktop.home.loadFailed"));
  }
}

onMounted(loadMode);
</script>
