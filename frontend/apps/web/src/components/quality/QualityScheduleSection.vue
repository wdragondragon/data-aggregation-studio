<template>
  <SectionCard title="调度配置" description="支持保存草稿时一并配置调度，发布后即可由调度器接管执行。">
    <div class="studio-form-grid">
      <el-form-item label="开启调度">
        <el-switch v-model="schedule.enabled" inline-prompt active-text="开启" inactive-text="关闭" />
      </el-form-item>
      <el-form-item label="Cron 表达式" class="cron-form-item">
        <CronExpressionPicker v-model="schedule.cronExpression" label="Cron 表达式" />
      </el-form-item>
      <el-form-item label="时区">
        <el-input v-model="schedule.timezone" placeholder="Asia/Shanghai" />
      </el-form-item>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { SectionCard } from "@studio/ui";
import CronExpressionPicker from "@web/components/CronExpressionPicker.vue";

defineProps<{
  schedule: {
    enabled: boolean;
    cronExpression: string;
    timezone: string;
  };
}>();
</script>
