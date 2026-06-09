<template>
  <div class="soap-request-builder">
    <el-alert
      v-if="showHint"
      class="soap-request-builder__alert"
      type="info"
      show-icon
      :closable="false"
      :title="hint"
    />

    <el-form-item v-if="showWsdl" label="WSDL 地址">
      <el-input :model-value="wsdlUrl" readonly :placeholder="wsdlPlaceholder" />
    </el-form-item>

    <div class="soap-request-builder__grid">
      <div class="soap-request-builder__side">
        <section class="soap-request-builder__option soap-request-builder__option--header">
          <div class="soap-request-builder__option-header">
            <div class="soap-request-builder__option-title">
              <span class="soap-request-builder__index">1</span>
              <div>
                <strong>{{ headersTitle }}</strong>
                <p>{{ headersDescription }}</p>
              </div>
            </div>
            <el-radio-group
              :model-value="headersMode"
              size="small"
              @update:model-value="emit('update:headersMode', $event as HeaderEditorMode)"
            >
              <el-radio-button value="form">表格</el-radio-button>
              <el-radio-button value="raw">原始</el-radio-button>
            </el-radio-group>
          </div>
          <div class="soap-request-builder__option-body">
            <template v-if="headersMode === 'form'">
              <el-table :data="headerRows" border size="small" table-layout="fixed" class="soap-request-builder__table">
                <el-table-column label="Header" min-width="170">
                  <template #default="{ row }">
                    <div class="soap-request-builder__field-name">
                      <span>{{ row.label }}</span>
                      <el-tag v-if="row.required" size="small" type="danger" effect="plain">必填</el-tag>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="值" min-width="260">
                  <template #default="{ row }">
                    <el-input
                      :model-value="stringValue(row.value)"
                      @update:model-value="emit('updateHeaderValue', row.key, $event)"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="说明" min-width="160">
                  <template #default="{ row }">
                    <span class="soap-request-builder__field-meta">{{ row.meta }}</span>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!headerRows.length" description="暂无固定 Header，可切换到原始模式填写 JSON" />
            </template>
            <template v-else>
              <el-alert
                v-if="headersError"
                class="soap-request-builder__parse-alert"
                type="error"
                show-icon
                :closable="false"
                :title="headersError"
              />
              <el-input
                :model-value="headers"
                type="textarea"
                :rows="7"
                :placeholder="headersPlaceholder"
                class="soap-request-builder__raw-input"
                @update:model-value="emit('update:headers', String($event ?? ''))"
              />
            </template>
          </div>
        </section>

        <section class="soap-request-builder__option soap-request-builder__option--body">
          <div class="soap-request-builder__option-header">
            <div class="soap-request-builder__option-title">
              <span class="soap-request-builder__index">2</span>
              <div>
                <strong>{{ fieldsTitle }}</strong>
                <p>{{ fieldsDescription }}</p>
              </div>
            </div>
          </div>
          <div class="soap-request-builder__option-body">
            <div v-if="fieldGroups.length" class="soap-request-builder__options soap-request-builder__options--inner">
              <section v-for="group in fieldGroups" :key="group.key">
                <div class="soap-request-builder__subtitle">
                  <strong>{{ group.title }}</strong>
                  <span>{{ group.description }}</span>
                </div>
                <el-table :data="group.rows" border size="small" table-layout="fixed" class="soap-request-builder__table">
                  <el-table-column label="参数" min-width="160">
                    <template #default="{ row }">
                      <div class="soap-request-builder__field-name">
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
                        @update:model-value="emit('updateFieldValue', row.key, $event)"
                      />
                      <el-input-number
                        v-else-if="row.controlType === 'number'"
                        :model-value="typeof row.value === 'number' ? row.value : null"
                        :controls="false"
                        class="soap-request-builder__number"
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
                  <el-table-column label="映射" min-width="150">
                    <template #default="{ row }">
                      <span class="soap-request-builder__field-meta">{{ row.meta }}</span>
                    </template>
                  </el-table-column>
                </el-table>
              </section>
            </div>
            <el-empty v-else :description="emptyDescription" />
          </div>
        </section>
      </div>

      <div class="soap-request-builder__xml-editor">
        <div class="soap-request-builder__xml-header">
          <div>
            <strong>{{ envelopeTitle }}</strong>
            <span>{{ envelopeDescription }}</span>
          </div>
          <el-button plain size="small" @click="formatEnvelope">格式化 XML</el-button>
        </div>
        <el-alert
          v-if="envelopeError"
          class="soap-request-builder__parse-alert"
          type="error"
          show-icon
          :closable="false"
          :title="envelopeError"
        />
        <el-input
          :model-value="envelope"
          type="textarea"
          :rows="envelopeRows"
          :placeholder="envelopePlaceholder"
          class="soap-request-builder__raw-input"
          @update:model-value="emit('update:envelope', String($event ?? ''))"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { watch } from "vue";
import { ElMessage } from "element-plus";
import { formatXmlText } from "@/components/open-service/openServiceDebugSupport";

export type HeaderEditorMode = "form" | "raw";
export type DebugControlType = "text" | "number" | "boolean" | "textarea";
export type DebugFieldValue = string | number | boolean | null | undefined;

export interface SoapRequestBuilderField {
  key: string;
  label: string;
  meta: string;
  required?: boolean;
  controlType?: DebugControlType;
  value?: DebugFieldValue;
}

