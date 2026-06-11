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
      :headers-section-visible="headersSectionVisible"
      :headers-index="headersIndex"
      :headers-mode="httpHeadersMode"
      :headers-error="httpHeadersError"
      :headers-placeholder="headersPlaceholder"
      :header-rows="httpHeaderRows"
      :fields-title="builderFieldsTitle"
      :fields-description="builderFieldsDescription"
      :body-section-visible="bodySectionVisible"
      :body-index="bodyIndex"
      :body-form-visible="bodyFormVisible"
      :field-groups="soapFieldGroups"
      :empty-description="soapEmptyDescription"
      :envelope="envelopeText"
      :envelope-error="effectiveEnvelopeError"
      :envelope-title="requestBodyField.fieldName || 'SOAP 请求 Body（Envelope）'"
      :envelope-description="builderEnvelopeDescription"
      :envelope-readonly="envelopeReadonly"
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
  buildSoapFieldsXmlByParentPath,
  buildSoapRecordsRepeatXmlByPath,
  normalizeXmlPathSegments,
  parseJsonObjectText,
  parseSoapEnvelope,
  prettyJsonValue,
  resolveCommonSoapFieldParentPath,
  firstArrayItemOrSelf,
  readPath,
  stringifyDebugValue,
  validateSoapArrayFieldParents,
  type DebugObject,
  type SoapFieldSpec,
} from "@/components/open-service/openServiceDebugSupport";

interface HttpSoapContract {
  soapVersion?: string;
  namespaceUri?: string;
  operationName?: string;
  requestRootName?: string;
}

export interface HttpSoapRuntimeFieldSpec {
  name: string;
  parentNode?: string;
}

const props = withDefaults(
  defineProps<{
    fields: MetadataFieldDefinition[];
    modelValue: Record<string, unknown>;
    dynamicFunctionFields?: string[];
    soapFieldNames?: string[];
    soapFields?: HttpSoapRuntimeFieldSpec[];
    soapTemplateMode?: boolean;
    headersSectionVisible?: boolean;
    headersIndex?: string;
    bodySectionVisible?: boolean;
    bodyIndex?: string;
    bodyFormVisible?: boolean;
    envelopeReadonly?: boolean;
    soapContract?: HttpSoapContract;
  }>(),
  {
    fields: () => [],
    modelValue: () => ({}),
    dynamicFunctionFields: () => [],
    soapFieldNames: () => [],
    soapFields: () => [],
    soapTemplateMode: false,
    headersSectionVisible: true,
    headersIndex: "1",
    bodySectionVisible: true,
    bodyIndex: "2",
    bodyFormVisible: true,
    envelopeReadonly: false,
    soapContract: () => ({}),
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: Record<string, unknown>];
}>();

const httpObjectKeys = new Set(["header", "params"]);
const requestBodyField = computed(() => props.fields.find((field) => field.fieldKey === "requestBody"));
const queryFields = computed(() => props.fields.filter((field) => field.fieldKey === "params"));
const isArrayPayloadMode = computed(() =>
  props.soapTemplateMode && String(props.modelValue?.payloadMode ?? "object").trim().toLowerCase() === "array");
const legacyDefaultDataNodePath = "records.record";
const inferredArrayDataNodePath = computed(() => resolveCommonSoapFieldParentPath(normalizedSoapFields.value));
const arrayDataNodePath = computed(() => {
  const configured = String(props.modelValue?.dataNodePath ?? "").trim();
  const inferred = inferredArrayDataNodePath.value;
  if (!configured || (configured === legacyDefaultDataNodePath && inferred && inferred !== configured)) {
    return inferred;
  }
  return configured;
});
const arrayPathError = computed(() =>
  isArrayPayloadMode.value ? validateSoapArrayFieldParents(buildSoapFieldSpecs("BODY"), arrayDataNodePath.value) : "");
const effectiveEnvelopeError = computed(() => arrayPathError.value || xmlError.value);
const baseFields = computed(() => props.fields.filter((field) =>
  !httpObjectKeys.has(field.fieldKey)
  && field.fieldKey !== "requestBody"
  && (field.fieldKey !== "dataNodePath" || isArrayPayloadMode.value)));
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
const latestModelValue = ref<Record<string, unknown>>({});

const envelopeDescription = computed(() =>
  isArrayPayloadMode.value
    ? `SOAP Envelope 是完整 XML 请求 Body；array 模式按 ${arrayDataNodePath.value} 生成数组节点，并必须包含 {{#records}}...{{/records}} repeat 块。`
    : "SOAP Envelope 是完整 XML 请求 Body，可使用 {{fieldName}} 引用当前记录字段。");
