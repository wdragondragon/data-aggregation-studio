<template>
  <el-dialog
    :model-value="modelValue"
    title="插入动态函数"
    width="880px"
    destroy-on-close
    class="dynamic-function-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="dynamic-function-dialog__layout">
      <div class="dynamic-function-dialog__sidebar">
        <div class="dynamic-function-dialog__sidebar-title">函数列表</div>
        <div class="dynamic-function-list">
          <button
            v-for="item in catalog"
            :key="item.name"
            type="button"
            class="dynamic-function-item"
            :class="{ 'dynamic-function-item--active': selectedName === item.name }"
            @click="emit('update:selectedName', item.name)"
          >
            <strong>{{ item.label }}</strong>
            <span>{{ item.summary }}</span>
          </button>
        </div>
      </div>

      <div class="dynamic-function-dialog__content">
        <div class="dynamic-function-card">
          <div class="dynamic-function-card__header">
            <div>
              <h4>{{ selectedFunction.label }}</h4>
              <p>{{ selectedFunction.summary }}</p>
            </div>
            <code>{{ selectedFunction.signature }}</code>
          </div>
          <p class="dynamic-function-card__description">{{ selectedFunction.description }}</p>
          <div class="dynamic-function-card__meta">
            <span>返回值：{{ selectedFunction.returnDescription }}</span>
            <span>示例：{{ selectedFunction.example }}</span>
          </div>
        </div>

        <div v-if="selectedInputSnippet" class="dynamic-function-selection-tip">
          当前选中内容：<code>{{ selectedInputSnippet }}</code>
        </div>

        <div class="dynamic-function-args">
          <el-form-item
            v-for="param in selectedFunction.params"
            :key="param.key"
            :label="param.label"
            class="dynamic-function-args__item"
          >
            <el-input v-model="args[param.key]" :placeholder="param.placeholder" />
            <div class="field-hint">
              {{ param.description }}
              <span v-if="!param.required"> 可留空。</span>
            </div>
          </el-form-item>
        </div>

        <div class="dynamic-function-preview">
          <span class="logic-sql-actions__label">表达式预览</span>
          <pre>{{ preview }}</pre>
          <div class="field-hint">文本参数默认自动补单引号；如果要传占位符或其他表达式，可以直接输入完整内容。</div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="studio-toolbar-actions">
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" @click="emit('confirm')">插入到当前输入框</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { DynamicFunctionSchema } from "./qualityTaskDynamicFunctions";

defineProps<{
  modelValue: boolean;
  catalog: DynamicFunctionSchema[];
  selectedName: string;
  selectedFunction: DynamicFunctionSchema;
  selectedInputSnippet: string;
  args: Record<string, string>;
  preview: string;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  "update:selectedName": [value: string];
  confirm: [];
}>();
</script>

<style scoped>
.logic-sql-actions__label {
  color: var(--studio-text-soft);
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
}

.field-hint {
  margin-top: 6px;
  color: var(--studio-text-soft);
  font-size: 12px;
}

:deep(.dynamic-function-dialog) {
  max-width: calc(100vw - 32px);
}

.dynamic-function-dialog__layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 16px;
}

.dynamic-function-dialog__sidebar {
  display: grid;
  gap: 10px;
}

.dynamic-function-dialog__sidebar-title {
  color: var(--studio-text-soft);
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
}

.dynamic-function-list {
  display: grid;
  gap: 8px;
  max-height: 540px;
  overflow: auto;
}

.dynamic-function-item {
  display: grid;
  gap: 4px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--studio-border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.8);
  color: var(--studio-text);
  text-align: left;
  cursor: pointer;
}

.dynamic-function-item strong {
  font-size: 13px;
}

.dynamic-function-item span {
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.45;
}

.dynamic-function-item:hover,
.dynamic-function-item--active {
  border-color: rgba(59, 130, 246, 0.42);
  background: rgba(239, 246, 255, 0.95);
}

.dynamic-function-dialog__content {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.dynamic-function-card {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--studio-border);
  border-radius: 14px;
}

.dynamic-function-card__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.dynamic-function-card__header h4 {
  margin: 0 0 4px;
  color: var(--studio-text);
  font-size: 18px;
}

.dynamic-function-card__header p,
.dynamic-function-card__description {
  margin: 0;
  color: var(--studio-text-soft);
}

.dynamic-function-card__header code,
.dynamic-function-selection-tip code {
  padding: 4px 6px;
  border-radius: 8px;
  background: rgba(15, 23, 42, 0.06);
  color: var(--studio-text);
  white-space: nowrap;
}

.dynamic-function-card__meta {
  display: grid;
  gap: 4px;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.dynamic-function-selection-tip {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(248, 250, 252, 0.92);
  color: var(--studio-text-soft);
}

.dynamic-function-args {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.dynamic-function-args__item {
  margin-bottom: 0;
}

.dynamic-function-preview {
  display: grid;
  gap: 8px;
}

.dynamic-function-preview pre {
  margin: 0;
  padding: 12px;
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.92);
  color: #e2e8f0;
  white-space: pre-wrap;
  word-break: break-all;
}

@media (max-width: 980px) {
  .dynamic-function-dialog__layout,
  .dynamic-function-args {
    grid-template-columns: 1fr;
  }

  .dynamic-function-dialog__sidebar {
    max-height: 260px;
    overflow: auto;
  }

  .dynamic-function-card__header {
    flex-direction: column;
  }
}
</style>
