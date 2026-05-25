<template>
  <div class="editor-footer">
    <el-button :disabled="activeStepModel === 1" @click="activeStepModel -= 1">{{ t("web.collectionTasks.previousStep") }}</el-button>
    <el-button v-if="activeStepModel < 4" type="primary" @click="activeStepModel += 1">{{ t("web.collectionTasks.nextStep") }}</el-button>
    <el-button v-else type="primary" :loading="saving" @click="saveTask">{{ t("common.saveDraft") }}</el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";

const props = defineProps<{
  activeStep: number;
  saving: boolean;
  saveTask: () => void | Promise<void>;
}>();

const emit = defineEmits<{
  "update:activeStep": [value: number];
}>();

const { t } = useI18n();

const activeStepModel = computed({
  get: () => props.activeStep,
  set: (value: number) => emit("update:activeStep", value),
});
</script>

<style scoped>
.editor-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 12px;
}
</style>
