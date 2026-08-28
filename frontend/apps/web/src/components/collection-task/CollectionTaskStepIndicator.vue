<template>
  <SectionCard :title="t('web.collectionTasks.stepsTitle')" :description="t('web.collectionTasks.stepsDescription')">
    <div class="collection-task-wizard" :class="{ 'collection-task-wizard--streaming': props.executionMode === 'STREAMING' }">
      <button
        v-for="step in steps"
        :key="step.value"
        class="collection-task-wizard__step"
        :class="{ active: activeStep === step.value, done: activeStep > step.value }"
        type="button"
        @click="emit('update:activeStep', step.value)"
      >
        <span class="collection-task-wizard__index">{{ step.value }}</span>
        <span>
          <strong>{{ step.title }}</strong>
          <small>{{ step.description }}</small>
        </span>
      </button>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { SectionCard } from "@studio/ui";

const emit = defineEmits<{
  "update:activeStep": [value: number];
}>();

const { t } = useI18n();

const props = defineProps<{
  activeStep: number;
  executionMode?: "BATCH" | "STREAMING";
}>();

const steps = computed(() => [
  {
    value: 1,
    title: t("web.collectionTasks.step1Title"),
    description: "配置源端、目标端和运行参数",
  },
  {
    value: 2,
    title: t("web.collectionTasks.step2Title"),
    description: "配置字段映射和转换规则",
  },
  ...(props.executionMode === "STREAMING" ? [] : [{
    value: 3,
    title: t("web.collectionTasks.step3Title"),
    description: "配置定时调度与时区",
  }]),
  {
    value: props.executionMode === "STREAMING" ? 3 : 4,
    title: t("web.collectionTasks.step4Title"),
    description: "预览配置并保存任务",
  },
]);
</script>

<style scoped>
.collection-task-wizard {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.collection-task-wizard--streaming {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.collection-task-wizard__step {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 76px;
  padding: 14px 16px;
  border: 1px solid var(--studio-border);
  border-radius: 18px;
  background: #fff;
  color: var(--studio-text-soft);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.collection-task-wizard__step:hover,
.collection-task-wizard__step.active {
  border-color: rgba(37, 99, 235, 0.42);
  box-shadow: 0 12px 28px rgba(37, 99, 235, 0.1);
  transform: translateY(-1px);
}

.collection-task-wizard__step.done {
  border-color: rgba(22, 163, 74, 0.32);
}

.collection-task-wizard__step strong {
  display: block;
  color: var(--studio-text);
  font-size: 14px;
}

.collection-task-wizard__step small {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.35;
}

.collection-task-wizard__index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.08);
  color: var(--studio-primary);
  font-weight: 700;
}

.collection-task-wizard__step.done .collection-task-wizard__index {
  background: rgba(22, 163, 74, 0.12);
  color: #15803d;
}

@media (max-width: 1100px) {
  .collection-task-wizard {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .collection-task-wizard {
    grid-template-columns: 1fr;
  }
}
</style>
