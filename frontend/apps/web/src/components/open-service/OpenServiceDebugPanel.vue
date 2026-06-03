<template>
  <SectionCard :title="title" :description="description">
    <el-alert
      v-if="!canDebug"
      class="service-debug-alert"
      type="info"
      show-icon
      :closable="false"
      :title="disabledHint"
    />
    <el-alert
      v-if="warningMessage"
      class="service-debug-alert"
      type="warning"
      show-icon
      :closable="false"
      :title="warningMessage"
    />

    <div class="service-debug-toolbar">
      <el-radio-group :model-value="mode" size="small" @update:model-value="emit('update:mode', $event as DebugMode)">
        <el-radio-button value="form">表单模式</el-radio-button>
        <el-radio-button value="raw">原始模式</el-radio-button>
        <el-radio-button v-if="showSoapMode" value="soap">SOAP 模式</el-radio-button>
      </el-radio-group>
      <div class="service-debug-actions">
        <el-button v-if="mode === 'form'" plain @click="emit('resetForm')">重置样例</el-button>
        <template v-if="showCurl">
          <el-button plain :disabled="curlDisabled" @click="emit('generateCurl', 'bash')">生成 cURL(bash)</el-button>
          <el-button plain :disabled="curlDisabled" @click="emit('generateCurl', 'cmd')">生成 cURL(cmd)</el-button>
        </template>
        <el-button
          v-if="mode === 'soap'"
          plain
          :disabled="!canDebug || !showSoapMode"
          :loading="soapPreviewLoading"
          @click="emit('generateSoapSample')"
        >
          生成 SOAP 样例
        </el-button>
        <el-button type="primary" :disabled="!canDebug" :loading="debugging" @click="emit('debug')">发送调试</el-button>
      </div>
    </div>

    <el-form-item v-if="mode !== 'soap' && endpointUrl" :label="endpointLabel">
      <el-input :model-value="endpointUrl" readonly />
    </el-form-item>

    <template v-if="mode === 'form'">
      <div v-if="formGroups.length" class="service-debug-options">
        <section
          v-for="(group, index) in formGroups"
          :key="group.key"
          class="service-debug-option"
          :class="`service-debug-option--${group.key}`"
        >
          <div class="service-debug-option__header">
            <div class="service-debug-option__title">
              <span class="service-debug-option__index">{{ index + 1 }}</span>
              <div>
                <strong>{{ group.title }}</strong>
                <p>{{ group.description }}</p>
              </div>
            </div>
          </div>
          <div class="service-debug-option__body">
            <el-table :data="group.rows" border size="small" table-layout="fixed" class="service-debug-table">
              <el-table-column label="参数" min-width="170">
                <template #default="{ row }">
                  <div class="service-debug-field-name">
                    <span>{{ row.label }}</span>
                    <el-tag v-if="row.required" size="small" type="danger" effect="plain">必填</el-tag>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="值" min-width="280">
                <template #default="{ row }">
                  <el-switch
                    v-if="row.controlType === 'boolean'"
                    :model-value="Boolean(row.value)"
                    @update:model-value="emit('updateFieldValue', row.key, $event)"
                  />
                  <el-input-number
                    v-else-if="row.controlType === 'number'"
                    :model-value="typeof row.value === 'number' ? row.value : null"
                    :controls="false"
                    class="service-debug-number"
                    @update:model-value="emit('updateFieldValue', row.key, $event)"
                  />
                  <el-input
                    v-else-if="row.controlType === 'textarea'"
                    type="textarea"
                    :rows="3"
                    :model-value="stringValue(row.value)"
                    @update:model-value="emit('updateFieldValue', row.key, $event)"
                  />
                  <el-input
                    v-else
                    :model-value="stringValue(row.value)"
                    @update:model-value="emit('updateFieldValue', row.key, $event)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="映射" min-width="180">
                <template #default="{ row }">
                  <span class="service-debug-field-meta">{{ row.meta }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </section>
      </div>
      <el-empty v-else :description="emptyFormDescription" />
    </template>

    <template v-else-if="mode === 'raw'">
      <div class="service-debug-options">
        <section
          v-for="(section, index) in rawSections"
          :key="section.key"
          class="service-debug-option"
          :class="`service-debug-option--${section.key}`"
        >
          <div class="service-debug-option__header">
            <div class="service-debug-option__title">
              <span class="service-debug-option__index">{{ index + 1 }}</span>
              <div>
                <strong>{{ section.title }}</strong>
                <p>{{ section.description }}</p>
              </div>
            </div>
          </div>
          <div class="service-debug-option__body">
            <el-alert
              v-if="section.parseError"
              class="service-debug-parse-alert"
              type="error"
              show-icon
              :closable="false"
              :title="section.parseError"
            />
            <el-input
              :model-value="section.value"
              type="textarea"
              :rows="section.rows ?? 6"
              :placeholder="section.placeholder"
              class="service-debug-raw-input"
              @update:model-value="emit('updateRawSection', section.key, String($event ?? ''))"
            />
          </div>
        </section>
      </div>
    </template>

    <template v-else>
      <el-alert
        class="service-debug-alert"
        type="info"
        show-icon
        :closable="false"
        title="SOAP Envelope 会作为本次 SOAP 调用的完整 XML 请求 Body 发送；HTTP Headers 只表示 HTTP 层请求头。"
      />
      <el-form-item label="WSDL 地址">
        <el-input :model-value="wsdlUrl" readonly placeholder="保存并启用 WebService 后生成" />
      </el-form-item>
      <div class="service-debug-soap-grid">
        <div class="service-debug-soap-side">
          <section class="service-debug-option service-debug-option--HEADER">
            <div class="service-debug-option__header">
              <div class="service-debug-option__title">
                <span class="service-debug-option__index">1</span>
                <div>
                  <strong>HTTP Headers</strong>
                  <p>HTTP 层请求头，可在表格和原始 JSON 之间双向切换。</p>
                </div>
              </div>
              <el-radio-group
                :model-value="soapHeadersMode"
                size="small"
                @update:model-value="emit('update:soapHeadersMode', $event as HeaderEditorMode)"
              >
                <el-radio-button value="form">表格</el-radio-button>
                <el-radio-button value="raw">原始</el-radio-button>
              </el-radio-group>
            </div>
            <div class="service-debug-option__body">
              <template v-if="soapHeadersMode === 'form'">
                <el-table :data="soapHeaderRows" border size="small" table-layout="fixed" class="service-debug-table">
                  <el-table-column label="Header" min-width="170">
                    <template #default="{ row }">
                      <div class="service-debug-field-name">
                        <span>{{ row.label }}</span>
                        <el-tag v-if="row.required" size="small" type="danger" effect="plain">必填</el-tag>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="值" min-width="260">
                    <template #default="{ row }">
                      <el-input
                        :model-value="stringValue(row.value)"
                        @update:model-value="emit('updateSoapHeaderValue', row.key, $event)"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column label="说明" min-width="160">
                    <template #default="{ row }">
                      <span class="service-debug-field-meta">{{ row.meta }}</span>
                    </template>
                  </el-table-column>
                </el-table>
              </template>
              <template v-else>
                <el-alert
                  v-if="soapHeadersError"
                  class="service-debug-parse-alert"
                  type="error"
                  show-icon
                  :closable="false"
                  :title="soapHeadersError"
                />
                <el-input
                  :model-value="soapHeaders"
                  type="textarea"
                  :rows="7"
                  :placeholder="soapHeadersPlaceholder"
                  class="service-debug-raw-input"
                  @update:model-value="emit('update:soapHeaders', String($event ?? ''))"
                />
              </template>
            </div>
          </section>

          <section class="service-debug-option service-debug-option--BODY">
            <div class="service-debug-option__header">
              <div class="service-debug-option__title">
                <span class="service-debug-option__index">2</span>
                <div>
                  <strong>SOAP 参数表单</strong>
                  <p>字段值会实时构造成完整 SOAP Envelope。</p>
                </div>
              </div>
            </div>
            <div class="service-debug-option__body">
              <div v-if="soapFieldGroups.length" class="service-debug-options service-debug-options--inner">
                <section v-for="group in soapFieldGroups" :key="group.key">
                  <div class="service-debug-subtitle">
                    <strong>{{ group.title }}</strong>
                    <span>{{ group.description }}</span>
                  </div>
                  <el-table :data="group.rows" border size="small" table-layout="fixed" class="service-debug-table">
                    <el-table-column label="参数" min-width="160">
                      <template #default="{ row }">
                        <div class="service-debug-field-name">
                          <span>{{ row.label }}</span>
                          <el-tag v-if="row.required" size="small" type="danger" effect="plain">必填</el-tag>
                        </div>
                      </template>
                    </el-table-column>
                    <el-table-column label="值" min-width="240">
                      <template #default="{ row }">
                        <el-switch
                          v-if="row.controlType === 'boolean'"
                          :model-value="Boolean(row.value)"
                          @update:model-value="emit('updateSoapFieldValue', row.key, $event)"
                        />
                        <el-input-number
                          v-else-if="row.controlType === 'number'"
                          :model-value="typeof row.value === 'number' ? row.value : null"
                          :controls="false"
                          class="service-debug-number"
                          @update:model-value="emit('updateSoapFieldValue', row.key, $event)"
                        />
                        <el-input
                          v-else-if="row.controlType === 'textarea'"
                          type="textarea"
                          :rows="3"
                          :model-value="stringValue(row.value)"
                          @update:model-value="emit('updateSoapFieldValue', row.key, $event)"
                        />
                        <el-input
                          v-else
                          :model-value="stringValue(row.value)"
                          @update:model-value="emit('updateSoapFieldValue', row.key, $event)"
                        />
                      </template>
                    </el-table-column>
                    <el-table-column label="映射" min-width="150">
                      <template #default="{ row }">
                        <span class="service-debug-field-meta">{{ row.meta }}</span>
                      </template>
                    </el-table-column>
                  </el-table>
                </section>
              </div>
              <el-empty v-else :description="emptyFormDescription" />
            </div>
          </section>
        </div>

        <div class="service-debug-xml-editor">
          <div class="service-debug-xml-editor__header">
            <strong>SOAP 请求 Body（Envelope）</strong>
            <span>{{ soapDescription }}</span>
          </div>
          <el-alert
            v-if="soapEnvelopeError"
            class="service-debug-parse-alert"
            type="error"
            show-icon
            :closable="false"
            :title="soapEnvelopeError"
          />
          <el-input
            :model-value="soapEnvelope"
            type="textarea"
            :rows="18"
            placeholder="点击生成 SOAP 样例后展示完整 XML 请求 Body"
            @update:model-value="emit('update:soapEnvelope', String($event ?? ''))"
          />
        </div>
      </div>
    </template>

    <div v-if="mode === 'soap'" class="service-debug-xml-editor service-debug-response-editor">
      <div class="service-debug-xml-editor__header">
        <div>
          <strong>SOAP 响应 Body（Envelope）</strong>
          <span>SOAP 调试返回 XML Envelope，和开放 WebService 响应格式一致。</span>
        </div>
        <el-button plain size="small" :disabled="!debugResult || debugResult === '{}'" @click="emit('formatSoapResponse')">格式化 XML</el-button>
      </div>
      <el-input
        :model-value="debugResult === '{}' ? '' : debugResult"
        type="textarea"
        :rows="12"
        readonly
        placeholder="发送 SOAP 调试后展示响应 XML"
      />
    </div>
    <JsonEditor v-else :model-value="debugResult" title="调试结果" readonly height="180px" />

    <div v-if="showCurl" class="service-debug-curl-panel">
      <div class="service-debug-curl-panel__header">
        <div>
          <strong>cURL 调用命令</strong>
          <p>{{ curlDescription }}</p>
        </div>
        <el-button plain :disabled="!curlCommand" @click="emit('copyCurl')">复制 cURL</el-button>
      </div>
      <el-input
        :model-value="curlCommand"
        type="textarea"
        :rows="8"
        readonly
        :placeholder="mode === 'soap' ? '点击生成 cURL 后展示 SOAP XML 调用命令' : '点击“生成 cURL(bash)”或“生成 cURL(cmd)”后展示命令'"
      />
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { SectionCard } from "@studio/ui";
import JsonEditor from "@/components/JsonEditor.vue";

export type DebugMode = "form" | "raw" | "soap";
export type HeaderEditorMode = "form" | "raw";
export type DebugControlType = "text" | "number" | "boolean" | "textarea";
export type DebugFieldValue = string | number | boolean | null | undefined;

export interface OpenServiceDebugField {
  key: string;
  label: string;
  meta: string;
  required?: boolean;
  controlType?: DebugControlType;
  value?: DebugFieldValue;
}

export interface OpenServiceDebugGroup {
  key: string;
  title: string;
  description: string;
  rows: OpenServiceDebugField[];
}

export interface OpenServiceRawSection {
  key: string;
  title: string;
  description: string;
  value: string;
  placeholder?: string;
  rows?: number;
  parseError?: string;
}

const props = withDefaults(
  defineProps<{
    title: string;
    description: string;
    mode: DebugMode;
    canDebug: boolean;
    disabledHint: string;
    debugging?: boolean;
    warningMessage?: string;
    endpointLabel?: string;
    endpointUrl?: string;
    formGroups?: OpenServiceDebugGroup[];
    rawSections?: OpenServiceRawSection[];
    emptyFormDescription?: string;
    showCurl?: boolean;
    curlCommand?: string;
    showSoapMode?: boolean;
    soapPreviewLoading?: boolean;
    wsdlUrl?: string;
    soapDescription?: string;
    soapEnvelope?: string;
    soapEnvelopeError?: string;
    soapHeaders?: string;
    soapHeadersPlaceholder?: string;
    soapHeadersMode?: HeaderEditorMode;
    soapHeadersError?: string;
    soapHeaderRows?: OpenServiceDebugField[];
    soapFieldGroups?: OpenServiceDebugGroup[];
    debugResult?: string;
  }>(),
  {
    debugging: false,
    warningMessage: "",
    endpointLabel: "请求 Path",
    endpointUrl: "",
    formGroups: () => [],
    rawSections: () => [],
    emptyFormDescription: "请先配置字段映射",
    showCurl: false,
    curlCommand: "",
    showSoapMode: false,
    soapPreviewLoading: false,
    wsdlUrl: "",
    soapDescription: "SOAP Envelope 是完整 XML 请求体。",
    soapEnvelope: "",
    soapEnvelopeError: "",
    soapHeaders: "{}",
    soapHeadersPlaceholder: "{\n  \"X-Data-Service-Token\": \"...\"\n}",
    soapHeadersMode: "form",
    soapHeadersError: "",
    soapHeaderRows: () => [],
    soapFieldGroups: () => [],
    debugResult: "{}",
  },
);

const emit = defineEmits<{
  "update:mode": [value: DebugMode];
  "update:soapEnvelope": [value: string];
  "update:soapHeaders": [value: string];
  "update:soapHeadersMode": [value: HeaderEditorMode];
  updateFieldValue: [key: string, value: DebugFieldValue];
  updateSoapFieldValue: [key: string, value: DebugFieldValue];
  updateSoapHeaderValue: [key: string, value: DebugFieldValue];
  updateRawSection: [key: string, value: string];
  resetForm: [];
  generateCurl: [mode: "bash" | "cmd"];
  copyCurl: [];
  generateSoapSample: [];
  formatSoapResponse: [];
  debug: [];
}>();

const curlDisabled = computed(() => props.mode === "soap" ? !props.wsdlUrl : !props.endpointUrl);
const curlDescription = computed(() => props.mode === "soap"
  ? "根据 WebService 地址、HTTP Header 和完整 SOAP Envelope XML 生成调用命令。"
  : "根据当前请求 Path、Header、Query、Body 生成开放服务调用命令，Token 使用占位符。");

function stringValue(value: DebugFieldValue) {
  return value == null ? "" : String(value);
}
</script>

<style scoped>
.service-debug-alert {
  margin-bottom: 14px;
}

.service-debug-parse-alert {
  margin-bottom: 10px;
}

.service-debug-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.service-debug-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.service-debug-options {
  display: grid;
  gap: 18px;
  margin-bottom: 14px;
}

.service-debug-options--inner {
  gap: 12px;
  margin-bottom: 0;
}

.service-debug-option {
  position: relative;
  display: grid;
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--studio-border);
  border-left: 4px solid var(--studio-primary);
  border-radius: 8px;
  background: var(--studio-surface-strong, #fff);
}

.service-debug-option--QUERY,
.service-debug-option--params {
  border-left-color: var(--studio-accent);
}

.service-debug-option--BODY,
.service-debug-option--requestBody {
  border-left-color: var(--studio-success);
}

.service-debug-option--FORM {
  border-left-color: var(--el-color-warning);
}

.service-debug-option--HEADER,
.service-debug-option--header {
  border-left-color: var(--studio-primary);
}

.service-debug-option__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid rgba(64, 113, 187, 0.12);
  background: rgba(15, 35, 66, 0.025);
}

