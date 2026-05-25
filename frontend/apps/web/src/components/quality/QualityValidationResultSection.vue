<template>
  <SectionCard v-if="validationResult" title="校验结果" description="语义校验会使用已选数据源执行真实 SQL，并返回结果列、样例数据和摘要。">
    <div class="validation-overview">
      <div class="summary-chip">
        <span>校验状态</span>
        <strong>{{ validationResult.valid ? "通过" : "失败" }}</strong>
      </div>
      <div class="summary-chip">
        <span>结果列数</span>
        <strong>{{ validationResult.columns?.length ?? 0 }}</strong>
      </div>
      <div class="summary-chip">
        <span>样例行数</span>
        <strong>{{ validationResult.rows?.length ?? 0 }}</strong>
      </div>
    </div>

    <div v-if="validationResult.columns?.length" class="validation-block">
      <strong>输出列</strong>
      <div class="tag-list">
        <el-tag v-for="column in validationResult.columns" :key="column" effect="plain">{{ column }}</el-tag>
      </div>
    </div>

    <div v-if="validationResult.rows?.length" class="validation-block">
      <strong>样例数据</strong>
      <el-table :data="validationResult.rows" border max-height="360">
        <el-table-column
          v-for="column in validationResult.columns"
          :key="column"
          :prop="column"
          :label="column"
          min-width="160"
        />
      </el-table>
    </div>

    <div class="validation-block">
      <strong>执行摘要</strong>
      <pre class="json-block">{{ prettyJson(validationResult.summary ?? {}) }}</pre>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import type { QualityTaskValidationView } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { prettyJson } from "@/utils/studio";

defineProps<{
  validationResult: QualityTaskValidationView | null;
}>();
</script>

<style scoped>
.validation-overview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.summary-chip {
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(15, 23, 42, 0.04);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.validation-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 14px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.json-block {
  margin: 0;
  padding: 14px;
  border-radius: 14px;
  background: rgba(15, 23, 42, 0.04);
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: "JetBrains Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
}
</style>
