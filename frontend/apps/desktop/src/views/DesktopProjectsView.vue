<template>
  <div class="studio-page">
    <SectionCard :title="t('desktop.projects.title')" :description="t('desktop.projects.description')">
      <div class="studio-toolbar">
        <div>
          <strong>{{ t("desktop.projects.exportTitle") }}</strong>
          <p>{{ t("desktop.projects.exportDescription") }}</p>
        </div>
        <div class="studio-toolbar-actions">
          <el-button type="primary" plain @click="loadBundle">{{ t("desktop.projects.refreshBundle") }}</el-button>
          <el-button @click="loadTemplate">{{ t("desktop.projects.importTemplateInfo") }}</el-button>
        </div>
      </div>

      <pre class="json-block studio-mono">{{ bundleText }}</pre>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { SectionCard } from "@studio/ui";
import { desktopApi } from "@/api/studio";

const { t } = useI18n();
const bundle = ref<unknown>({});

const bundleText = computed(() => JSON.stringify(bundle.value, null, 2));

async function loadBundle() {
  try {
    bundle.value = await desktopApi.exports.project();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("desktop.projects.loadBundleFailed"));
  }
}

async function loadTemplate() {
  try {
    bundle.value = await desktopApi.imports.template();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("desktop.projects.loadTemplateFailed"));
  }
}
</script>
