<template>
  <SectionCard title="四、接口调试" description="按发布映射生成 Header、Query、Body 模板，调试结果会展示统一包装结构。">
    <div class="section-toolbar debug-toolbar">
      <div>
        <strong>调试请求</strong>
        <p>先生成模板，再按实际值补充 Header、Query、Body 后发起调试。</p>
      </div>
      <div class="section-actions">
        <el-button plain @click="debugActions.generateDebugTemplate">生成调试模板</el-button>
        <el-button plain :disabled="!endpointUrl" @click="debugActions.generateCurlCommand('bash')">生成 cURL(bash)</el-button>
        <el-button plain :disabled="!endpointUrl" @click="debugActions.generateCurlCommand('cmd')">生成 cURL(cmd)</el-button>
        <el-button type="primary" :loading="debugging" :disabled="!canDebug" @click="debugActions.debugService">调试</el-button>
      </div>
    </div>
    <el-form-item label="请求 Path">
      <el-input :model-value="endpointUrl" readonly />
    </el-form-item>
    <div class="debug-editor-layout">
      <div class="debug-request-editor">
        <el-tabs v-model="activeRequestPaneModel" class="debug-tabs">
          <el-tab-pane label="Header" name="headers">
            <JsonEditor
              v-model="debugHeadersModel"
              title="请求 Header"
              description="会作为调试请求的 Header 参数。"
              :placeholder="debugHeaderPlaceholder"
              height="260px"
            />
          </el-tab-pane>
          <el-tab-pane label="Query" name="query">
            <JsonEditor
              v-model="debugQueryModel"
              title="请求 Query"
              description="GET 参数和 Query 位置的发布参数会填在这里。"
              :placeholder="debugQueryPlaceholder"
              height="260px"
            />
          </el-tab-pane>
          <el-tab-pane label="Body" name="body">
            <JsonEditor
              v-model="debugBodyModel"
              title="请求 Body"
              description="POST Body 位置的发布参数会填在这里。"
              :placeholder="debugBodyPlaceholder"
              height="260px"
            />
          </el-tab-pane>
        </el-tabs>
      </div>
      <JsonEditor
        v-model="debugResultModel"
        title="返回结果"
        description="展示后端统一 Result 包装后的调试响应。"
        placeholder="调试后展示返回 JSON"
        height="360px"
        readonly
      />
    </div>
    <div class="curl-panel">
      <div class="curl-panel__header">
        <div>
          <strong>cURL 调用命令</strong>
          <p>根据当前请求 Path、Header、Query、Body 生成开放服务调用命令，Token 使用占位符。</p>
        </div>
        <el-button plain :disabled="!curlCommand" @click="debugActions.copyCurlCommand">复制 cURL</el-button>
      </div>
      <el-input
        v-model="curlCommandModel"
        type="textarea"
        :rows="8"
        readonly
        placeholder="点击“生成 cURL(bash)”或“生成 cURL(cmd)”后展示命令"
      />
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { SectionCard } from "@studio/ui";
import JsonEditor from "@/components/JsonEditor.vue";

interface DebugSectionActions {
  generateDebugTemplate: () => void;
  generateCurlCommand: (mode: "bash" | "cmd") => void;
  debugService: () => void | Promise<void>;
  copyCurlCommand: () => void | Promise<void>;
}

const props = defineProps<{
  endpointUrl: string;
  canDebug: boolean;
  debugging: boolean;
  activeRequestPane: string;
  debugHeaders: string;
  debugQuery: string;
  debugBody: string;
  debugResult: string;
  curlCommand: string;
  debugHeaderPlaceholder: string;
  debugQueryPlaceholder: string;
  debugBodyPlaceholder: string;
  debugActions: DebugSectionActions;
}>();

const emit = defineEmits<{
  "update:activeRequestPane": [value: string];
  "update:debugHeaders": [value: string];
  "update:debugQuery": [value: string];
  "update:debugBody": [value: string];
  "update:debugResult": [value: string];
  "update:curlCommand": [value: string];
}>();

const activeRequestPaneModel = computed({
  get: () => props.activeRequestPane,
  set: (value: string) => emit("update:activeRequestPane", value),
});

const debugHeadersModel = computed({
  get: () => props.debugHeaders,
  set: (value: string) => emit("update:debugHeaders", value),
});

const debugQueryModel = computed({
  get: () => props.debugQuery,
  set: (value: string) => emit("update:debugQuery", value),
});

const debugBodyModel = computed({
  get: () => props.debugBody,
  set: (value: string) => emit("update:debugBody", value),
});

const debugResultModel = computed({
  get: () => props.debugResult,
  set: (value: string) => emit("update:debugResult", value),
});

const curlCommandModel = computed({
  get: () => props.curlCommand,
  set: (value: string) => emit("update:curlCommand", value),
});
</script>

<style scoped>
p {
  margin: 0;
  color: var(--studio-text-soft);
}

.section-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 16px 0 12px;
}

.section-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.debug-toolbar {
  margin-top: 0;
}

.debug-editor-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 0.95fr);
  gap: 16px;
  align-items: start;
}

.debug-request-editor {
  min-width: 0;
}

.debug-tabs :deep(.el-tabs__header) {
  margin-bottom: 8px;
}

.curl-panel {
  margin-top: 16px;
  padding: 14px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: var(--studio-surface-soft);
}

.curl-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

@media (max-width: 1080px) {
  .debug-editor-layout {
    grid-template-columns: 1fr;
  }
}
</style>
