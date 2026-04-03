<template>
  <div class="studio-page">
    <SectionCard title="Import and Export Boundary" description="First-version desktop and web remain independent and exchange only explicit bundles.">
      <div class="studio-toolbar">
        <div>
          <strong>Project bundle export</strong>
          <p>Use this preview to inspect what would be packaged for transfer.</p>
        </div>
        <div class="studio-toolbar-actions">
          <el-button type="primary" plain @click="loadBundle">Refresh Bundle</el-button>
          <el-button @click="loadTemplate">Import Template Info</el-button>
        </div>
      </div>

      <pre class="json-block studio-mono">{{ bundleText }}</pre>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import { SectionCard } from "@studio/ui";
import { desktopApi } from "@/api/studio";

const bundle = ref<unknown>({});

const bundleText = computed(() => JSON.stringify(bundle.value, null, 2));

async function loadBundle() {
  try {
    bundle.value = await desktopApi.exports.project();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to load export bundle");
  }
}

async function loadTemplate() {
  try {
    bundle.value = await desktopApi.imports.template();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Failed to load import template");
  }
}
</script>
