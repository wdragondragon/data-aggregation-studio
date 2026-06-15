<template>
  <div class="protocol-trace">
    <div
      v-for="(step, index) in traceSteps"
      :key="step.key || index"
      class="protocol-trace-step"
      :class="{ 'protocol-trace-step--failed': isFailed(step), 'protocol-trace-step--empty': !step.key }"
    >
      <div class="protocol-trace-step__rail">
        <span class="protocol-trace-step__index">{{ index + 1 }}</span>
      </div>
      <div class="protocol-trace-step__content">
        <div class="protocol-trace-step__header">
          <div>
            <strong>{{ step.title || defaultTitles[index] }}</strong>
            <p>{{ step.summary || emptySummary }}</p>
          </div>
          <el-tag :type="isFailed(step) ? 'danger' : step.key ? 'success' : 'info'" effect="plain">
            {{ isFailed(step) ? "失败" : step.key ? "完成" : "无详情" }}
          </el-tag>
        </div>

        <div class="protocol-trace-step__meta">
          <span v-if="step.protocol">{{ step.protocol }}</span>
          <span v-if="step.method">{{ step.method }}</span>
          <span v-if="step.httpStatus != null">HTTP {{ step.httpStatus }}</span>
          <span v-if="step.contentType">{{ step.contentType }}</span>
        </div>
        <div v-if="step.url" class="protocol-trace-step__url">{{ step.url }}</div>
        <el-alert
          v-if="step.errorMessage"
          type="error"
          show-icon
          :closable="false"
          :title="step.errorMessage"
        />

        <el-collapse v-if="hasDetail(step)" class="protocol-trace-detail">
          <el-collapse-item v-if="hasObject(step.headers)" title="Header" name="headers">
            <pre>{{ formatJson(step.headers) }}</pre>
          </el-collapse-item>
          <el-collapse-item v-if="hasObject(step.query)" title="Query" name="query">
            <pre>{{ formatJson(step.query) }}</pre>
          </el-collapse-item>
          <el-collapse-item v-if="hasObject(step.form)" title="Form" name="form">
            <pre>{{ formatJson(step.form) }}</pre>
          </el-collapse-item>
          <el-collapse-item v-if="step.bodyPreview" title="Body" name="body">
            <pre>{{ formatBody(step) }}</pre>
          </el-collapse-item>
        </el-collapse>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { ProtocolConversionTraceStepView, ProtocolConversionTraceView } from "@studio/api-sdk";
import { formatXmlText } from "@/components/open-service/openServiceDebugSupport";

const props = defineProps<{
  trace?: ProtocolConversionTraceView | null;
}>();

const defaultTitles = ["原请求", "请求参数转换", "原响应", "响应转换"];
const emptySummary = "暂无结构化明细。";

const traceSteps = computed<ProtocolConversionTraceStepView[]>(() => [
  props.trace?.sourceRequest || emptyStep("sourceRequest", defaultTitles[0]),
  props.trace?.convertedRequest || emptyStep("convertedRequest", defaultTitles[1]),
  props.trace?.targetResponse || emptyStep("targetResponse", defaultTitles[2]),
  props.trace?.convertedResponse || emptyStep("convertedResponse", defaultTitles[3]),
]);

function emptyStep(key: string, title: string): ProtocolConversionTraceStepView {
  return { key: "", title, summary: "该阶段尚未执行或历史日志无结构化记录。" };
}

function isFailed(step: ProtocolConversionTraceStepView) {
  return String(step.status || "").toUpperCase() === "FAILED";
}

function hasObject(value?: Record<string, unknown>) {
  return Boolean(value && Object.keys(value).length);
}

function hasDetail(step: ProtocolConversionTraceStepView) {
  return hasObject(step.headers) || hasObject(step.query) || hasObject(step.form) || Boolean(step.bodyPreview);
}

function formatJson(value: unknown) {
  try {
    return JSON.stringify(value ?? {}, null, 2);
  } catch {
    return String(value ?? "");
  }
}

function formatBody(step: ProtocolConversionTraceStepView) {
  const text = String(step.bodyPreview ?? "");
  if (!text.trim()) {
    return "";
  }
  if (String(step.bodyFormat || "").toUpperCase() === "XML" || text.trim().startsWith("<")) {
    const formatted = formatXmlText(text, "Trace Body");
    return formatted.ok ? formatted.value : text;
  }
  if (String(step.bodyFormat || "").toUpperCase() === "JSON" || text.trim().startsWith("{") || text.trim().startsWith("[")) {
    try {
      return JSON.stringify(JSON.parse(text), null, 2);
    } catch {
      return text;
    }
  }
  return text;
}
</script>

<style scoped>
.protocol-trace {
  display: grid;
  gap: 12px;
}

.protocol-trace-step {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 10px;
}

.protocol-trace-step__rail {
  display: flex;
  justify-content: center;
}

.protocol-trace-step__index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 999px;
  background: rgba(22, 163, 74, 0.12);
  color: #15803d;
  font-weight: 700;
}

.protocol-trace-step--failed .protocol-trace-step__index {
  background: rgba(220, 38, 38, 0.12);
  color: #b91c1c;
}

.protocol-trace-step--empty .protocol-trace-step__index {
  background: rgba(100, 116, 139, 0.12);
  color: #64748b;
}

.protocol-trace-step__content {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.76);
}

.protocol-trace-step__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.protocol-trace-step__header p {
  margin: 3px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.5;
}

.protocol-trace-step__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.protocol-trace-step__meta span,
.protocol-trace-step__url {
  max-width: 100%;
  padding: 3px 7px;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.05);
  color: var(--studio-text-soft);
  font-size: 12px;
  word-break: break-all;
}

.protocol-trace-detail :deep(.el-collapse-item__header) {
  height: 34px;
  font-size: 12px;
}

.protocol-trace-detail pre {
  max-height: 320px;
  margin: 0;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: Consolas, "Courier New", monospace;
  font-size: 12px;
  line-height: 1.5;
}
</style>