export interface SoapRequestBuilderFieldGroup {
  key: string;
  title: string;
  description: string;
  rows: SoapRequestBuilderField[];
}

const props = withDefaults(
  defineProps<{
    showHint?: boolean;
    hint?: string;
    showWsdl?: boolean;
    wsdlUrl?: string;
    wsdlPlaceholder?: string;
    headersTitle?: string;
    headersDescription?: string;
    headersMode?: HeaderEditorMode;
    headers?: string;
    headersPlaceholder?: string;
    headersError?: string;
    headerRows?: SoapRequestBuilderField[];
    fieldsTitle?: string;
    fieldsDescription?: string;
    fieldGroups?: SoapRequestBuilderFieldGroup[];
    emptyDescription?: string;
    envelopeTitle?: string;
    envelopeDescription?: string;
    envelope?: string;
    envelopeError?: string;
    envelopePlaceholder?: string;
    envelopeRows?: number;
  }>(),
  {
    showHint: false,
    hint: "SOAP Envelope 会作为本次 SOAP 调用的完整 XML 请求 Body 发送；HTTP Headers 只表示 HTTP 层请求头。",
    showWsdl: false,
    wsdlUrl: "",
    wsdlPlaceholder: "保存并启用 WebService 后生成",
    headersTitle: "HTTP Headers",
    headersDescription: "HTTP 层请求头，可在表格和原始 JSON 之间双向切换。",
    headersMode: "form",
    headers: "{}",
    headersPlaceholder: "{\n  \"X-Trace-Id\": \"debug-001\"\n}",
    headersError: "",
    headerRows: () => [],
    fieldsTitle: "SOAP 参数表单",
    fieldsDescription: "字段值会实时构造成完整 SOAP Envelope。",
    fieldGroups: () => [],
    emptyDescription: "请先配置字段映射",
    envelopeTitle: "SOAP 请求 Body（Envelope）",
    envelopeDescription: "SOAP Envelope 是完整 XML 请求体。",
    envelope: "",
    envelopeError: "",
    envelopePlaceholder: "填写完整 SOAP Envelope XML",
    envelopeRows: 18,
  },
);

const emit = defineEmits<{
  "update:headersMode": [value: HeaderEditorMode];
  "update:headers": [value: string];
  "update:envelope": [value: string];
  updateHeaderValue: [key: string, value: DebugFieldValue];
  updateFieldValue: [key: string, value: DebugFieldValue];
  validChange: [value: boolean];
}>();

watch(
  [() => props.headersError, () => props.envelopeError],
  () => emit("validChange", !props.headersError && !props.envelopeError),
  { immediate: true },
);

function stringValue(value: DebugFieldValue) {
  return value == null ? "" : String(value);
}

function formatEnvelope() {
  const result = formatXmlText(props.envelope || "", "SOAP Envelope");
  if (!result.ok) {
    ElMessage.error(result.error);
    return;
  }
  emit("update:envelope", result.value);
}
</script>

<style scoped>
.soap-request-builder {
  display: grid;
  gap: 14px;
}

.soap-request-builder__alert,
.soap-request-builder__parse-alert {
  margin-bottom: 10px;
}

.soap-request-builder__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(340px, 0.8fr);
  gap: 14px;
  align-items: start;
}

.soap-request-builder__side,
.soap-request-builder__options {
  display: grid;
  gap: 14px;
}

.soap-request-builder__options--inner {
  gap: 12px;
}

.soap-request-builder__option {
  position: relative;
  display: grid;
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--studio-border);
  border-left: 4px solid var(--studio-primary);
  border-radius: 8px;
  background: var(--studio-surface-strong, #fff);
}

.soap-request-builder__option--body {
  border-left-color: var(--studio-success);
}

.soap-request-builder__option-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid rgba(64, 113, 187, 0.12);
  background: rgba(15, 35, 66, 0.025);
}

.soap-request-builder__option-title {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px;
  min-width: 0;
}

.soap-request-builder__index {
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

.soap-request-builder__option-title strong {
  display: block;
  margin-bottom: 4px;
  color: var(--studio-text);
  font-size: 14px;
  line-height: 1.35;
}

.soap-request-builder__option-title p {
  margin: 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.55;
}

.soap-request-builder__option-body {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 14px 16px 16px;
  background: #fff;
}

.soap-request-builder__table :deep(.cell) {
  white-space: normal;
}

.soap-request-builder__table :deep(th.el-table__cell) {
  background: rgba(16, 78, 139, 0.05);
}

.soap-request-builder__field-name {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.soap-request-builder__field-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.soap-request-builder__subtitle {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  color: var(--studio-text);
}

.soap-request-builder__subtitle span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.soap-request-builder__number {
  width: 100%;
}

.soap-request-builder__raw-input :deep(.el-textarea__inner),
.soap-request-builder__xml-editor :deep(.el-textarea__inner) {
  font-family: "Cascadia Code", "Consolas", monospace;
  font-size: 13px;
  line-height: 1.6;
}

.soap-request-builder__xml-editor {
  display: grid;
  gap: 8px;
}

.soap-request-builder__xml-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  color: var(--studio-text);
}

.soap-request-builder__xml-header > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.soap-request-builder__xml-header span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

@media (max-width: 960px) {
  .soap-request-builder__grid {
    grid-template-columns: 1fr;
  }
}
</style>
