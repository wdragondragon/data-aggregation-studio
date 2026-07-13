<template>
  <div class="http-webservice-contract-editor">
    <MetaFormRenderer
      v-if="commonFields.length"
      :fields="commonFields"
      :model-value="modelValue"
      :dynamic-function-fields="dynamicFunctionFields"
      @update:model-value="emitMergedValue($event)"
    />

    <template v-if="isSoapMode">
      <section class="contract-section">
        <div class="contract-section__header">
          <div>
            <strong>WebService 契约</strong>
            <p>SOAP 模式下仅维护服务契约和 XML 节点约定，采集任务运行参数中再配置完整 Envelope。</p>
          </div>
        </div>
        <MetaFormRenderer
          :fields="soapContractFields"
          :model-value="modelValue"
          :dynamic-function-fields="dynamicFunctionFields"
          @update:model-value="emitMergedValue($event)"
        />
      </section>

      <section v-if="responseFields.length" class="contract-section contract-section--muted">
        <div class="contract-section__header">
          <div>
            <strong>响应识别</strong>
            <p>用于从 SOAP 响应转换后的结构中判断业务状态和总量。</p>
          </div>
        </div>
        <MetaFormRenderer
          :fields="responseFields"
          :model-value="modelValue"
          :dynamic-function-fields="dynamicFunctionFields"
          @update:model-value="emitMergedValue($event)"
        />
      </section>
    </template>

    <MetaFormRenderer
      v-else-if="restFields.length"
      :fields="restFields"
      :model-value="modelValue"
      :dynamic-function-fields="dynamicFunctionFields"
      @update:model-value="emitMergedValue($event)"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, watch } from "vue";
import type { MetadataFieldDefinition } from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";

const props = withDefaults(
  defineProps<{
    fields: MetadataFieldDefinition[];
    modelValue: Record<string, unknown>;
    dynamicFunctionFields?: string[];
  }>(),
  {
    fields: () => [],
    modelValue: () => ({}),
    dynamicFunctionFields: () => [],
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: Record<string, unknown>];
}>();

const commonKeys = new Set(["physicalName", "description", "protocolMode"]);
const restKeys = new Set(["mode", "resultType", "businessStatusPath", "businessStatusCode", "totalCodePath"]);
const soapContractKeys = new Set(["mode", "resultType", "soapVersion", "namespaceUri", "operationName", "soapAction", "requestRootName", "responseRootName", "wsdlUrl"]);
const responseKeys = new Set(["businessStatusPath", "businessStatusCode", "totalCodePath"]);

const protocolMode = computed(() => String(props.modelValue?.protocolMode ?? "").trim().toUpperCase());
const resultType = computed(() => String(props.modelValue?.resultType ?? "").trim().toLowerCase());
const isSoapMode = computed(() => protocolMode.value === "SOAP");
const commonFields = computed(() => filterFields(commonKeys));
const restFields = computed(() => filterFields(restKeys));
const soapContractFields = computed(() => filterFields(soapContractKeys));
const responseFields = computed(() => filterFields(responseKeys));

watch(
  () => [protocolMode.value, resultType.value],
  ([mode]) => {
    const current = props.modelValue ?? {};
    const patch: Record<string, unknown> = {};
    const expectedResultType = mode === "SOAP" ? "soap" : mode === "REST_XML" ? "xml" : "json";
    if (String(current.resultType ?? "").trim().toLowerCase() !== expectedResultType) {
      patch.resultType = expectedResultType;
    }
    if (mode === "SOAP" && String(current.mode ?? "").trim().toUpperCase() !== "POST") {
      patch.mode = "POST";
    }
    if (mode === "SOAP" && !String(current.soapVersion ?? "").trim()) {
      patch.soapVersion = "SOAP_11";
    }
    if (Object.keys(patch).length) {
      emit("update:modelValue", {
        ...current,
        ...patch,
      });
    }
  },
  { immediate: true },
);

function filterFields(keys: Set<string>) {
  return props.fields.filter((field) => keys.has(field.fieldKey));
}

function emitMergedValue(value: Record<string, unknown>) {
  emit("update:modelValue", {
    ...(props.modelValue ?? {}),
    ...(value ?? {}),
  });
}
</script>

<style scoped>
.http-webservice-contract-editor {
  display: grid;
  gap: 16px;
}

.contract-section {
  display: grid;
  gap: 12px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  padding: 14px 16px 16px;
  background: #fff;
}

.contract-section--muted {
  background: rgba(248, 250, 252, 0.72);
}

.contract-section__header strong {
  display: block;
  margin-bottom: 4px;
  color: var(--studio-text);
  font-size: 14px;
  line-height: 1.35;
}

.contract-section__header p {
  margin: 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.5;
}
</style>
