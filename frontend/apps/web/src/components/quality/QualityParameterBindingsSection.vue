<template>
  <SectionCard title="输入参数绑定" description="表参数和字段参数会自动赋值，自定义参数支持动态函数与手工输入。">
    <StudioTableShell min-width="820px">
    <el-table :data="bindings" border>
      <el-table-column label="序号" width="90" align="center" header-align="center">
        <template #default="{ row }">{{ row.paramOrder || "-" }}</template>
      </el-table-column>
      <el-table-column label="参数名称" min-width="160">
        <template #default="{ row }">{{ row.paramName }}</template>
      </el-table-column>
      <el-table-column label="参数类型" width="120" align="center" header-align="center">
        <template #default="{ row }">{{ actions.resolveParamTypeLabel(row.paramType) }}</template>
      </el-table-column>
      <el-table-column label="参数含义" min-width="180">
        <template #default="{ row }">{{ row.paramMeaning || "-" }}</template>
      </el-table-column>
      <el-table-column label="参数赋值" min-width="240">
        <template #default="{ row }">
          <div v-if="row.editable" class="task-binding-input">
            <input
              :ref="(el) => actions.registerBindingInputRef(row.paramName ?? '', el as HTMLInputElement | null)"
              v-model="row.paramValue"
              class="task-binding-input__field"
              placeholder="支持直接输入字面量或动态函数"
              @click="actions.syncBindingSelection(row.paramName ?? '', $event)"
              @focus="actions.syncBindingSelection(row.paramName ?? '', $event)"
              @keyup="actions.syncBindingSelection(row.paramName ?? '', $event)"
              @select="actions.syncBindingSelection(row.paramName ?? '', $event)"
            />
            <el-button plain size="small" @click="actions.openBindingFunction(row.paramName ?? '')">
              函数
            </el-button>
          </div>
          <el-input
            v-else
            v-model="row.paramValue"
            disabled
            placeholder="系统自动赋值"
          />
        </template>
      </el-table-column>
    </el-table>
    </StudioTableShell>
  </SectionCard>
</template>

<script setup lang="ts">
import type { QualityRuleParamType, QualityTaskParamBinding } from "@studio/api-sdk";
import { SectionCard, StudioTableShell } from "@studio/ui";

interface ParameterBindingActions {
  resolveParamTypeLabel: (type?: QualityRuleParamType) => string;
  registerBindingInputRef: (key: string, element: HTMLInputElement | null) => void;
  syncBindingSelection: (key: string, event: Event) => void;
  openBindingFunction: (key: string) => void;
}

defineProps<{
  bindings: QualityTaskParamBinding[];
  actions: ParameterBindingActions;
}>();
</script>

<style scoped>
.task-binding-input {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-binding-input__field {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--studio-border);
  border-radius: 12px;
  background: #fff;
  color: var(--studio-text);
  font: 13px/1.6 "Cascadia Code", "Consolas", monospace;
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.task-binding-input__field:focus {
  border-color: var(--studio-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

@media (max-width: 960px) {
  .task-binding-input {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
