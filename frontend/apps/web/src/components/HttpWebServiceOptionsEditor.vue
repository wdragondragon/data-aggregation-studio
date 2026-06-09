<template>
  <div class="http-webservice-options-editor">
    <MetaFormRenderer
      v-if="baseFields.length"
      :fields="baseFields"
      :model-value="modelValue"
      :dynamic-function-fields="baseDynamicFunctionFields"
      @update:model-value="emitMergedValue($event)"
    />

    <HttpRequestOptionsEditor
      v-if="queryFields.length"
      :fields="queryFields"
      :model-value="modelValue"
      :dynamic-function-fields="queryDynamicFunctionFields"
      @update:model-value="emitMergedValue($event)"
    />

    <OpenServiceSoapRequestBuilder
      v-if="requestBodyField"
      :headers="httpHeadersRaw"
      :headers-mode="httpHeadersMode"
      :headers-error="httpHeadersError"
      :headers-placeholder="headersPlaceholder"
      :header-rows="httpHeaderRows"
      :field-groups="soapFieldGroups"
      :empty-description="soapEmptyDescription"
      :envelope="envelopeText"
      :envelope-error="xmlError"
      :envelope-title="requestBodyField.fieldName || 'SOAP 请求 Body（Envelope）'"
      :envelope-description="requestBodyField.description || envelopeDescription"
      :envelope-placeholder="soapEnvelopePlaceholder"
      :envelope-rows="14"
      @update:headers="updateHttpHeadersRaw"
      @update:headers-mode="httpHeadersMode = $event"
      @update:envelope="updateEnvelope"
      @update-header-value="setHttpHeaderValue"
      @update-field-value="setSoapFieldValue"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import type { MetadataFieldDefinition } from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import HttpRequestOptionsEditor from "@/components/HttpRequestOptionsEditor.vue";
import OpenServiceSoapRequestBuilder from "@/components/open-service/OpenServiceSoapRequestBuilder.vue";
import type {
  DebugFieldValue,
  HeaderEditorMode,
  SoapRequestBuilderField,
  SoapRequestBuilderFieldGroup,
} from "@/components/open-service/OpenServiceSoapRequestBuilder.vue";
import {
  buildSoapEnvelope,
  parseJsonObjectText,
  parseSoapEnvelope,
  prettyJsonValue,
  stringifyDebugValue,
  type DebugObject,
  type SoapFieldSpec,
} from "@/components/open-service/openServiceDebugSupport";

interface HttpSoapContract {
  soapVersion?: string;
  namespaceUri?: string;
  operationName?: string;
  requestRootName?: string;
}

const props = withDefaults(
  defineProps<{
    fields: MetadataFieldDefinition[];
    modelValue: Record<string, unknown>;
    dynamicFunctionFields?: string[];
    soapFieldNames?: string[];
    soapTemplateMode?: boolean;
    soapContract?: HttpSoapContract;
  }>(),
  {
    fields: () => [],
    modelValue: () => ({}),
    dynamicFunctionFields: () => [],
    soapFieldNames: () => [],
    soapTemplateMode: false,
    soapContract: () => ({}),
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: Record<string, unknown>];
}>();

const httpObjectKeys = new Set(["header", "params"]);
const requestBodyField = computed(() => props.fields.find((field) => field.fieldKey === "requestBody"));
const queryFields = computed(() => props.fields.filter((field) => field.fieldKey === "params"));
const baseFields = computed(() => props.fields.filter((field) => !httpObjectKeys.has(field.fieldKey) && field.fieldKey !== "requestBody"));
const queryDynamicFunctionFields = computed(() => props.dynamicFunctionFields.filter((field) => field === "params"));
const baseDynamicFunctionFields = computed(() => props.dynamicFunctionFields.filter((field) => !httpObjectKeys.has(field) && field !== "requestBody"));

const httpHeadersMode = ref<HeaderEditorMode>("form");
const httpHeadersRaw = ref("{}");
const httpHeadersError = ref("");
const httpHeaders = reactive<DebugObject>({});
const envelopeText = ref("");
const xmlError = ref("");
const soapFieldValues = reactive<Record<string, DebugFieldValue>>({});
const discoveredBodyNames = ref<string[]>([]);
const discoveredSoapHeaderNames = ref<string[]>([]);
const initializedEnvelope = ref(false);
const pendingEnvelopeText = ref<string | undefined>();
const pendingHeaderText = ref<string | undefined>();

const envelopeDescription = "SOAP Envelope 是完整 XML 请求 Body，可使用 {{fieldName}} 引用当前记录字段。";
const soapEnvelopePlaceholder = [
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
  "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:tns=\"urn:studio\">",
  "  <soap:Body>",
  "    <tns:Operation>",
  "      <id>{{id}}</id>",
  "    </tns:Operation>",
  "  </soap:Body>",
  "</soap:Envelope>",
].join("\n");
const headersPlaceholder = "{\n  \"Authorization\": \"Bearer ...\",\n  \"X-Trace-Id\": \"debug-001\"\n}";