.service-debug-option__title {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px;
  min-width: 0;
}

.service-debug-option__index {
  display: inline-grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border: 1px solid rgba(37, 99, 235, 0.18);
  border-radius: 50%;
  background: rgba(37, 99, 235, 0.08);
  color: var(--studio-primary);
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
}

.service-debug-option__title strong {
  display: block;
  margin-bottom: 4px;
  color: var(--studio-text);
  font-size: 14px;
  line-height: 1.35;
}

.service-debug-option__title p {
  margin: 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.55;
}

.service-debug-option__body {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 14px 16px 16px;
  background: #fff;
}

.service-debug-table :deep(.cell) {
  white-space: normal;
}

.service-debug-table :deep(th.el-table__cell) {
  background: rgba(16, 78, 139, 0.05);
}

.service-debug-field-name {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.service-debug-field-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.service-debug-subtitle {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  color: var(--studio-text);
}

.service-debug-subtitle span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.service-debug-number {
  width: 100%;
}

.service-debug-raw-input :deep(.el-textarea__inner),
.service-debug-xml-editor :deep(.el-textarea__inner) {
  font-family: "Cascadia Code", "Consolas", monospace;
  font-size: 13px;
  line-height: 1.6;
}

.service-debug-soap-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(340px, 0.8fr);
  gap: 14px;
  margin-bottom: 14px;
  align-items: start;
}

.service-debug-soap-side {
  display: grid;
  gap: 14px;
}

.service-debug-xml-editor {
  display: grid;
  gap: 8px;
}

.service-debug-response-editor {
  margin-bottom: 14px;
}

.service-debug-xml-editor__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  color: var(--studio-text);
}

.service-debug-xml-editor__header > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.service-debug-xml-editor__header span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.service-debug-curl-panel {
  margin-top: 16px;
  padding: 14px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: var(--studio-surface-soft);
}

.service-debug-curl-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.service-debug-curl-panel__header p {
  margin: 4px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
}

@media (max-width: 960px) {
  .service-debug-soap-grid {
    grid-template-columns: 1fr;
  }

  .service-debug-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
