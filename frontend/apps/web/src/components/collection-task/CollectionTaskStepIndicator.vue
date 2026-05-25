<template>
  <SectionCard :title="t('web.collectionTasks.stepsTitle')" :description="t('web.collectionTasks.stepsDescription')">
    <el-steps :active="activeStep - 1" finish-status="success">
      <el-step :title="t('web.collectionTasks.step1Title')" />
      <el-step :title="t('web.collectionTasks.step2Title')" />
      <el-step :title="t('web.collectionTasks.step3Title')" />
      <el-step :title="t('web.collectionTasks.step4Title')" />
    </el-steps>
  </SectionCard>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import { SectionCard } from "@studio/ui";

defineProps<{
  activeStep: number;
}>();

const { t } = useI18n();
</script>