const normalizedSoapFieldNames = computed(() => uniqueNames(props.soapFieldNames));
const soapBodyNames = computed(() => {
  const configured = normalizedSoapFieldNames.value;
  return configured.length ? configured : discoveredBodyNames.value;
});
const soapEmptyDescription = computed(() =>
  props.soapTemplateMode
    ? "暂未发现目标字段；可直接编辑完整 SOAP Envelope XML 模板。"
    : "暂未发现 SOAP 参数；可直接编辑完整 SOAP Envelope XML。");

const httpHeaderRows = computed<SoapRequestBuilderField[]>(() =>
  Object.entries(httpHeaders).map(([key, value]) => ({
    key,
    label: key,
    meta: "HTTP Header",
    controlType: "text",
    value: value as DebugFieldValue,
  })),
);

const soapFieldGroups = computed<SoapRequestBuilderFieldGroup[]>(() => {
  const groups: SoapRequestBuilderFieldGroup[] = [];
  const headerRows = discoveredSoapHeaderNames.value.map((name) => ({
    key: soapValueKey("HEADER", name),
    label: name,
    meta: "SOAP Header",
    controlType: "text" as const,
    value: soapFieldValues[soapValueKey("HEADER", name)],
  }));
  if (headerRows.length) {
    groups.push({
      key: "soap-header",
      title: "SOAP Header",
      description: "写入 Envelope Header 的业务节点；HTTP Header 请在上方配置。",
      rows: headerRows,
    });
  }
  const bodyRows = soapBodyNames.value.map((name) => ({
    key: soapValueKey("BODY", name),
    label: name,
    meta: props.soapTemplateMode ? `模板变量 {{${name}}}` : "SOAP Body",
    controlType: "text" as const,
    value: soapFieldValues[soapValueKey("BODY", name)],
  }));
  if (bodyRows.length) {
    groups.push({
      key: "soap-body",
      title: props.soapTemplateMode ? "SOAP Body 模板参数" : "SOAP Body 参数",
      description: props.soapTemplateMode
        ? "字段默认使用 {{fieldName}} 占位符，运行时由当前 Record 渲染。"
        : "字段值会写入当前 SOAP 操作节点。",
      rows: bodyRows,
    });
  }
  return groups;
});

watch(
  () => props.modelValue?.header,
  (value) => syncHttpHeadersFromModel(value),
  { immediate: true },
);

watch(
  () => props.modelValue?.requestBody,
  (value) => syncEnvelopeFromModel(value),
  { immediate: true },
);

watch(
  [
    () => normalizedSoapFieldNames.value.join(","),
    () => props.soapTemplateMode,
    () => props.soapContract?.requestRootName,
    () => props.soapContract?.operationName,
  ],
  () => {
    syncConfiguredSoapFieldDefaults();
    if ((!envelopeText.value.trim() && !initializedEnvelope.value) || (envelopeText.value.trim() && !xmlError.value)) {
      rebuildEnvelopeFromFields();
    }
  },
  { immediate: true },
);

watch(
  [
    () => props.modelValue?.soapVersion,
    () => props.soapContract?.soapVersion,
    () => props.soapContract?.namespaceUri,
  ],
  () => {
    if (envelopeText.value.trim() && !xmlError.value) {
      rebuildEnvelopeFromFields();
    }
  },
);

function emitMergedValue(value: Record<string, unknown>) {
  emit("update:modelValue", {
    ...(props.modelValue ?? {}),
    ...(value ?? {}),
  });
}

function emitRuntimeValue(key: string, value: unknown) {
  emit("update:modelValue", {
    ...(props.modelValue ?? {}),
    [key]: value,
  });
}

function syncHttpHeadersFromModel(value: unknown) {
  const text = stringifyObjectRuntimeValue(value);
  if (pendingHeaderText.value === text) {
    pendingHeaderText.value = undefined;
    return;
  }
  httpHeadersRaw.value = text;
  const parsed = parseJsonObjectText(text, "HTTP Headers");
  if (!parsed.ok) {
    httpHeadersError.value = parsed.error;
    httpHeadersMode.value = "raw";
    return;
  }
  httpHeadersError.value = "";
  replaceDebugObject(httpHeaders, parsed.value);
}

function updateHttpHeadersRaw(value: string) {
  httpHeadersRaw.value = value;
  const parsed = parseJsonObjectText(value, "HTTP Headers");
  if (!parsed.ok) {
    httpHeadersError.value = parsed.error;
    emitRuntimeHeader(value.trim() ? value : "{}");
    return;
  }
  httpHeadersError.value = "";
  replaceDebugObject(httpHeaders, parsed.value);
  emitRuntimeHeader(value.trim() ? value : "{}");
}

function setHttpHeaderValue(key: string, value: DebugFieldValue) {
  httpHeaders[key] = value ?? "";
  httpHeadersRaw.value = prettyJsonValue(httpHeaders);
  httpHeadersError.value = "";
  emitRuntimeHeader(httpHeadersRaw.value);
}

function emitRuntimeHeader(value: string) {
  pendingHeaderText.value = value;
  emitRuntimeValue("header", value);
}

