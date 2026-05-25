<template>
  <SectionCard title="SQL 预览与校验" description="where 子句支持动态函数；SQL 预览会替换参数与函数后生成当前时刻可执行语句。">
    <div class="section-toolbar">
      <div>
        <strong>执行约束</strong>
        <p>where 子句会拼接到规则 SQL 外层，最终 SQL 可直接用于执行校验。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="actions.openWhereClauseFunction">插入动态函数</el-button>
        <el-button plain :loading="previewLoading" @click="actions.previewSql">SQL 预览</el-button>
        <el-button type="primary" plain :loading="validateLoading" @click="actions.validateTask">语义校验</el-button>
      </div>
    </div>
    <el-form-item label="Where 子句">
      <textarea
        :ref="actions.registerWhereClauseTextareaRef"
        v-model="form.whereClause"
        class="task-where-textarea"
        placeholder="例如：dt = $getCurrentTime('yyyy-MM-dd', '-1d') 或 status = 'VALID'"
        spellcheck="false"
        @click="actions.syncWhereClauseSelection"
        @focus="actions.syncWhereClauseSelection"
        @keyup="actions.syncWhereClauseSelection"
        @select="actions.syncWhereClauseSelection"
      />
    </el-form-item>
    <el-form-item label="最终执行 SQL">
      <el-input v-model="form.resolvedSqlPreview" type="textarea" :rows="10" readonly placeholder="点击 SQL 预览后生成实际语句" />
    </el-form-item>
    <div v-if="previewWarnings.length" class="inline-message warning">
      <div v-for="(warning, index) in previewWarnings" :key="`preview-${index}`">{{ warning }}</div>
    </div>
    <div v-if="validationResult?.message" class="inline-message" :class="validationResult.valid ? 'success' : 'danger'">
      {{ validationResult.message }}
    </div>
    <div v-if="validationWarnings.length" class="inline-message warning">
      <div v-for="(warning, index) in validationWarnings" :key="`validate-${index}`">{{ warning }}</div>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import type { QualityTaskValidationView } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";

interface SqlPreviewForm {
  whereClause: string;
  resolvedSqlPreview: string;
}

interface SqlPreviewActions {
  registerWhereClauseTextareaRef: (element: unknown) => void;
  syncWhereClauseSelection: () => void;
  openWhereClauseFunction: () => void;
  previewSql: () => void | Promise<void>;
  validateTask: () => void | Promise<void>;
}

defineProps<{
  form: SqlPreviewForm;
  previewLoading: boolean;
  validateLoading: boolean;
  previewWarnings: string[];
  validationWarnings: string[];
  validationResult: QualityTaskValidationView | null;
  actions: SqlPreviewActions;
}>();
</script>

<style scoped>
.task-where-textarea {
  width: 100%;
  min-height: 108px;
  padding: 10px 12px;
  border: 1px solid var(--studio-border);
  border-radius: 12px;
  background: #fff;
  color: var(--studio-text);
  font: 13px/1.6 "Cascadia Code", "Consolas", monospace;
  outline: none;
  resize: vertical;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.task-where-textarea:focus {
  border-color: var(--studio-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.inline-message {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.04);
  color: var(--studio-text-soft);
}

.inline-message.success {
  background: rgba(34, 197, 94, 0.12);
  color: #166534;
}

.inline-message.warning {
  background: rgba(245, 158, 11, 0.12);
  color: #92400e;
}

.inline-message.danger {
  background: rgba(239, 68, 68, 0.12);
  color: #991b1b;
}
</style>
