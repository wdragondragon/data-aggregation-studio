<template>
  <SectionCard title="输出告警" description="按输出字段逐项定义告警条件，当前版本仅将告警内容打印到运行日志。">
    <el-table :data="configs" border>
      <el-table-column label="序号" width="90" align="center" header-align="center">
        <template #default="{ row }">{{ row.outputOrder || "-" }}</template>
      </el-table-column>
      <el-table-column label="结果字段" min-width="180">
        <template #default="{ row }">{{ row.resultField || "-" }}</template>
      </el-table-column>
      <el-table-column label="输出类型" width="110" align="center" header-align="center">
        <template #default="{ row }">{{ row.outputType === "NUMBER" ? "数值" : "字符串" }}</template>
      </el-table-column>
      <el-table-column label="开启告警" width="110" align="center" header-align="center">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" />
        </template>
      </el-table-column>
      <el-table-column label="比较方式" min-width="180">
        <template #default="{ row }">
          <el-select v-model="row.operator" :disabled="!row.enabled" placeholder="请选择" @change="actions.handleAlertOperatorChange(row)">
            <el-option
              v-for="option in actions.resolveAlertOperators(row.outputType)"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="阈值配置" min-width="280">
        <template #default="{ row }">
          <div v-if="actions.isRangeOperator(row.operator)" class="threshold-grid">
            <el-input v-model="row.minValue" :disabled="!row.enabled" placeholder="最小值" />
            <el-input v-model="row.maxValue" :disabled="!row.enabled" placeholder="最大值" />
          </div>
          <el-input v-else v-model="row.expectedValue" :disabled="!row.enabled" placeholder="比较值" />
        </template>
      </el-table-column>
    </el-table>
  </SectionCard>
</template>

<script setup lang="ts">
import type { QualityRuleOutputType, QualityTaskAlertConfig, QualityTaskAlertOperator } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";

interface AlertOperatorOption {
  label: string;
  value: QualityTaskAlertOperator;
}

interface AlertConfigActions {
  resolveAlertOperators: (outputType?: QualityRuleOutputType) => AlertOperatorOption[];
  isRangeOperator: (operator?: QualityTaskAlertOperator) => boolean;
  handleAlertOperatorChange: (row: QualityTaskAlertConfig) => void;
}

defineProps<{
  configs: QualityTaskAlertConfig[];
  actions: AlertConfigActions;
}>();
</script>

<style scoped>
.threshold-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
</style>