function syncEnvelopeFromModel(value: unknown) {
  const text = stringifyStringRuntimeValue(value);
  if (pendingEnvelopeText.value === text) {
    pendingEnvelopeText.value = undefined;
    return;
  }
  envelopeText.value = text;
  validateAndParseEnvelope(text);
  if (!text.trim()) {
    initializedEnvelope.value = false;
  }
}

function updateEnvelope(value: string) {
  envelopeText.value = value;
  validateAndParseEnvelope(value);
  pendingEnvelopeText.value = value;
  emitRuntimeValue("requestBody", value);
}

function setSoapFieldValue(key: string, value: DebugFieldValue) {
  soapFieldValues[key] = value;
  rebuildEnvelopeFromFields();
}

function validateAndParseEnvelope(value: string) {
  const text = value.trim();
  if (!text) {
    xmlError.value = "";
    return;
  }
  const parsed = parseSoapEnvelope(value);
  if (!parsed.ok) {
    xmlError.value = parsed.error;
    return;
  }
  xmlError.value = "";
  discoveredBodyNames.value = uniqueNames([...Object.keys(parsed.values), ...discoveredBodyNames.value]);
  discoveredSoapHeaderNames.value = uniqueNames([...Object.keys(parsed.headerValues), ...discoveredSoapHeaderNames.value]);
  syncSoapValuesFromEnvelope(parsed.values, parsed.headerValues);
}

function syncSoapValuesFromEnvelope(bodyValues: DebugObject, headerValues: DebugObject) {
  for (const [name, value] of Object.entries(headerValues)) {
    soapFieldValues[soapValueKey("HEADER", name)] = toDebugFieldValue(value);
  }
  for (const [name, value] of Object.entries(bodyValues)) {
    soapFieldValues[soapValueKey("BODY", name)] = toDebugFieldValue(value);
  }
}

function syncConfiguredSoapFieldDefaults() {
  for (const name of soapBodyNames.value) {
    const key = soapValueKey("BODY", name);
    if (!(key in soapFieldValues)) {
      soapFieldValues[key] = props.soapTemplateMode ? `{{${name}}}` : "";
    }
  }
}

function rebuildEnvelopeFromFields() {
  if (!requestBodyField.value) {
    return;
  }
  syncConfiguredSoapFieldDefaults();
  const nextValue = buildSoapEnvelope({
    soapVersion: resolveSoapVersion(),
    namespaceUri: resolveNamespaceUri(),
    requestRootName: resolveRequestRootName(),
    includeToken: false,
    headerFields: buildSoapFieldSpecs("HEADER"),
    fields: buildSoapFieldSpecs("BODY"),
  });
  if (nextValue === envelopeText.value && nextValue === stringifyStringRuntimeValue(props.modelValue?.requestBody)) {
    return;
  }
  initializedEnvelope.value = true;
  envelopeText.value = nextValue;
  xmlError.value = "";
  pendingEnvelopeText.value = nextValue;
  emitRuntimeValue("requestBody", nextValue);
}

function buildSoapFieldSpecs(target: "HEADER" | "BODY"): SoapFieldSpec[] {
  const names = target === "HEADER" ? discoveredSoapHeaderNames.value : soapBodyNames.value;
  return names.map((name) => ({
    key: soapValueKey(target, name),
    elementName: name,
    value: soapFieldValues[soapValueKey(target, name)] ?? (target === "BODY" && props.soapTemplateMode ? `{{${name}}}` : ""),
  }));
}

function resolveSoapVersion() {
  return String(props.modelValue?.soapVersion || props.soapContract?.soapVersion || "SOAP_11");
}

function resolveNamespaceUri() {
  return String(props.soapContract?.namespaceUri || "urn:studio");
}

function resolveRequestRootName() {
  return String(props.soapContract?.requestRootName || props.soapContract?.operationName || "Operation");
}

function soapValueKey(target: "HEADER" | "BODY", name: string) {
  return `${target}:${name}`;
}

function stringifyObjectRuntimeValue(value: unknown) {
  if (value == null || value === "") {
    return "{}";
  }
  if (typeof value === "string") {
    return value.trim() ? value : "{}";
  }
  return prettyJsonValue(value);
}

function stringifyStringRuntimeValue(value: unknown) {
  if (value == null) {
    return "";
  }
  return typeof value === "string" ? value : String(value);
}

function toDebugFieldValue(value: unknown): DebugFieldValue {
  if (value == null || typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return value as DebugFieldValue;
  }
  return stringifyDebugValue(value);
}

function replaceDebugObject(target: DebugObject, source?: Record<string, unknown>) {
  Object.keys(target).forEach((key) => {
    delete target[key];
  });
  Object.assign(target, source ?? {});
}

function uniqueNames(values?: unknown[]) {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const value of values ?? []) {
    const text = String(value ?? "").trim();
    if (!text || seen.has(text)) {
      continue;
    }
    seen.add(text);
    result.push(text);
  }
  return result;
}
</script>

<style scoped>
.http-webservice-options-editor {
  display: grid;
  gap: 18px;
}
</style>
