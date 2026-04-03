<template>
  <div class="studio-page">
    <div class="studio-grid columns-3">
      <MetricCard label="Mode" :value="mode.mode || 'DESKTOP'" hint="Runtime Shape" />
      <MetricCard label="Offline Execution" :value="mode.offlineExecution ? 'Enabled' : 'Disabled'" tone="success" hint="Local Worker" />
      <MetricCard label="Sync Boundary" :value="mode.syncStrategy || 'IMPORT_EXPORT'" tone="accent" hint="Exchange Strategy" />
    </div>

    <SectionCard title="Desktop Runtime" description="The offline shell stays independent and only exchanges bundles with the online environment.">
      <div class="soft-panel">
        <p><strong>Current mode</strong></p>
        <pre class="json-block studio-mono">{{ JSON.stringify(mode, null, 2) }}</pre>
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive } from "vue";
import { ElMessage } from "element-plus";
import type { RuntimeModeResponse } from "@studio/api-sdk";
import { MetricCard, SectionCard } from "@studio/ui";
import { desktopApi } from "@/api/studio";

const mode = reactive<Partial<RuntimeModeResponse>>({});

async function loadMode() {
  try {
    Object.assign(mode, await desktopApi.runtime.mode());
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to load runtime mode");
  }
}

onMounted(loadMode);
</script>
