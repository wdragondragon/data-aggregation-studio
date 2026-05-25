<template>
  <SectionCard :title="t('web.workflows.basicsTitle')" :description="t('web.workflows.basicsDescription')">
    <div class="studio-form-grid">
      <el-form-item :label="t('web.workflows.workflowCode')">
        <el-input v-model="form.code" :placeholder="t('web.workflows.placeholderWorkflowCode')" />
      </el-form-item>
      <el-form-item :label="t('web.workflows.workflowName')">
        <el-input v-model="form.name" :placeholder="t('web.workflows.placeholderWorkflowName')" />
      </el-form-item>
      <el-form-item :label="t('web.workflows.cronExpression')" class="cron-form-item">
        <CronExpressionPicker
          v-model="form.schedule.cronExpression"
          :label="t('web.workflows.cronExpression')"
        />
      </el-form-item>
      <el-form-item :label="t('web.workflows.timezone')">
        <el-input v-model="form.schedule.timezone" placeholder="Asia/Shanghai" />
      </el-form-item>
      <el-form-item :label="t('web.workflows.scheduleEnabled')">
        <el-switch v-model="form.schedule.enabled" inline-prompt :active-text="t('common.on')" :inactive-text="t('common.off')" />
      </el-form-item>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import { SectionCard } from "@studio/ui";
import CronExpressionPicker from "@web/components/CronExpressionPicker.vue";

defineProps<{
  form: {
    code?: string;
    name?: string;
    schedule: {
      cronExpression?: string;
      enabled?: boolean;
      timezone?: string;
    };
  };
}>();

const { t } = useI18n();
</script>

<style scoped>
.cron-form-item {
  grid-column: 1 / -1;
}
</style>
