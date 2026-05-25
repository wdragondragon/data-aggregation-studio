<template>
  <SectionCard title="基本信息" description="先确定任务编码、规则维度与校验粒度，后续规则会按条件过滤。">
    <div class="studio-form-grid">
      <el-form-item label="任务名称">
        <el-input v-model="form.taskName" placeholder="例如：订单表主键唯一性巡检" />
      </el-form-item>
      <el-form-item label="任务编码">
        <el-input v-model="form.taskCode" placeholder="例如：DQ_TASK_ORDER_PK" />
      </el-form-item>
      <el-form-item label="任务状态">
        <el-input :model-value="form.id ? (taskStatus === 'ONLINE' ? '已发布' : '草稿') : '未保存'" disabled />
      </el-form-item>
      <el-form-item label="规则维度">
        <el-select v-model="form.ruleDimension" @change="actions.handleRuleDimensionChange">
          <el-option v-for="option in dimensionOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="校验粒度">
        <el-select v-model="form.granularity" @change="actions.handleGranularityChange">
          <el-option label="表级" value="TABLE" />
          <el-option label="字段级" value="COLUMN" />
        </el-select>
      </el-form-item>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import type { QualityRuleDimension, QualityRuleGranularity } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";

interface BasicInfoForm {
  id?: string | number;
  taskName: string;
  taskCode: string;
  ruleDimension: QualityRuleDimension;
  granularity: QualityRuleGranularity;
}

interface DimensionOption {
  label: string;
  value: QualityRuleDimension;
}

interface BasicInfoActions {
  handleRuleDimensionChange: () => void | Promise<void>;
  handleGranularityChange: (value: QualityRuleGranularity) => void | Promise<void>;
}

defineProps<{
  form: BasicInfoForm;
  taskStatus: "DRAFT" | "ONLINE";
  dimensionOptions: DimensionOption[];
  actions: BasicInfoActions;
}>();
</script>
