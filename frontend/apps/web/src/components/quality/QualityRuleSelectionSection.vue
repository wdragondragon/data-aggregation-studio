<template>
  <SectionCard title="规则选择" description="仅展示当前维度、粒度和数据源类型下可用的已启用质量规则。">
    <div class="section-toolbar">
      <div>
        <strong>匹配规则</strong>
        <p>选中规则后会自动加载 SQL 模板、输入参数和输出参数。</p>
      </div>
      <el-button plain :loading="rulesLoading" @click="actions.loadRuleOptions">刷新规则</el-button>
    </div>
    <div class="studio-form-grid">
      <el-form-item label="质量规则">
        <el-select
          :model-value="String(form.ruleId || '')"
          filterable
          clearable
          placeholder="请选择质量规则"
          @update:model-value="actions.handleRuleChange"
        >
          <el-option
            v-for="rule in ruleOptions"
            :key="String(rule.id)"
            :label="`${rule.ruleName} / ${rule.ruleCode}`"
            :value="String(rule.id)"
          >
            <div class="rule-option">
              <span>{{ rule.ruleName }}</span>
              <span class="cell-subtle">{{ rule.scopeType === "SYSTEM" ? "系统级" : "项目级" }} / {{ actions.resolveDimensionLabel(rule.ruleDimension) }}</span>
            </div>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="规则来源">
        <el-input :model-value="selectedRule ? (selectedRule.scopeType === 'SYSTEM' ? '系统级规则' : '项目级规则') : '-'" disabled />
      </el-form-item>
      <el-form-item label="适配类型">
        <el-input :model-value="actions.resolveSupportedDatasourceTypesLabel(selectedRule)" disabled />
      </el-form-item>
    </div>
    <el-form-item label="定义逻辑 SQL">
      <el-input :model-value="selectedRule?.logicSql || ''" type="textarea" :rows="8" readonly placeholder="选择规则后展示定义逻辑 SQL" />
    </el-form-item>
  </SectionCard>
</template>

<script setup lang="ts">
import type { QualityRuleOptionView, QualityRuleView } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";

interface RuleSelectionForm {
  ruleId: string | number;
}

interface RuleSelectionActions {
  loadRuleOptions: () => void | Promise<void>;
  handleRuleChange: (value: string) => void | Promise<void>;
  resolveDimensionLabel: (value?: string) => string;
  resolveSupportedDatasourceTypesLabel: (rule?: QualityRuleView | null) => string;
}

defineProps<{
  form: RuleSelectionForm;
  ruleOptions: QualityRuleOptionView[];
  selectedRule: QualityRuleView | null;
  rulesLoading: boolean;
  actions: RuleSelectionActions;
}>();
</script>

<style scoped>
.rule-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}
</style>
