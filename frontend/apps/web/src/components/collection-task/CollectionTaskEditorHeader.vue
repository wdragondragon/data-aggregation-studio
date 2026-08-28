<template>
  <div class="studio-toolbar">
    <div>
      <h3>{{ taskId ? t("web.collectionTasks.editTitle") : t("web.collectionTasks.createTitle") }}</h3>
      <p>{{ t("web.collectionTasks.editorDescription") }}</p>
    </div>
    <div class="studio-toolbar-actions">
      <el-button @click="actions.backToList">{{ t("common.backToList") }}</el-button>
      <el-button plain @click="actions.refresh">{{ t("common.refresh") }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="locked" @click="actions.saveTask">{{ t("common.saveDraft") }}</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";

interface HeaderActions {
  backToList: () => void | Promise<void>;
  refresh: () => void | Promise<void>;
  saveTask: () => void | Promise<void>;
}

defineProps<{
  taskId?: string;
  saving: boolean;
  locked?: boolean;
  actions: HeaderActions;
}>();

const { t } = useI18n();
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}
</style>