const builderFieldsTitle = computed(() =>
  props.bodyFormVisible ? "SOAP 参数表单" : "SOAP Body XML 预览");
const builderFieldsDescription = computed(() =>
  props.bodyFormVisible
    ? "字段值会实时构造成完整 SOAP Envelope。"
    : "这里展示由字段映射自动生成的 SOAP Body 结构。");
const builderEnvelopeDescription = computed(() =>
  props.bodyFormVisible
    ? (requestBodyField.value?.description || envelopeDescription.value)
    : "字段映射变化后会自动更新最终 XML；{{targetField}} 表示映射后的目标字段变量。");
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

const normalizedSoapFields = computed<HttpSoapRuntimeFieldSpec[]>(() => {
  const source: HttpSoapRuntimeFieldSpec[] = props.soapFields.length
    ? props.soapFields
    : props.soapFieldNames.map((name) => ({ name }));
  const seen = new Set<string>();
  const result: HttpSoapRuntimeFieldSpec[] = [];
  for (const item of source) {
    const name = String(item?.name ?? "").trim();
    if (!name || seen.has(name)) {
      continue;
    }
    seen.add(name);
    result.push({
      name,
      parentNode: String(item?.parentNode ?? "").trim() || undefined,
    });
  }
  return result;
});
const normalizedSoapFieldNames = computed(() => normalizedSoapFields.value.map((field) => field.name));
const soapBodyNames = computed(() => {
  const configured = normalizedSoapFieldNames.value;
  if (props.soapTemplateMode) {
    return configured;
  }
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
    meta: props.soapTemplateMode
      ? (isArrayPayloadMode.value ? `${arrayDataNodePath.value}.${name} / {{${name}}}` : `模板变量 {{${name}}}`)
      : "SOAP Body",
    controlType: "text" as const,
    value: soapFieldValues[soapValueKey("BODY", name)],
  }));
  if (bodyRows.length) {
    groups.push({
      key: "soap-body",
      title: props.soapTemplateMode
        ? (isArrayPayloadMode.value ? "SOAP Body 数组模板参数" : "SOAP Body 模板参数")
        : "SOAP Body 参数",
      description: props.soapTemplateMode
        ? (isArrayPayloadMode.value
          ? `字段默认写入 ${arrayDataNodePath.value} 节点模板，运行时按 batch 重复渲染。`
          : "字段默认使用 {{fieldName}} 占位符，运行时由当前 Record 渲染。")
        : "字段值会写入当前 SOAP 操作节点。",
      rows: bodyRows,
    });
  }
  return groups;
});

watch(
  () => props.modelValue,
  (value) => {
    latestModelValue.value = { ...(value ?? {}) };
  },
  { immediate: true, deep: true },
);

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
    () => normalizedSoapFields.value.map((field) => `${field.name}:${field.parentNode ?? ""}`).join(","),
    () => props.soapTemplateMode,
    () => props.modelValue?.payloadMode,
    () => props.modelValue?.dataNodePath,
    () => inferredArrayDataNodePath.value,
    () => props.soapContract?.requestRootName,
    () => props.soapContract?.operationName,
  ],
  () => {
    syncInferredArrayDataNodePath();
    syncConfiguredSoapFieldDefaults();
    if (
      props.soapTemplateMode
      || (!envelopeText.value.trim() && !initializedEnvelope.value)
      || (envelopeText.value.trim() && !effectiveEnvelopeError.value)
    ) {
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
    if (envelopeText.value.trim() && !effectiveEnvelopeError.value) {
      rebuildEnvelopeFromFields();
    }
  },
);

function emitMergedValue(value: Record<string, unknown>) {
  const nextValue = {
    ...latestModelValue.value,
    ...(value ?? {}),
  };
  latestModelValue.value = nextValue;
  emit("update:modelValue", nextValue);
}

