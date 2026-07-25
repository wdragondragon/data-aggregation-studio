<template>
  <SectionCard :title="t('web.workflows.basicsTitle')" :description="t('web.workflows.basicsDescription')">
    <div class="studio-form-grid">
      <el-form-item :label="t('web.workflows.workflowCode')">
        <el-input v-model="form.code" :placeholder="t('web.workflows.placeholderWorkflowCode')" />
      </el-form-item>
      <el-form-item :label="t('web.workflows.workflowName')">
        <el-input v-model="form.name" :placeholder="t('web.workflows.placeholderWorkflowName')" />
      </el-form-item>
      <el-form-item :label="t('web.runtimeClusterSelection.runtimeCluster')" required>
        <el-select v-model="form.runtimeClusterId" :loading="runtimeClustersLoading" :disabled="singleRuntimeCluster" :placeholder="t('web.runtimeClusterSelection.placeholder')" @change="onRuntimeClusterChange">
          <el-option v-for="cluster in runtimeClusters" :key="String(cluster.id)" :label="runtimeClusterOptionLabel(cluster)" :value="cluster.id" :disabled="runtimeClusterOptionDisabled(cluster)" />
        </el-select>
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
        <el-switch
          v-model="form.schedule.enabled"
          inline-prompt
          :active-text="t('common.on')"
          :inactive-text="t('common.off')"
          :disabled="isRuntimeScheduleToggleDisabled(runtimeInvalid, form.schedule.enabled)"
        />
      </el-form-item>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import { SectionCard } from "@studio/ui";
import CronExpressionPicker from "@web/components/CronExpressionPicker.vue";
import type { RuntimeClusterView } from "@studio/api-sdk";
import { isRuntimeScheduleToggleDisabled } from "@/utils/runtimeClusters";

defineProps<{
  form: {
    code?: string;
    name?: string;
    runtimeClusterId?: string | number;
    schedule: {
      cronExpression?: string;
      enabled?: boolean;
      timezone?: string;
    };
  };
  runtimeClusters: RuntimeClusterView[];
  runtimeClustersLoading: boolean;
  singleRuntimeCluster: boolean;
  runtimeClusterOptionLabel: (cluster: RuntimeClusterView) => string;
  runtimeClusterOptionDisabled: (cluster: RuntimeClusterView) => boolean;
  onRuntimeClusterChange: () => void | Promise<void>;
  runtimeInvalid?: boolean;
}>();

const { t } = useI18n();
</script>

<style scoped>
.cron-form-item {
  grid-column: 1 / -1;
}
</style>
