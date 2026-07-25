<template>
  <SectionCard :title="t('web.collectionTasks.scheduleTitle')" :description="t('web.collectionTasks.scheduleDescription')">
    <div class="studio-form-grid">
      <el-form-item :label="t('web.collectionTasks.scheduleEnabled')">
        <el-switch
          v-model="schedule.enabled"
          inline-prompt
          :active-text="t('common.on')"
          :inactive-text="t('common.off')"
          :disabled="isRuntimeScheduleToggleDisabled(runtimeInvalid, schedule.enabled)"
        />
      </el-form-item>
      <el-form-item :label="t('web.collectionTasks.cronExpression')" class="cron-form-item">
        <CronExpressionPicker
          v-model="schedule.cronExpression"
          :label="t('web.collectionTasks.cronExpression')"
        />
      </el-form-item>
      <el-form-item :label="t('web.collectionTasks.timezone')">
        <el-input v-model="schedule.timezone" placeholder="Asia/Shanghai" />
      </el-form-item>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import { SectionCard } from "@studio/ui";
import CronExpressionPicker from "@web/components/CronExpressionPicker.vue";
import { isRuntimeScheduleToggleDisabled } from "@/utils/runtimeClusters";

defineProps<{
  schedule: {
    enabled?: boolean;
    cronExpression?: string;
    timezone?: string;
  };
  runtimeInvalid?: boolean;
}>();

const { t } = useI18n();
</script>