function emitRuntimeValue(key: string, value: unknown) {
  const nextValue = {
    ...latestModelValue.value,
    [key]: value,
  };
  latestModelValue.value = nextValue;
  emit("update:modelValue", nextValue);
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
  if (isArrayPayloadMode.value) {
    const pathError = validateSoapArrayFieldParents(buildSoapFieldSpecs("BODY"), arrayDataNodePath.value);
    if (pathError) {
      return;
    }
    const repeatError = validateRecordsRepeatBlock(value);
    if (repeatError) {
      xmlError.value = repeatError;
      return;
    }
  }
  const parsed = parseSoapEnvelope(value);
  if (!parsed.ok) {
    xmlError.value = parsed.error;
    return;
  }
  xmlError.value = "";
  const bodyValues = normalizeParsedBodyValues(parsed.values);
  discoveredBodyNames.value = uniqueNames([...Object.keys(bodyValues), ...discoveredBodyNames.value]);
  discoveredSoapHeaderNames.value = uniqueNames([...Object.keys(parsed.headerValues), ...discoveredSoapHeaderNames.value]);
  syncSoapValuesFromEnvelope(bodyValues, parsed.headerValues);
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
  const bodyFields = buildSoapFieldSpecs("BODY");
  if (isArrayPayloadMode.value) {
    const pathError = validateSoapArrayFieldParents(bodyFields, arrayDataNodePath.value);
    if (pathError) {
      return;
    }
  }
  const nextValue = buildSoapEnvelope({
    soapVersion: resolveSoapVersion(),
    namespaceUri: resolveNamespaceUri(),
    requestRootName: resolveRequestRootName(),
    includeToken: false,
    headerFields: buildSoapFieldSpecs("HEADER"),
    fields: [],
    bodyInnerXml: isArrayPayloadMode.value
      ? buildSoapRecordsRepeatXmlByPath(bodyFields, arrayDataNodePath.value)
      : buildSoapFieldsXmlByParentPath(bodyFields),
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
    parentNode: target === "BODY" ? soapFieldParentNode(name) : undefined,
  }));
}

function normalizeParsedBodyValues(values: DebugObject) {
  const configuredFields = normalizedSoapFields.value;
  if (!isArrayPayloadMode.value) {
    if (configuredFields.length) {
      const extracted = extractConfiguredBodyValues(values, configuredFields, []);
      if (Object.keys(extracted).length) {
        return extracted;
      }
    }
    return values;
  }
  const segments = normalizeXmlPathSegments(arrayDataNodePath.value);
  if (!segments.length) {
    return values;
  }
  const record = firstArrayItemOrSelf(readPath(values, segments.join(".")));
  if (record && typeof record === "object" && !Array.isArray(record)) {
    if (configuredFields.length) {
      const extracted = extractConfiguredBodyValues(record as DebugObject, configuredFields, segments);
      if (Object.keys(extracted).length) {
        return extracted;
      }
    }
    return record as DebugObject;
  }
  return values;
}

function extractConfiguredBodyValues(source: DebugObject, fields: HttpSoapRuntimeFieldSpec[], baseSegments: string[]) {
  const result: DebugObject = {};
  fields.forEach((field) => {
    const parentSegments = normalizeXmlPathSegments(field.parentNode);
    const relativeParent = startsWithPath(parentSegments, baseSegments)
      ? parentSegments.slice(baseSegments.length)
      : parentSegments;
    const value = readPath(source, [...relativeParent, field.name].join("."));
    if (value !== undefined) {
      result[field.name] = value;
    }
  });
  return result;
}

function startsWithPath(value: string[], prefix: string[]) {
  if (!prefix.length) {
    return true;
  }
  if (value.length < prefix.length) {
    return false;
  }
  return prefix.every((segment, index) => value[index] === segment);
}

function syncInferredArrayDataNodePath() {
  if (!isArrayPayloadMode.value || !inferredArrayDataNodePath.value) {
    return;
  }
  const current = String(latestModelValue.value.dataNodePath ?? props.modelValue?.dataNodePath ?? "").trim();
  if (current && current !== legacyDefaultDataNodePath) {
    return;
  }
  if (current === inferredArrayDataNodePath.value) {
    return;
  }
  emitRuntimeValue("dataNodePath", inferredArrayDataNodePath.value);
}

function soapFieldParentNode(name: string) {
  return normalizedSoapFields.value.find((field) => field.name === name)?.parentNode;
}

function validateRecordsRepeatBlock(value: string) {
  const startToken = "{{#records}}";
  const endToken = "{{/records}}";
  const start = value.indexOf(startToken);
  const end = value.indexOf(endToken);
  if (start < 0 || end < 0) {
    return "SOAP array 模板必须包含 {{#records}}...{{/records}} repeat 块";
  }
  if (start !== value.lastIndexOf(startToken) || end !== value.lastIndexOf(endToken)) {
    return "SOAP array 模板只能包含一个 {{#records}}...{{/records}} repeat 块";
  }
  if (!value.slice(start + startToken.length, end).trim()) {
    return "SOAP array 模板的 repeat 块不能为空";
  }
  return "";
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
